package com.nextaicommerce.platform.shipping;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

/** Scoped local test orders. These identities never exist in provider order/job tables. */
@Service
public class LocalShippingFixtures {
    public static final String PREFIX="local-fixture:";
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final boolean enabled;
    public LocalShippingFixtures(JdbcTemplate jdbc,ObjectMapper json,@Value("${app.fedex.simulation-enabled:false}") boolean enabled){this.jdbc=jdbc;this.json=json;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    public static boolean identity(String order){return order!=null&&order.startsWith(PREFIX);}
    public record Definition(List<CarrierLabelComposer.Line> packing,String preferredService,FedExPayloads.Address recipient) {
        public Definition(List<CarrierLabelComposer.Line> packing,String preferredService){this(packing,preferredService,null);}
    }
    public record Fixture(UUID id,String name,Definition definition,FedExPayloads.Parcel parcel) {
        public String orderId(){return PREFIX+id;}
    }
    private void scope(UUID tenant,UUID store){
        if(!enabled)throw new IllegalStateException("Local shipping simulation is disabled.");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connections WHERE tenant_id=? AND id=? AND status='ACTIVE' AND channel IN ('AMAZON','SHOPIFY'))",Boolean.class,tenant,store)))
            throw new IllegalArgumentException("Choose an active store in this account.");
    }
    @Transactional(readOnly=true)
    public List<Fixture> list(UUID tenant,UUID store){
        if(!enabled)return List.of();scope(tenant,store);
        return jdbc.query("SELECT * FROM local_shipping_fixtures WHERE tenant_id=? AND marketplace_connection_id=? AND definition->>'kind' IS NULL ORDER BY name,id",(r,n)->
            new Fixture(r.getObject("id",UUID.class),r.getString("name"),json.readValue(r.getString("definition"),Definition.class),
                r.getString("saved_parcel")==null?null:json.readValue(r.getString("saved_parcel"),FedExPayloads.Parcel.class)),tenant,store);
    }
    @Transactional(readOnly=true)
    public Fixture get(UUID tenant,UUID store,String order){
        scope(tenant,store);
        if(!identity(order))throw new IllegalArgumentException("Simulation accepts only explicitly created local fixture orders.");
        UUID id=UUID.fromString(order.substring(PREFIX.length()));
        return list(tenant,store).stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Local test order was not found in this store."));
    }
    public BuyShippingService.Context context(UUID tenant,UUID store,String order){
        var fixture=get(tenant,store,order);var lines=fixture.definition().packing();
        if(lines==null||lines.isEmpty()||lines.stream().anyMatch(l->l.quantity()<1||l.sku()==null||l.sku().isBlank()))throw new IllegalStateException("Local test order requires valid product quantities and SKUs.");
        var items=new ArrayList<BuyShippingRepository.OrderItem>();for(int i=0;i<lines.size();i++){var line=lines.get(i);items.add(new BuyShippingRepository.OrderItem("local-line-"+i,line.sku(),null,line.description(),line.quantity(),0));}
        var sender=new BuyShippingRepository.Address(UUID.fromString("00000000-0000-0000-0000-000000000001"),"Synthetic warehouse","Local Test Sender","LOCAL SIMULATION",
            "10 FedEx Parkway",null,null,"Collierville","TN","38017","US","9015550100",null,true);
        return new BuyShippingService.Context(new BuyShippingRepository.StoreSettings(null,"RATES_ONLY",true),
            new BuyShippingRepository.OrderInfo(order,"Unshipped","MERCHANT",null,null,null,null,null),items,List.of(sender),List.of(),null,List.of(),Map.of());
    }
    @Transactional
    public void saveParcel(UUID tenant,UUID store,String order,FedExPayloads.Parcel parcel){
        var fixture=get(tenant,store,order);FedExPayloads.validate(parcel);
        jdbc.update("UPDATE local_shipping_fixtures SET saved_parcel=?::jsonb WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(parcel),tenant,store,fixture.id());
    }
    @Transactional
    public void saveRecipient(UUID tenant,UUID store,String order,FedExPayloads.Address recipient){
        var fixture=get(tenant,store,order);FedExPayloads.validate(recipient);
        if(!"US".equals(recipient.country()))throw new IllegalArgumentException("Choose a US delivery address.");
        jdbc.update("UPDATE local_shipping_fixtures SET definition=jsonb_set(definition,'{recipient}',?::jsonb) WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(recipient),tenant,store,fixture.id());
    }
}
