package com.nextaicommerce.platform.fba;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Pure checkpoint transitions. No Amazon, inventory, charge or document-generation side effects.
 * Persist each returned checkpoint under the plan revision lock BEFORE invoking an adapter.
 */
public final class FbaInboundWorkflow {
    private FbaInboundWorkflow() {}
    public enum Status { PENDING, SUCCEEDED, FAILED }
    public enum DocumentKind { ITEM_LABEL, CARTON_LABEL, SHIPPING_LABEL, BILL_OF_LADING }
    public record Scope(UUID tenant, UUID account, UUID connection, UUID replenishment) implements Serializable {
        public Scope { Objects.requireNonNull(tenant); Objects.requireNonNull(account); Objects.requireNonNull(connection); Objects.requireNonNull(replenishment); }
    }
    public record Quote(String optionId, FbaInboundAdapter.Operation confirmation, long packingVersion,
                        Instant expiresAt, BigDecimal charge, String currency) implements Serializable {
        public Quote {
            optionId = required(optionId); Objects.requireNonNull(expiresAt); Objects.requireNonNull(charge);
            if (confirmation != FbaInboundAdapter.Operation.CONFIRM_PLACEMENT && confirmation != FbaInboundAdapter.Operation.CONFIRM_TRANSPORT)
                throw new IllegalArgumentException("Quote must identify placement or transport confirmation.");
            if (packingVersion < 1 || charge.signum() < 0) throw new IllegalArgumentException("Invalid quote version or charge.");
            if (charge.signum()>0) currency = required(currency);
        }
    }
    public record Attempt(UUID requestId, FbaInboundAdapter.Operation operation, String childId,
                          long packingVersion, String optionId, Status status, String amazonOperationId) implements Serializable {
        public Attempt { Objects.requireNonNull(requestId); Objects.requireNonNull(operation); Objects.requireNonNull(status); }
    }
    public record Child(String shipmentId, Map<String,Integer> skuUnits,String confirmationId) implements Serializable {
        public Child(String shipmentId,Map<String,Integer> skuUnits){this(shipmentId,skuUnits,null);}
        public Child { shipmentId = required(shipmentId); skuUnits = quantities(skuUnits); if(confirmationId!=null)confirmationId=required(confirmationId); }
    }
    /** Only populate from a verified successful Amazon document response, never a local preview. */
    public record IssuedDocument(String amazonDocumentId, DocumentKind kind, String childId,
                                 long packingVersion, String amazonOperationId) implements Serializable {
        public IssuedDocument { amazonDocumentId=required(amazonDocumentId); Objects.requireNonNull(kind); childId=required(childId); amazonOperationId=required(amazonOperationId); }
    }
    public record Origin(Scope scope, UUID warehouse, long packingVersion, String amazonPlanId,
                         Map<UUID,Attempt> attempts, Map<String,Child> children,
                         Map<String,Quote> quotes, List<IssuedDocument> documents) implements Serializable {
        public Origin {
            Objects.requireNonNull(scope); Objects.requireNonNull(warehouse);
            if (packingVersion < 1) throw new IllegalArgumentException("Packing version must be positive.");
            attempts=Map.copyOf(attempts); children=Map.copyOf(children); quotes=Map.copyOf(quotes); documents=List.copyOf(documents);
        }
    }
    /** Exact identity for the later atomic inventory command; this method never deducts stock. */
    public record Dispatch(Scope scope, UUID warehouse, String shipmentId, long packingVersion,
                           Map<String,Integer> skuUnits) implements Serializable {
        public Dispatch { skuUnits=quantities(skuUnits); }
    }
    public static Origin start(Scope scope, UUID warehouse) { return new Origin(scope,warehouse,1,null,Map.of(),Map.of(),Map.of(),List.of()); }
    public static Origin packingChanged(Origin old) {
        requireSettled(old);
        if (!old.children().isEmpty()) throw new IllegalStateException("Created shipments require an explicit Amazon-supported edit/cancel workflow; preserve their contents.");
        return new Origin(old.scope(),old.warehouse(),Math.addExact(old.packingVersion(),1),old.amazonPlanId(),old.attempts(),old.children(),Map.of(),List.of());
    }
    public static Origin quote(Origin old, Quote quote) {
        if(quote.packingVersion()!=old.packingVersion()) throw new IllegalStateException("Quote belongs to stale packing.");
        var quotes=new LinkedHashMap<>(old.quotes()); quotes.put(quote.optionId(),quote);
        return copy(old,old.amazonPlanId(),old.attempts(),old.children(),quotes,old.documents());
    }
    public static Origin begin(Origin old, UUID request, FbaInboundAdapter.Operation operation,
                               String child, String option, boolean explicitPaidConfirmation, Instant now) {
        Objects.requireNonNull(now); Objects.requireNonNull(operation); Objects.requireNonNull(request);
        requireSettled(old);
        if (cancelled(old,child)) throw new IllegalStateException("This origin or child was cancelled; reconcile its inventory separately.");
        if(old.attempts().containsKey(request)) throw new IllegalStateException("Request identity already recorded; reconcile or use a new reviewed attempt.");
        if(operation==FbaInboundAdapter.Operation.STATUS) throw new IllegalArgumentException("Status reads reconcile an existing attempt.");
        if(operation==FbaInboundAdapter.Operation.CREATE) {
            if(old.amazonPlanId()!=null || old.attempts().values().stream().anyMatch(a->a.operation()==operation&&a.status()==Status.SUCCEEDED))
                throw new IllegalStateException("This origin already has an Amazon plan.");
        } else if(old.amazonPlanId()==null) throw new IllegalStateException("A verified Amazon plan is required.");
        if(child!=null && !old.children().containsKey(child)) throw new IllegalArgumentException("Shipment is outside this origin.");
        if(operation==FbaInboundAdapter.Operation.CONFIRM_PACKING && old.attempts().values().stream().anyMatch(a->a.status()==Status.SUCCEEDED&&a.operation()==operation&&Objects.equals(a.optionId(),option)&&a.packingVersion()==old.packingVersion()))
            throw new IllegalStateException("This packing option was already confirmed.");
        if(operation==FbaInboundAdapter.Operation.CONFIRM_PLACEMENT || operation==FbaInboundAdapter.Operation.CONFIRM_TRANSPORT) {
            Quote q=old.quotes().get(option);
            if(q==null || q.confirmation()!=operation || q.packingVersion()!=old.packingVersion() || !now.isBefore(q.expiresAt()))
                throw new IllegalStateException("Refresh the expired or changed option and review it again.");
            if(!explicitPaidConfirmation) throw new IllegalStateException("Explicit confirmation of the current charge and option is required.");
            if(old.attempts().values().stream().anyMatch(a->a.status()==Status.SUCCEEDED&&a.operation()==operation&&Objects.equals(a.optionId(),option)&&Objects.equals(a.childId(),child)&&a.packingVersion()==old.packingVersion()))
                throw new IllegalStateException("This option was already confirmed.");
        }
        var attempts=new LinkedHashMap<>(old.attempts());
        attempts.put(request,new Attempt(request,operation,child,old.packingVersion(),option,Status.PENDING,null));
        return copy(old,old.amazonPlanId(),attempts,old.children(),old.quotes(),old.documents());
    }
    /** A timeout/transport exception is NOT failure: leave PENDING until this verified status read. */
    public static Origin reconcile(Origin old, UUID request, Status status, String amazonOperationId, String createdPlanId, List<Child> issuedChildren) {
        Attempt attempt=old.attempts().get(request);
        if(attempt==null || attempt.status()!=Status.PENDING) throw new IllegalStateException("Only a pending attempt can be reconciled.");
        String remote=required(amazonOperationId);
        if(attempt.amazonOperationId()!=null&&!attempt.amazonOperationId().equals(remote)) throw new IllegalStateException("Amazon operation identity changed.");
        Objects.requireNonNull(status); Objects.requireNonNull(issuedChildren);
        if(status!=Status.SUCCEEDED && (createdPlanId!=null||!issuedChildren.isEmpty())) throw new IllegalArgumentException("Only successful results issue plan/shipment identities.");
        String plan=old.amazonPlanId();
        if(status==Status.SUCCEEDED && attempt.operation()==FbaInboundAdapter.Operation.CREATE) plan=required(createdPlanId);
        else if(createdPlanId!=null) throw new IllegalArgumentException("Only CREATE establishes the plan identity.");
        var children=new LinkedHashMap<>(old.children());
        for(Child c:issuedChildren) {
            Child existing=children.putIfAbsent(c.shipmentId(),c);
            if(existing!=null&&!existing.equals(c)) throw new IllegalStateException("Successful child contents cannot be overwritten.");
        }
        var attempts=new LinkedHashMap<>(old.attempts()); attempts.put(request,new Attempt(request,attempt.operation(),attempt.childId(),attempt.packingVersion(),attempt.optionId(),status,remote));
        return copy(old,plan,attempts,children,old.quotes(),old.documents());
    }
    public static Origin issuedDocument(Origin old, UUID successfulRequest, String child, String documentId, DocumentKind kind) {
        Attempt attempt=old.attempts().get(successfulRequest);
        if(attempt==null||attempt.status()!=Status.SUCCEEDED||attempt.amazonOperationId()==null||attempt.packingVersion()!=old.packingVersion()||(!old.children().containsKey(child)||attempt.childId()!=null&&!attempt.childId().equals(child)))
            throw new IllegalStateException("A verified successful current Amazon response and child are required; local previews are not issued documents.");
        var document=new IssuedDocument(documentId,kind,child,old.packingVersion(),attempt.amazonOperationId());
        var documents=new ArrayList<>(old.documents()); if(!documents.contains(document)) documents.add(document);
        return copy(old,old.amazonPlanId(),old.attempts(),old.children(),old.quotes(),documents);
    }
    public static Dispatch dispatch(Origin old,String child,Map<String,Integer> exactUnits) {
        requireSettled(old);
        if(cancelled(old,child)) throw new IllegalStateException("Cancelled shipments cannot dispatch.");
        Child shipment=old.children().get(child);
        if(shipment==null||!shipment.skuUnits().equals(quantities(exactUnits))) throw new IllegalStateException("Dispatch must match exact issued child SKU quantities.");
        return new Dispatch(old.scope(),old.warehouse(),child,old.packingVersion(),shipment.skuUnits());
    }
    private static boolean cancelled(Origin old,String child) {
        return old.attempts().values().stream().anyMatch(a->a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==Status.SUCCEEDED&&(a.childId()==null||Objects.equals(a.childId(),child)));
    }
    private static void requireSettled(Origin old) {
        if(old.attempts().values().stream().anyMatch(a->a.status()==Status.PENDING)) throw new IllegalStateException("Amazon outcome is pending or ambiguous. Reconcile before retry or editing.");
    }
    private static Origin copy(Origin o,String plan,Map<UUID,Attempt> attempts,Map<String,Child> children,Map<String,Quote> quotes,List<IssuedDocument> documents) {
        return new Origin(o.scope(),o.warehouse(),o.packingVersion(),plan,attempts,children,quotes,documents);
    }
    private static String required(String value) { if(value==null||value.isBlank()) throw new IllegalArgumentException("Verified identity is required."); return value; }
    private static Map<String,Integer> quantities(Map<String,Integer> input) {
        if(input==null||input.isEmpty()) throw new IllegalArgumentException("Exact SKU quantities are required.");
        var result=new TreeMap<String,Integer>(); input.forEach((sku,n)->{required(sku);if(n==null||n<1)throw new IllegalArgumentException("Positive whole SKU units are required.");result.put(sku,n);});return Collections.unmodifiableMap(result);
    }
}
