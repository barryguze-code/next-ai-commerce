package com.nextaicommerce.platform.fba;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;
import static com.nextaicommerce.platform.fba.FbaInboundAdapter.Operation.*;

/** Durable asynchronous Amazon boundary. Network calls are deliberately outside checkpoint transactions.
 * Ports must be wired to scoped plan locks/audit and server-owned warehouse/packing evidence.
 * No HTTP payload or remote identity is accepted from the browser as authoritative shipment contents.
 */
public final class FbaInboundOrchestration {
    public record Command(UUID requestId,long revision,FbaInboundAdapter.Operation operation,String childId,String optionId,boolean paidConfirmed) {}
    public record Acknowledgement(String operationId,String planId) {}
    public record Option(String id,FbaInboundAdapter.Operation confirmation,long packingVersion,Instant expiresAt,
                         BigDecimal charge,String currency,List<String> children,String childId,String source) {
        public Option { children=List.copyOf(children); }
    }
    public record Checkpoint(Origin origin,Map<UUID,Acknowledgement> acknowledgements,Map<String,Option> options) {
        public Checkpoint { Objects.requireNonNull(origin);acknowledgements=Map.copyOf(acknowledgements);options=Map.copyOf(options); }
        public static Checkpoint initial(Scope scope,UUID warehouse){return new Checkpoint(start(scope,warehouse),Map.of(),Map.of());}
    }
    public record Snapshot(long revision,Checkpoint checkpoint) {}
    public record Audit(UUID requestId,String kind,String actor,Object details) {}
    public record Result(Snapshot snapshot,String message,boolean pending) {}
    public interface Checkpoints {
        Snapshot read(Scope scope,UUID warehouse);
        /** Lock, validate scope/revision, persist checkpoint AND audit, then commit before returning.
         * -1 is reserved for internal acknowledgements/status; user commands always supply a revision.
         */
        Snapshot mutate(Scope scope,UUID warehouse,long expectedRevision,Audit audit,UnaryOperator<Checkpoint> transition);
    }
    public interface Payloads {
        /** Build from validated persisted allocation, managed source address, packing and prep choices. No network. */
        String build(Scope scope,UUID warehouse,Checkpoint state,Command command);
        Map<String,Integer> expectedSkuUnits(Scope scope,UUID warehouse);
    }
    /** Ingest the genuine short-lived Amazon URL immediately into existing scoped document storage.
     * Return durable storage identity, never the expiring URL. No invented/generated label content.
     */
    public interface Documents {
        String ingest(Scope scope,UUID warehouse,String child,long packingVersion,URI genuineAmazonDownload);
        default String ingest(Scope scope,UUID warehouse,String child,long packingVersion,URI genuineAmazonDownload,DocumentKind kind){
            if(kind!=DocumentKind.SHIPPING_LABEL)throw new IllegalStateException("This document kind is not supported by the configured document store.");
            return ingest(scope,warehouse,child,packingVersion,genuineAmazonDownload);
        }
    }
    private final Checkpoints checkpoints;private final Payloads payloads;private final Documents documents;
    private final FbaInboundAdapter adapter;private final ObjectMapper json;private final Clock clock;
    public FbaInboundOrchestration(Checkpoints checkpoints,Payloads payloads,Documents documents,FbaInboundAdapter adapter,ObjectMapper json,Clock clock){
        this.checkpoints=checkpoints;this.payloads=payloads;this.documents=documents;this.adapter=adapter;this.json=json;this.clock=clock;
    }
    private void enabled(){if(!adapter.enabled())throw new IllegalStateException(adapter.unavailable());}
    public Result execute(Scope scope,UUID warehouse,String actor,Command command){
        enabled();Objects.requireNonNull(command);Objects.requireNonNull(command.requestId());
        if(command.revision()<0)throw new IllegalArgumentException("A current plan revision is required.");
        if(command.operation()==null||command.operation()==STATUS)throw new IllegalArgumentException("Use reconciliation for operation status.");
        if(command.operation()==CANCEL&&command.childId()!=null)throw new IllegalArgumentException("The adapter cancels whole inbound plans, not individual shipments.");
        Snapshot before=checkpoints.read(scope,warehouse);requireScope(scope,warehouse,before.checkpoint());
        Attempt replay=before.checkpoint().origin().attempts().get(command.requestId());
        if(replay!=null){
            if(replay.operation()!=command.operation()||!Objects.equals(replay.childId(),command.childId())||!Objects.equals(replay.optionId(),command.optionId()))throw new IllegalStateException("Request identity belongs to a different operation.");
            return new Result(before,replay.status()==Status.PENDING?"Amazon outcome pending. Reconcile before retry.":"This request was already recorded.",replay.status()==Status.PENDING);
        }
        Option reviewed=command.optionId()==null?null:before.checkpoint().options().get(command.optionId());
        if(isConfirmation(command.operation())){
            if(reviewed==null||reviewed.confirmation()!=command.operation()||reviewed.packingVersion()!=before.checkpoint().origin().packingVersion())throw new IllegalStateException("Refresh and review the current option first.");
            Option fresh=loadOptions(scope,before.checkpoint(),kind(command.operation())).get(command.optionId());
            if(!sameOffer(reviewed,fresh))throw new IllegalStateException("Amazon option or charge changed. Refresh and explicitly confirm again.");
            if(command.operation()==CONFIRM_TRANSPORT&&(reviewed.childId()==null||command.childId()==null))throw new IllegalStateException("Select this shipment and its transportation option.");
            if(reviewed.childId()!=null&&!Objects.equals(reviewed.childId(),command.childId()))throw new IllegalStateException("Transportation option belongs to a different shipment.");
            if(reviewed.charge()!=null&&reviewed.charge().signum()>0&&!command.paidConfirmed())throw new IllegalStateException("Explicit paid confirmation is required.");
        }
        AtomicReference<String> body=new AtomicReference<>();
        Snapshot pending=checkpoints.mutate(scope,warehouse,command.revision(),audit(command.requestId(),"INBOUND_PENDING",actor,command),state->{
            requireScope(scope,warehouse,state);
            requirePrerequisite(state.origin(),command.operation());
            if(isConfirmation(command.operation())&&!Objects.equals(reviewed,state.options().get(command.optionId())))throw new IllegalStateException("Reviewed option changed.");
            body.set(Objects.requireNonNull(payloads.build(scope,warehouse,state,command),"Server payload is required"));
            Origin begun=begin(state.origin(),command.requestId(),command.operation(),command.childId(),command.optionId(),command.paidConfirmed(),clock.instant());
            return new Checkpoint(begun,state.acknowledgements(),state.options());
        });
        try {
            JsonNode response=adapter.invoke(scope.tenant(),scope.connection(),command.operation(),pending.checkpoint().origin().amazonPlanId(),command.optionId(),body.get(),command.paidConfirmed());
            String operation=required(response.path("operationId").asText());
            String plan=command.operation()==CREATE?required(response.path("inboundPlanId").asText()):pending.checkpoint().origin().amazonPlanId();
            Snapshot acknowledged=checkpoints.mutate(scope,warehouse,-1,audit(command.requestId(),"INBOUND_ACK",actor,Map.of("operationId",operation)),state->{
                requireScope(scope,warehouse,state);var ack=new LinkedHashMap<>(state.acknowledgements());ack.put(command.requestId(),new Acknowledgement(operation,plan));
                Origin origin=reconcile(state.origin(),command.requestId(),Status.PENDING,operation,null,List.of());
                return new Checkpoint(origin,ack,state.options());
            });
            return new Result(acknowledged,"Amazon operation accepted; awaiting verified completion.",true);
        }catch(RuntimeException failure){
            // Includes response parsing or ACK persistence failure: never retry the write automatically.
            return new Result(checkpoints.read(scope,warehouse),"Amazon outcome is uncertain. Reconcile before retry; no automatic repeat was sent.",true);
        }
    }
    public Result reconcileOperation(Scope scope,UUID warehouse,String actor,UUID request){
        enabled();Snapshot before=checkpoints.read(scope,warehouse);requireScope(scope,warehouse,before.checkpoint());
        Attempt attempt=before.checkpoint().origin().attempts().get(request);
        if(attempt==null)throw new IllegalArgumentException("Unknown pending request.");
        if(attempt.status()!=Status.PENDING)return new Result(before,"Operation was already reconciled.",false);
        Acknowledgement ack=before.checkpoint().acknowledgements().get(request);
        if(ack==null||ack.operationId()==null)throw new IllegalStateException("Response identity was not received. Investigate the ambiguous Amazon request before retry; never create a replacement blindly.");
        JsonNode remote=adapter.invoke(scope.tenant(),scope.connection(),STATUS,ack.operationId(),null,null,false);
        if(!ack.operationId().equals(required(remote.path("operationId").asText())))throw new IllegalStateException("Amazon status identity does not match this request.");
        Status status=switch(remote.path("operationStatus").asText()){case "SUCCESS"->Status.SUCCEEDED;case "FAILED"->Status.FAILED;case "IN_PROGRESS"->Status.PENDING;default->throw new IllegalStateException("Unrecognized Amazon operation status; retain pending state.");};
        List<Child> children=status==Status.SUCCEEDED&&attempt.operation()==CONFIRM_PLACEMENT?loadChildren(scope,warehouse,ack.planId()):List.of();
        Snapshot result=checkpoints.mutate(scope,warehouse,-1,audit(request,"INBOUND_STATUS_"+status,actor,Map.of("operationId",ack.operationId())),state->{
            requireScope(scope,warehouse,state);Origin origin=reconcile(state.origin(),request,status,ack.operationId(),status==Status.SUCCEEDED&&attempt.operation()==CREATE?ack.planId():null,children);
            return new Checkpoint(origin,state.acknowledgements(),state.options());
        });
        return new Result(result,status==Status.SUCCEEDED?"Amazon operation completed.":status==Status.FAILED?"Amazon rejected this operation. Review its result before a new attempt.":"Amazon operation remains in progress.",status==Status.PENDING);
    }
    public Snapshot refreshOptions(Scope scope,UUID warehouse,String actor,long revision,FbaInboundAdapter.Read kind){
        enabled();Snapshot before=checkpoints.read(scope,warehouse);requireScope(scope,warehouse,before.checkpoint());
        Map<String,Option> fetched=loadOptions(scope,before.checkpoint(),kind);
        return checkpoints.mutate(scope,warehouse,revision,audit(UUID.randomUUID(),"INBOUND_OPTIONS",actor,Map.of("kind",kind.name())),state->{
            requireScope(scope,warehouse,state);if(state.origin().packingVersion()!=before.checkpoint().origin().packingVersion())throw new IllegalStateException("Packing changed during option retrieval.");
            var options=new LinkedHashMap<>(state.options());options.values().removeIf(o->o.confirmation()==confirmation(kind));options.putAll(fetched);
            Origin origin=state.origin();for(Option option:fetched.values())if(option.confirmation()!=CONFIRM_PACKING&&option.expiresAt()!=null&&option.charge()!=null)
                origin=quote(origin,new Quote(option.id(),option.confirmation(),option.packingVersion(),option.expiresAt(),option.charge(),option.currency()));
            return new Checkpoint(origin,state.acknowledgements(),options);
        });
    }
    private Map<String,Option> loadOptions(Scope scope,Checkpoint state,FbaInboundAdapter.Read kind){
        FbaInboundAdapter.Operation confirmation=confirmation(kind);String field=kind==FbaInboundAdapter.Read.PACKING_OPTIONS?"packingOptions":kind==FbaInboundAdapter.Read.PLACEMENT_OPTIONS?"placementOptions":"transportationOptions";
        String identity=kind==FbaInboundAdapter.Read.PACKING_OPTIONS?"packingOptionId":kind==FbaInboundAdapter.Read.PLACEMENT_OPTIONS?"placementOptionId":"transportationOptionId";
        var result=new LinkedHashMap<String,Option>();
        for(JsonNode page:pages(scope,kind,state.origin().amazonPlanId(),null,kind==FbaInboundAdapter.Read.TRANSPORT_OPTIONS?selectedPlacement(state.origin()):null))for(JsonNode option:page.path(field)){
            String status=option.path("status").asText();if(!status.isEmpty()&&!Set.of("OFFERED","ACCEPTED").contains(status))continue;
            String id=required(option.path(identity).asText());String expiration=option.path("expiration").asText();BigDecimal charge=null;String currency=null;
            if(kind==FbaInboundAdapter.Read.TRANSPORT_OPTIONS){expiration=option.path("quote").path("expiration").asText();JsonNode cost=option.path("quote").path("cost");if(cost.has("amount")){charge=amount(cost.path("amount"));currency=required(cost.path("code").asText());}}
            else {if(!option.path("fees").isArray())throw new IllegalStateException("Amazon fee details are missing; do not assume zero.");charge=BigDecimal.ZERO;for(JsonNode fee:option.path("fees")){JsonNode value=fee.path("value");String code=required(value.path("code").asText());if(currency!=null&&!currency.equals(code))throw new IllegalStateException("Mixed option currencies require review.");currency=code;charge=charge.add(amount(value.path("amount")));}}
            var ids=new ArrayList<String>();for(JsonNode child:option.path("shipmentIds"))ids.add(required(child.asText()));
            String source=json.writeValueAsString(option);
            if(kind==FbaInboundAdapter.Read.PACKING_OPTIONS){
                var groups=new TreeMap<String,Map<String,Integer>>();
                for(JsonNode group:option.path("packingGroups")){
                    String groupId=required(group.asText());var units=new TreeMap<String,Integer>();
                    for(JsonNode groupPage:pages(scope,FbaInboundAdapter.Read.PACKING_GROUP_ITEMS,state.origin().amazonPlanId(),groupId))for(JsonNode item:groupPage.path("items")){
                        if(!item.path("quantity").isIntegralNumber()||!item.path("quantity").canConvertToInt()||item.path("quantity").intValue()<1)throw new IllegalStateException("Packing group requires positive whole SKU quantities.");
                        units.merge(required(item.path("msku").asText()),item.path("quantity").intValue(),Math::addExact);
                    }
                    if(groups.putIfAbsent(groupId,units)!=null)throw new IllegalStateException("Duplicate Amazon packing group.");
                }
                if(!groups.isEmpty()){
                    var totals=new TreeMap<String,Integer>();groups.values().forEach(units->units.forEach((sku,n)->totals.merge(sku,n,Math::addExact)));
                    if(!totals.equals(payloads.expectedSkuUnits(scope,state.origin().warehouse())))throw new IllegalStateException("Packing group contents differ from confirmed origin quantities.");
                    var enriched=json.convertValue(option,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});enriched.put("verifiedPackingGroupItems",groups);source=json.writeValueAsString(enriched);
                }
            }
            result.put(id,new Option(id,confirmation,state.origin().packingVersion(),expiration.isBlank()?null:Instant.parse(expiration),charge,currency,List.copyOf(ids),option.path("shipmentId").asText(null),source));
        }
        return result;
    }
    private List<Child> loadChildren(Scope scope,UUID warehouse,String plan){
        JsonNode inbound=adapter.read(scope.tenant(),scope.connection(),FbaInboundAdapter.Read.PLAN,plan,null,null);
        if(!plan.equals(required(inbound.path("inboundPlanId").asText())))throw new IllegalStateException("Inbound plan identity mismatch.");
        var children=new ArrayList<Child>();var totals=new TreeMap<String,Integer>();
        for(JsonNode summary:inbound.path("shipments")){
            String id=required(summary.path("shipmentId").asText());var units=new TreeMap<String,Integer>();
            for(JsonNode page:pages(scope,FbaInboundAdapter.Read.SHIPMENT_ITEMS,plan,id))for(JsonNode item:page.path("items")){
                String sku=required(item.path("msku").asText());if(!item.path("quantity").isIntegralNumber()||!item.path("quantity").canConvertToInt()||item.path("quantity").intValue()<1)throw new IllegalStateException("Amazon child quantities are not positive whole SKU units.");
                units.merge(sku,item.path("quantity").intValue(),Math::addExact);
            }
            JsonNode detail=adapter.read(scope.tenant(),scope.connection(),FbaInboundAdapter.Read.SHIPMENT,plan,id,null);if(!id.equals(required(detail.path("shipmentId").asText())))throw new IllegalStateException("Amazon shipment reference does not belong to this child.");String confirmation=detail.path("shipmentConfirmationId").asText(null);Child child=new Child(id,units,confirmation==null||confirmation.isBlank()?null:confirmation);children.add(child);units.forEach((sku,n)->totals.merge(sku,n,Math::addExact));
        }
        if(children.isEmpty()||!totals.equals(payloads.expectedSkuUnits(scope,warehouse)))throw new IllegalStateException("Amazon split contents differ from this origin's confirmed allocation. Reconcile before dispatch.");
        return children;
    }
    /** Read-only genuine label retrieval, saved through existing document storage; no local fabricated PDF. */
    public String saveLabels(Scope scope,UUID warehouse,String actor,long revision,String child,FbaInboundAdapter.LabelPage page){
        return saveLabels(scope,warehouse,actor,revision,child,page,DocumentKind.SHIPPING_LABEL);
    }
    public String saveLabels(Scope scope,UUID warehouse,String actor,long revision,String child,FbaInboundAdapter.LabelPage page,DocumentKind kind){
        enabled();Objects.requireNonNull(kind);
        if(kind==DocumentKind.ITEM_LABEL)throw new IllegalStateException("Amazon item-label document retrieval is unavailable through this adapter. Use Seller Central; no local issued label was fabricated.");
        Snapshot before=checkpoints.read(scope,warehouse);requireScope(scope,warehouse,before.checkpoint());Origin origin=before.checkpoint().origin();
        if(!origin.children().containsKey(child))throw new IllegalArgumentException("Shipment is outside this origin.");
        UUID confirmation=documentConfirmation(before.checkpoint(),child,kind);
        if(kind==DocumentKind.BILL_OF_LADING){
            Attempt attempt=origin.attempts().get(confirmation);Option option=before.checkpoint().options().get(attempt.optionId());
            JsonNode source=json.readTree(option.source());
            if(!"AMAZON_PARTNERED_CARRIER".equals(source.path("shippingSolution").asText())||!Set.of("FREIGHT_LTL","FREIGHT_FTL_PALLET","FREIGHT_FTL_NONPALLET").contains(source.path("shippingMode").asText()))
                throw new IllegalStateException("Amazon bills of lading are available only for confirmed Amazon-partnered LTL/FTL shipments.");
        }
        JsonNode shipment=adapter.read(scope.tenant(),scope.connection(),FbaInboundAdapter.Read.SHIPMENT,origin.amazonPlanId(),child,null);
        if(!child.equals(required(shipment.path("shipmentId").asText())))throw new IllegalStateException("Shipment identity mismatch.");
        String shipmentStatus=shipment.path("status").asText();
        if(Set.of("CANCELLED","DELETED","ABANDONED","UNCONFIRMED").contains(shipmentStatus))throw new IllegalStateException("Shipment is cancelled or not confirmed; no current document was retrieved.");
        String confirmationId=required(shipment.path("shipmentConfirmationId").asText());JsonNode document;
        if(kind==DocumentKind.BILL_OF_LADING)document=adapter.billOfLading(scope.tenant(),scope.connection(),confirmationId);
        else{
            var boxes=new ArrayList<String>();for(JsonNode boxPage:pages(scope,FbaInboundAdapter.Read.SHIPMENT_BOXES,origin.amazonPlanId(),child))for(JsonNode box:boxPage.path("boxes"))boxes.add(required(box.path("boxId").asText()));
            document=adapter.labels(scope.tenant(),scope.connection(),confirmationId,page,boxes);
        }
        URI url=URI.create(required(document.path("payload").path("DownloadURL").asText()));if(!"https".equals(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null)throw new IllegalStateException("Invalid Amazon document response.");
        String stored=required(documents.ingest(scope,warehouse,child,origin.packingVersion(),url,kind));
        checkpoints.mutate(scope,warehouse,revision,audit(UUID.randomUUID(),"INBOUND_DOCUMENT",actor,Map.of("child",child,"document",stored,"kind",kind.name())),state->{
            if(state.origin().packingVersion()!=origin.packingVersion()||!confirmation.equals(documentConfirmation(state,child,kind)))throw new IllegalStateException("Packing or confirmation changed while obtaining the document; it is stale.");
            return new Checkpoint(issuedDocument(state.origin(),confirmation,child,stored,kind),state.acknowledgements(),state.options());
        });return stored;
    }
    static UUID documentConfirmation(Checkpoint state,String child,DocumentKind kind){
        Origin origin=state.origin();
        if(origin.attempts().values().stream().anyMatch(a->a.operation()==CANCEL&&a.status()==Status.SUCCEEDED&&(a.childId()==null||child.equals(a.childId()))))throw new IllegalStateException("Cancelled shipment documents are stale.");
        return origin.attempts().entrySet().stream().filter(e->{Attempt a=e.getValue();if(a.status()!=Status.SUCCEEDED||a.packingVersion()!=origin.packingVersion()||a.optionId()==null)return false;
            Option option=state.options().get(a.optionId());if(option==null||option.packingVersion()!=origin.packingVersion())return false;
            return kind==DocumentKind.CARTON_LABEL?a.operation()==CONFIRM_PLACEMENT&&option.children().contains(child):a.operation()==CONFIRM_TRANSPORT&&child.equals(a.childId())&&child.equals(option.childId());
        }).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalStateException(kind==DocumentKind.CARTON_LABEL?"Confirm this child's current placement before requesting carton labels.":"Confirm this child's current transportation before requesting shipping documents."));
    }
    private static String selectedPlacement(Origin origin){
        List<String> selected=origin.attempts().values().stream().filter(a->a.operation()==CONFIRM_PLACEMENT&&a.status()==Status.SUCCEEDED&&a.packingVersion()==origin.packingVersion()).map(Attempt::optionId).distinct().toList();
        if(selected.size()!=1)throw new IllegalStateException("A single verified current placement confirmation is required before transportation options.");return required(selected.getFirst());
    }
    private List<JsonNode> pages(Scope scope,FbaInboundAdapter.Read kind,String plan,String child){return pages(scope,kind,plan,child,null);}
    private List<JsonNode> pages(Scope scope,FbaInboundAdapter.Read kind,String plan,String child,String placement){
        var result=new ArrayList<JsonNode>();var tokens=new HashSet<String>();String token=null;
        do{JsonNode page=placement==null?adapter.read(scope.tenant(),scope.connection(),kind,plan,child,token):adapter.read(scope.tenant(),scope.connection(),kind,plan,child,token,placement);result.add(page);token=page.path("pagination").path("nextToken").asText(null);
            if(token!=null&&!token.isBlank()&&(!tokens.add(token)||result.size()>=100))throw new IllegalStateException("Amazon pagination is incomplete; retain prior checkpoint.");
        }while(token!=null&&!token.isBlank());return result;
    }
    private static void requirePrerequisite(Origin origin,FbaInboundAdapter.Operation operation){
        FbaInboundAdapter.Operation prior=switch(operation){case CREATE,CANCEL,STATUS->null;case PACKING_OPTIONS->CREATE;case CONFIRM_PACKING->PACKING_OPTIONS;case PACKING_INFORMATION->CONFIRM_PACKING;case PLACEMENT_OPTIONS->PACKING_INFORMATION;case CONFIRM_PLACEMENT->PLACEMENT_OPTIONS;case TRANSPORT_OPTIONS->CONFIRM_PLACEMENT;case CONFIRM_TRANSPORT->TRANSPORT_OPTIONS;};
        if(prior!=null&&origin.attempts().values().stream().noneMatch(a->a.operation()==prior&&a.status()==Status.SUCCEEDED&&(prior==CREATE||a.packingVersion()==origin.packingVersion())))throw new IllegalStateException("Complete the preceding Amazon workflow stage first.");
    }
    private static BigDecimal amount(JsonNode value){if(!value.isNumber()||value.decimalValue().signum()<0)throw new IllegalStateException("Amazon cost amount is missing or invalid; review before confirming.");return value.decimalValue();}
    private static boolean sameOffer(Option old,Option fresh){return fresh!=null&&old.id().equals(fresh.id())&&old.confirmation()==fresh.confirmation()&&old.packingVersion()==fresh.packingVersion()&&Objects.equals(old.expiresAt(),fresh.expiresAt())&&Objects.equals(old.charge(),fresh.charge())&&Objects.equals(old.currency(),fresh.currency())&&old.children().equals(fresh.children())&&Objects.equals(old.childId(),fresh.childId())&&Objects.equals(old.source(),fresh.source());}
    private static boolean isConfirmation(FbaInboundAdapter.Operation operation){return operation==CONFIRM_PACKING||operation==CONFIRM_PLACEMENT||operation==CONFIRM_TRANSPORT;}
    private static FbaInboundAdapter.Read kind(FbaInboundAdapter.Operation operation){return operation==CONFIRM_PACKING?FbaInboundAdapter.Read.PACKING_OPTIONS:operation==CONFIRM_PLACEMENT?FbaInboundAdapter.Read.PLACEMENT_OPTIONS:FbaInboundAdapter.Read.TRANSPORT_OPTIONS;}
    private static FbaInboundAdapter.Operation confirmation(FbaInboundAdapter.Read kind){return switch(kind){case PACKING_OPTIONS->CONFIRM_PACKING;case PLACEMENT_OPTIONS->CONFIRM_PLACEMENT;case TRANSPORT_OPTIONS->CONFIRM_TRANSPORT;default->throw new IllegalArgumentException("Choose packing, placement or transportation options.");};}
    private static Audit audit(UUID request,String kind,String actor,Object details){return new Audit(UUID.nameUUIDFromBytes((request+":"+kind).getBytes(StandardCharsets.UTF_8)),kind,actor,details);}
    private static void requireScope(Scope scope,UUID warehouse,Checkpoint checkpoint){if(!scope.equals(checkpoint.origin().scope())||!warehouse.equals(checkpoint.origin().warehouse()))throw new IllegalStateException("Inbound checkpoint belongs to another account, store or origin.");}
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalStateException("Required verified Amazon identity or metadata is missing.");return value;}
}
