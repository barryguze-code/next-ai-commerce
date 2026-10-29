package com.nextaicommerce.platform.shipping;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import com.nextaicommerce.platform.shopify.LocalShopifyShippingUat;
import com.nextaicommerce.platform.shopify.ShopifyOrderPacking;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Genuine sandbox labels and existing local handoff only; never provider fulfillment or stock publication. */
@Service
public class LocalFedExShippingUat {
 private final LocalShopifyShippingUat cycles;private final FedExSandboxRates provider;private final CarrierConnectionRepository connections;private final FedExShipmentRepository shipments;private final CarrierOrderContext orders;private final ShopifyOrderPacking packing;private final ShippingLabelCrypto crypto;private final CarrierLabelComposer composer;private final ObjectMapper json;
 public LocalFedExShippingUat(LocalShopifyShippingUat cycles,FedExSandboxRates provider,CarrierConnectionRepository connections,FedExShipmentRepository shipments,CarrierOrderContext orders,ShopifyOrderPacking packing,ShippingLabelCrypto crypto,CarrierLabelComposer composer,ObjectMapper json){this.cycles=cycles;this.provider=provider;this.connections=connections;this.shipments=shipments;this.orders=orders;this.packing=packing;this.crypto=crypto;this.composer=composer;this.json=json;}
 public boolean enabled(){return cycles.enabled();}
 public String key(UUID tenant,UUID store,String order){UUID id=UUID.fromString(order),cycle=cycles.cycle(tenant,store,id);if(cycle==null)throw new IllegalStateException("Prepare an explicit local UAT round for this order first.");return LocalShopifyShippingUat.shipmentKey(id,cycle);}
 public List<FedExShipmentRepository.Shipment> history(UUID tenant,UUID store,String order){return shipments.list(tenant,store,key(tenant,store,order));}
 public FedExSandboxRates.Quote quote(UUID tenant,UUID store,String order,FedExShippingService.RateOnlyInput input,String actor){
  var c=orders.rateOnlyContext(tenant,store,order);String key=key(tenant,store,order);
  if(shipments.protectedShipment(tenant,store,key)!=null)throw new IllegalStateException("A label exists or needs review. Use its saved history.");
  var sender=c.addresses().stream().filter(a->a.id().equals(input.senderId())).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a sender in this account."));
  var from=new FedExPayloads.Address(sender.contactName(),sender.phone(),sender.line1(),sender.city(),sender.state(),sender.postalCode(),sender.countryCode(),false);
  String reason=packing.eligibility(tenant,store,UUID.fromString(order));if(reason!=null)throw new IllegalStateException(reason);
  var parcel=FedExPackaging.normalize(input.parcel());String packageName="YOUR_PACKAGING".equals(parcel.packagingId())?"Custom packaging":FedExPackaging.option(parcel.packagingId()).label();
  var lines=orders.accountPacking(tenant,store,order).stream().map(l->l.withPackage(packageName)).toList();composer.validateCompact(lines);
  var result=provider.quote(tenant,store,from,input.recipient(),parcel);if(result.rates().isEmpty())return result;
  ObjectNode payload=FedExPayloads.rateRequest(json,provider.account(),from,input.recipient(),parcel);
  var carrier=connections.localSandboxUat(tenant,provider.account());UUID id=shipments.quote(tenant,store,key,carrier,crypto.encrypt(payload.toString().getBytes(StandardCharsets.UTF_8)),result.rates(),lines,actor);
  shipments.orderPackage(tenant,store,order,FedExShippingService.key(c),parcel);
  return new FedExSandboxRates.Quote(result.rates(),false,"SANDBOX","",id);
 }
 public FedExShipmentRepository.Shipment purchase(UUID tenant,UUID store,String order,UUID quote,String service,String actor){
  String key=key(tenant,store,order);var prior=shipments.list(tenant,store,key).stream().filter(s->s.id().equals(quote)).findFirst().orElseThrow(()->new IllegalArgumentException("Quote is not in this store/order/UAT cycle."));
  if("PURCHASED".equals(prior.state())){if("LOCAL_HANDOFF_PENDING".equals(prior.error())){handoff(tenant,store,order,prior,actor);shipments.localHandoffPending(tenant,quote,false);}return prior;}
  orders.rateOnlyContext(tenant,store,order);cycles.refresh(tenant,store,UUID.fromString(order));String reason=packing.eligibility(tenant,store,UUID.fromString(order));if(reason!=null)throw new IllegalStateException(reason);
  var current=orders.accountPacking(tenant,store,order);
  var frozen=json.readValue(prior.packing().toString(),new tools.jackson.core.type.TypeReference<List<CarrierLabelComposer.Line>>(){});
  if(frozen.isEmpty()||frozen.stream().anyMatch(l->!"ACCOUNT_ITEM_V2".equals(l.format())||l.packageName()==null||l.packageName().isBlank())||!samePacking(current,frozen))throw new IllegalStateException("Reserved packing items or locations changed. Request fresh rates before creating a label.");
  var carrier=connections.find(tenant,prior.carrierId());if(!"SANDBOX".equals(carrier.environment())||carrier.simulated()||!provider.account().equals(carrier.accountNumber()))throw new IllegalStateException("Local sandbox carrier changed.");
  var request=shipments.claim(tenant,store,key,quote,service,carrier.revision());if(request==null)throw new IllegalStateException("This quote was used, expired or needs review.");
  try{var rated=(ObjectNode)json.readTree(crypto.decrypt(request.payload(),request.nonce()));var label=provider.ship(tenant,store,FedExPayloads.shipRequest(rated,service));shipments.purchasedUat(tenant,quote,label.trackingNumber(),crypto.encrypt(label.pdf()));}
  catch(FedExClient.CarrierException e){shipments.failure(tenant,quote,e.uncertain());throw e;}
  catch(RuntimeException e){shipments.failure(tenant,quote,true);throw new IllegalStateException("Sandbox outcome needs review. Do not create another label.");}
  try{handoff(tenant,store,order,prior,actor);shipments.localHandoffPending(tenant,quote,false);}catch(RuntimeException e){throw new IllegalStateException("Sandbox label saved; local stock handoff needs review. Use its saved label and resolve warehouse readiness before handoff.");}
  return shipments.list(tenant,store,key).stream().filter(s->s.id().equals(quote)).findFirst().orElseThrow();
 }
 static boolean samePacking(List<CarrierLabelComposer.Line> current,List<CarrierLabelComposer.Line> frozen){
  var packages=frozen.stream().map(CarrierLabelComposer.Line::packageName).distinct().toList();if(packages.size()!=1)return false;
  String header=packages.getFirst();
  // The inventory projection has no package metadata. Compare its stock fields with
  // the package frozen by this quote, never a later mutable order/package choice.
  if(header!=null)current=current.stream().map(l->l.packageName()==null?l.withPackage(header):l).toList();
  return canonicalPacking(current).equals(canonicalPacking(frozen));
 }
 private static java.math.BigDecimal exact(java.math.BigDecimal quantity){return quantity==null?null:quantity.stripTrailingZeros();}
 private static List<CarrierLabelComposer.Line> canonicalPacking(List<CarrierLabelComposer.Line> rows){return rows.stream().map(row->new CarrierLabelComposer.Line(row.brand(),row.sku(),row.description(),row.quantity(),row.allocations().stream().map(a->new CarrierLabelComposer.Allocation(a.itemCode(),exact(a.quantity()),a.expiration(),a.location())).toList(),exact(row.mappedQuantity()),row.packageName(),row.format(),row.accountItemId(),row.locationId())).toList();}
 private void handoff(UUID tenant,UUID store,String order,FedExShipmentRepository.Shipment archived,String actor){
  packing.mark(tenant,store,UUID.fromString(order),true,actor,()->{
   var frozen=json.readValue(archived.packing().toString(),new tools.jackson.core.type.TypeReference<List<CarrierLabelComposer.Line>>(){});
   if(frozen.stream().anyMatch(l->"ACCOUNT_ITEM_V2".equals(l.format()))&&!samePacking(orders.accountPacking(tenant,store,order),frozen))
    throw new IllegalStateException("Saved label packing differs from current warehouse allocations. Review before handoff.");
  });
 }
 public byte[] label(UUID tenant,UUID store,String order,UUID label){
  // Cycle-independent reprints preserve prior archives without unlocking another purchase.
  var archived=shipments.uatLabel(tenant,store,order,label);if(archived==null)throw new IllegalArgumentException("Sandbox label not in this store and order.");
  return composer.compose(crypto.decrypt(archived.payload(),archived.nonce()),order,shipments.uatPacking(tenant,store,order,label),CarrierLabelComposer.Layout.SANDBOX_80_20, FedExClient.Environment.SANDBOX);
 }
}
