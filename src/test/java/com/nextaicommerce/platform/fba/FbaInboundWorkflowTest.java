package com.nextaicommerce.platform.fba;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.nextaicommerce.platform.fba.FbaInboundAdapter.Operation.*;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

class FbaInboundWorkflowTest {
    private final Instant now=Instant.parse("2026-11-18T00:00:00Z");
    private Origin initial() { return start(new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),UUID.randomUUID()); }
    private Origin created() {
        var request=UUID.randomUUID();var o=begin(initial(),request,CREATE,null,null,false,now);
        return reconcile(o,request,Status.SUCCEEDED,"amazon-create-operation","amazon-plan",List.of());
    }
    private Quote transport(Origin o) { return new Quote("transport-option",CONFIRM_TRANSPORT,o.packingVersion(),now.plusSeconds(60),new BigDecimal("12.34"),"USD"); }
    @Test void timeoutCannotBeRetriedOrEditedBeforeReconciliation() {
        var o=begin(initial(),UUID.randomUUID(),CREATE,null,null,false,now);
        assertThrows(IllegalStateException.class,()->begin(o,UUID.randomUUID(),CREATE,null,null,false,now));
        assertThrows(IllegalStateException.class,()->packingChanged(o));
        var request=o.attempts().keySet().iterator().next();
        var stillPending=reconcile(o,request,Status.PENDING,"amazon-op",null,List.of());
        assertThrows(IllegalStateException.class,()->begin(stillPending,UUID.randomUUID(),CREATE,null,null,false,now));
        var failed=reconcile(stillPending,request,Status.FAILED,"amazon-op",null,List.of());
        assertEquals(Status.PENDING,begin(failed,UUID.randomUUID(),CREATE,null,null,false,now).attempts().values().stream().filter(a->a.status()==Status.PENDING).findFirst().orElseThrow().status());
    }
    @Test void packingRevisionInvalidatesOptionsAndCannotSilentlyRewriteCreatedChildren() {
        var old=created();old=quote(old,transport(old));var changed=packingChanged(old);
        assertEquals(2,changed.packingVersion());assertTrue(changed.quotes().isEmpty());assertTrue(changed.documents().isEmpty());
        assertThrows(IllegalStateException.class,()->quote(changed,transport(created())));
        var withChildren=children();assertThrows(IllegalStateException.class,()->packingChanged(withChildren));
    }
    @Test void paymentRequiresCurrentQuoteExplicitConfirmationAndRejectsDuplicateCharge() {
        var o=created();o=quote(o,transport(o));final var reviewed=o;
        assertThrows(IllegalStateException.class,()->begin(reviewed,UUID.randomUUID(),CONFIRM_TRANSPORT,null,"transport-option",false,now));
        assertThrows(IllegalStateException.class,()->begin(reviewed,UUID.randomUUID(),CONFIRM_TRANSPORT,null,"transport-option",true,now.plusSeconds(60)));
        assertThrows(IllegalStateException.class,()->begin(reviewed,UUID.randomUUID(),CONFIRM_PLACEMENT,null,"transport-option",true,now));
        var request=UUID.randomUUID();var pending=begin(o,request,CONFIRM_TRANSPORT,null,"transport-option",true,now);
        var done=reconcile(pending,request,Status.SUCCEEDED,"amazon-paid-op",null,List.of());
        assertThrows(IllegalStateException.class,()->begin(done,UUID.randomUUID(),CONFIRM_TRANSPORT,null,"transport-option",true,now));
    }
    private Origin children() {
        var request=UUID.randomUUID();var o=begin(created(),request,PLACEMENT_OPTIONS,null,null,false,now);
        return reconcile(o,request,Status.SUCCEEDED,"amazon-placement-op",null,List.of(new Child("child-A",Map.of("sku-one",4)),new Child("child-B",Map.of("sku-two",3))));
    }
    @Test void failedChildPreservesSuccessfulSiblingsAndExactDispatchIdentity() {
        var o=children();var request=UUID.randomUUID();
        var pending=begin(o,request,TRANSPORT_OPTIONS,"child-B",null,false,now);
        var failed=reconcile(pending,request,Status.FAILED,"amazon-child-B-op",null,List.of());
        assertEquals(o.children(),failed.children());
        assertEquals(new Dispatch(o.scope(),o.warehouse(),"child-A",o.packingVersion(),Map.of("sku-one",4)),dispatch(failed,"child-A",Map.of("sku-one",4)));
        assertThrows(IllegalStateException.class,()->dispatch(failed,"child-A",Map.of("sku-one",3)));
        assertThrows(IllegalStateException.class,()->dispatch(failed,"child-A",Map.of("sku-two",4)));
        assertThrows(IllegalStateException.class,()->dispatch(failed,"foreign-child",Map.of("sku-one",4)));
        assertThrows(UnsupportedOperationException.class,()->failed.children().clear());
    }
    @Test void cancellationBlocksOnlyItsChildAndNeverRestoresStock() {
        var o=children();var request=UUID.randomUUID();
        var pending=begin(o,request,CANCEL,"child-B",null,false,now);
        var cancelled=reconcile(pending,request,Status.SUCCEEDED,"amazon-cancel-op",null,List.of());
        assertEquals(o.children(),cancelled.children());
        assertThrows(IllegalStateException.class,()->dispatch(cancelled,"child-B",Map.of("sku-two",3)));
        assertEquals("child-A",dispatch(cancelled,"child-A",Map.of("sku-one",4)).shipmentId());
    }
    @Test void documentsRequireVerifiedRemoteSuccessAndNeverInventIssuedIdentity() {
        var o=children();var request=UUID.randomUUID();
        var pending=begin(o,request,PACKING_INFORMATION,"child-A",null,false,now);
        assertThrows(IllegalStateException.class,()->issuedDocument(pending,request,"child-A","label",DocumentKind.CARTON_LABEL));
        var done=reconcile(pending,request,Status.SUCCEEDED,"amazon-document-op",null,List.of());
        assertThrows(IllegalArgumentException.class,()->issuedDocument(done,request,"child-A","",DocumentKind.CARTON_LABEL));
        assertThrows(IllegalStateException.class,()->issuedDocument(done,request,"foreign-child","issued-label",DocumentKind.CARTON_LABEL));
        var withDoc=issuedDocument(done,request,"child-A","issued-label",DocumentKind.CARTON_LABEL);
        assertEquals(1,issuedDocument(withDoc,request,"child-A","issued-label",DocumentKind.CARTON_LABEL).documents().size());
        assertEquals("amazon-document-op",withDoc.documents().getFirst().amazonOperationId());
    }
    @Test void immutableCheckpointRoundTripsWithoutLosingTenantOriginOrChildren() throws Exception {
        var o=children();
        var json=new tools.jackson.databind.ObjectMapper();
        assertEquals(o,json.readValue(json.writeValueAsString(o),Origin.class));
        var bytes=new ByteArrayOutputStream();
        try(var output=new ObjectOutputStream(bytes)){output.writeObject(o);}
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){assertEquals(o,input.readObject());}
        assertThrows(IllegalArgumentException.class,()->new Child("bad",Map.of("sku",0)));
    }
}
