package com.nextaicommerce.platform.fba;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.nextaicommerce.platform.fba.FbaInboundAdapter.Operation.*;
import static com.nextaicommerce.platform.fba.FbaInboundOrchestration.*;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;

class FbaInboundOrchestrationTest {
    final ObjectMapper json=new ObjectMapper();final Instant now=Instant.parse("2026-11-18T00:00:00Z");
    final Scope scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());final UUID warehouse=UUID.randomUUID();
    final Memory store=new Memory();final FbaInboundAdapter adapter=mock(FbaInboundAdapter.class);
    final Payloads payloads=mock(Payloads.class);final Documents docs=mock(Documents.class);
    final AtomicInteger calls=new AtomicInteger();
    class Memory implements Checkpoints {
        Snapshot current=new Snapshot(0,Checkpoint.initial(scope,warehouse));final List<Audit> audits=new ArrayList<>();
        public Snapshot read(Scope s,UUID w){return current;}
        public Snapshot mutate(Scope s,UUID w,long revision,Audit audit,UnaryOperator<Checkpoint> transition){
            if(revision>=0&&revision!=current.revision())throw new IllegalStateException("Revision conflict");
            Checkpoint next=transition.apply(current.checkpoint());audits.add(audit);return current=new Snapshot(current.revision()+1,next);
        }
    }
    FbaInboundOrchestration service(){return new FbaInboundOrchestration(store,payloads,docs,adapter,json,Clock.fixed(now,ZoneOffset.UTC));}
    void fixture(){
        when(adapter.enabled()).thenReturn(true);when(payloads.build(any(),any(),any(),any())).thenReturn("{\"serverOwned\":true}");when(payloads.expectedSkuUnits(scope,warehouse)).thenReturn(Map.of("SKU",5));
        when(adapter.invoke(any(),any(),any(),any(),any(),any(),anyBoolean())).thenAnswer(inv->{
            var operation=inv.getArgument(2,FbaInboundAdapter.Operation.class);
            if(operation==STATUS)return json.readTree("{\"operationId\":\""+inv.getArgument(3)+"\",\"operationStatus\":\"SUCCESS\"}");
            assertTrue(store.current.checkpoint().origin().attempts().values().stream().anyMatch(a->a.status()==Status.PENDING),"pending checkpoint must commit before network");
            calls.incrementAndGet();return json.readTree("{\"operationId\":\"remote-"+calls.get()+"\",\"inboundPlanId\":\"plan\"}");
        });
        when(adapter.read(any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            assertEquals("placement",inv.getArgument(6));return adapter.read(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3),inv.getArgument(4),inv.getArgument(5));
        });
        when(adapter.read(any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            var read=inv.getArgument(2,FbaInboundAdapter.Read.class);String child=inv.getArgument(4);
            return json.readTree(switch(read){
                case PLAN->"{\"inboundPlanId\":\"plan\",\"shipments\":[{\"shipmentId\":\"A\"},{\"shipmentId\":\"B\"}]}";
                case PACKING_OPTIONS->"{\"packingOptions\":[{\"packingOptionId\":\"pack\",\"packingGroups\":[\"group\"],\"fees\":[],\"status\":\"OFFERED\"}]}";
                case PLACEMENT_OPTIONS->"{\"placementOptions\":[{\"placementOptionId\":\"placement\",\"expiration\":\"2026-11-19T00:00:00Z\",\"fees\":[{\"value\":{\"amount\":2,\"code\":\"USD\"}}],\"shipmentIds\":[\"A\",\"B\"],\"status\":\"OFFERED\"}]}";
                case TRANSPORT_OPTIONS->"{\"transportationOptions\":[{\"transportationOptionId\":\"transport-A\",\"shipmentId\":\"A\",\"quote\":{\"cost\":{\"amount\":12.34,\"code\":\"USD\"},\"expiration\":\"2026-11-19T00:00:00Z\"}}]}";
                case SHIPMENT_ITEMS->"{\"items\":[{\"msku\":\"SKU\",\"quantity\":"+("A".equals(child)?2:3)+"}]}";
                case SHIPMENT->"{\"shipmentId\":\""+child+"\",\"shipmentConfirmationId\":\""+("A".equals(child)?"FBA-REAL":"FBA-B")+"\"}";
                case PACKING_GROUP_ITEMS->"{\"items\":[{\"msku\":\"SKU\",\"quantity\":5}]}";
                case SHIPMENT_BOXES->"{\"boxes\":[{\"boxId\":\"box-A\"}]}";
            });
        });
    }
    UUID step(FbaInboundAdapter.Operation operation,String child,String option){
        UUID id=UUID.randomUUID();Result pending=service().execute(scope,warehouse,"owner",new Command(id,store.current.revision(),operation,child,option,true));
        assertTrue(pending.pending());assertNotNull(store.current.checkpoint().acknowledgements().get(id).operationId());
        assertFalse(service().reconcileOperation(scope,warehouse,"owner",id).pending());return id;
    }
    void throughPlacement(){fixture();step(CREATE,null,null);step(PACKING_OPTIONS,null,null);service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.PACKING_OPTIONS);step(CONFIRM_PACKING,null,"pack");step(PACKING_INFORMATION,null,null);step(PLACEMENT_OPTIONS,null,null);service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.PLACEMENT_OPTIONS);step(CONFIRM_PLACEMENT,null,"placement");}
    @Test void localGuardsStopBeforeAnyPersistencePayloadOrNetwork(){
        when(adapter.enabled()).thenReturn(false);when(adapter.unavailable()).thenReturn("Disabled locally");
        assertThrows(IllegalStateException.class,()->service().execute(scope,warehouse,"owner",new Command(UUID.randomUUID(),0,CREATE,null,null,false)));
        assertEquals(0,store.current.revision());verifyNoInteractions(payloads,docs);verify(adapter,never()).invoke(any(),any(),any(),any(),any(),any(),anyBoolean());
    }
    @Test void persistedPendingAndAckPrecedeVerifiedCompletionAndReplayNeverCreatesTwice(){
        fixture();UUID id=UUID.randomUUID();Command c=new Command(id,0,CREATE,null,null,false);
        var pending=service().execute(scope,warehouse,"owner",c);assertNull(pending.snapshot().checkpoint().origin().amazonPlanId());
        assertEquals("plan",pending.snapshot().checkpoint().acknowledgements().get(id).planId());assertEquals(1,calls.get());
        service().execute(scope,warehouse,"owner",c);assertEquals(1,calls.get());
        var complete=service().reconcileOperation(scope,warehouse,"owner",id);assertEquals("plan",complete.snapshot().checkpoint().origin().amazonPlanId());
        service().execute(scope,warehouse,"owner",c);assertEquals(1,calls.get());
        assertThrows(IllegalStateException.class,()->service().execute(scope,warehouse,"owner",new Command(id,0,PACKING_OPTIONS,null,null,false)));
    }
    @Test void missingAcknowledgementLeavesAmbiguousPendingAndCannotBlindRetry(){
        fixture();doThrow(new IllegalStateException("timeout")).when(adapter).invoke(any(),any(),eq(CREATE),any(),any(),any(),anyBoolean());
        UUID id=UUID.randomUUID();var result=service().execute(scope,warehouse,"owner",new Command(id,0,CREATE,null,null,false));assertTrue(result.pending());
        assertThrows(IllegalStateException.class,()->service().reconcileOperation(scope,warehouse,"owner",id));
        assertThrows(IllegalStateException.class,()->service().execute(scope,warehouse,"owner",new Command(UUID.randomUUID(),store.current.revision(),CREATE,null,null,false)));
        verify(adapter,times(1)).invoke(any(),any(),eq(CREATE),any(),any(),any(),anyBoolean());
    }
    @Test void realShapeFixturesCompleteStagesSplitChildrenAndPersistOnlyIssuedStoredLabels(){
        throughPlacement();assertEquals(Map.of("SKU",2),store.current.checkpoint().origin().children().get("A").skuUnits());assertEquals(Map.of("SKU",3),store.current.checkpoint().origin().children().get("B").skuUnits());
        step(TRANSPORT_OPTIONS,null,null);service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS);step(CONFIRM_TRANSPORT,"A","transport-A");
        when(adapter.labels(scope.tenant(),scope.connection(),"FBA-REAL",FbaInboundAdapter.LabelPage.PackageLabel_Letter_2,List.of("box-A"))).thenReturn(json.readTree("{\"payload\":{\"DownloadURL\":\"https://amazon-issued.example/short-lived-label\"}}"));
        when(docs.ingest(eq(scope),eq(warehouse),eq("A"),eq(1L),any(),eq(DocumentKind.SHIPPING_LABEL))).thenReturn("scoped-stored-real-label");
        assertEquals("scoped-stored-real-label",service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",FbaInboundAdapter.LabelPage.PackageLabel_Letter_2));
        assertEquals("scoped-stored-real-label",store.current.checkpoint().origin().documents().getFirst().amazonDocumentId());
        verify(docs).ingest(scope,warehouse,"A",1,URI.create("https://amazon-issued.example/short-lived-label"),DocumentKind.SHIPPING_LABEL);
        assertThrows(IllegalStateException.class,()->service().saveLabels(scope,warehouse,"owner",store.current.revision(),"B",FbaInboundAdapter.LabelPage.PackageLabel_Letter_2));
        assertThrows(IllegalStateException.class,()->service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",null,DocumentKind.BILL_OF_LADING));
        verify(adapter,never()).billOfLading(any(),any(),any());
    }
    @Test void cartonLabelsUseConfirmedPlacementBeforeTransportAndItemsAreExplicitlyUnsupported(){
        throughPlacement();
        when(adapter.labels(scope.tenant(),scope.connection(),"FBA-REAL",FbaInboundAdapter.LabelPage.PackageLabel_Letter_4,List.of("box-A"))).thenReturn(json.readTree("{\"payload\":{\"DownloadURL\":\"https://amazon-issued.example/cartons\"}}"));
        when(docs.ingest(eq(scope),eq(warehouse),eq("A"),eq(1L),any(),eq(DocumentKind.CARTON_LABEL))).thenReturn("stored-carton-label");
        assertEquals("stored-carton-label",service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",FbaInboundAdapter.LabelPage.PackageLabel_Letter_4,DocumentKind.CARTON_LABEL));
        assertEquals(DocumentKind.CARTON_LABEL,store.current.checkpoint().origin().documents().getFirst().kind());
        assertThrows(IllegalStateException.class,()->service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",FbaInboundAdapter.LabelPage.PackageLabel_Letter_4,DocumentKind.SHIPPING_LABEL));
        assertThrows(IllegalStateException.class,()->service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",FbaInboundAdapter.LabelPage.PackageLabel_Letter_4,DocumentKind.ITEM_LABEL));
    }
    @Test void billOfLadingRequiresActualPartneredFreightAndPersistsOnlyGenuineDocument(){
        throughPlacement();step(TRANSPORT_OPTIONS,null,null);
        doReturn(json.readTree("{\"transportationOptions\":[{\"transportationOptionId\":\"transport-A\",\"shipmentId\":\"A\",\"shippingSolution\":\"AMAZON_PARTNERED_CARRIER\",\"shippingMode\":\"FREIGHT_LTL\",\"quote\":{\"cost\":{\"amount\":12.34,\"code\":\"USD\"},\"expiration\":\"2026-11-19T00:00:00Z\"}}]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.TRANSPORT_OPTIONS),any(),any(),any());
        service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS);step(CONFIRM_TRANSPORT,"A","transport-A");
        when(adapter.billOfLading(scope.tenant(),scope.connection(),"FBA-REAL")).thenReturn(json.readTree("{\"payload\":{\"DownloadURL\":\"https://amazon-issued.example/bol\"}}"));
        when(docs.ingest(eq(scope),eq(warehouse),eq("A"),eq(1L),any(),eq(DocumentKind.BILL_OF_LADING))).thenReturn("stored-bol");
        assertEquals("stored-bol",service().saveLabels(scope,warehouse,"owner",store.current.revision(),"A",null,DocumentKind.BILL_OF_LADING));
        verify(adapter).billOfLading(scope.tenant(),scope.connection(),"FBA-REAL");assertEquals(DocumentKind.BILL_OF_LADING,store.current.checkpoint().origin().documents().getFirst().kind());
    }
    @Test void staleChargeCannotBeConfirmedAndEarlierChildrenSurviveFailure(){
        throughPlacement();var children=store.current.checkpoint().origin().children();
        step(TRANSPORT_OPTIONS,null,null);service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS);
        doReturn(json.readTree("{\"transportationOptions\":[]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.TRANSPORT_OPTIONS),any(),any(),any());
        int prior=calls.get();assertThrows(IllegalStateException.class,()->service().execute(scope,warehouse,"owner",new Command(UUID.randomUUID(),store.current.revision(),CONFIRM_TRANSPORT,"A","transport-A",true)));
        assertEquals(prior,calls.get());assertEquals(children,store.current.checkpoint().origin().children());
        doReturn(json.readTree("{\"transportationOptions\":[{\"transportationOptionId\":\"unknown-transport\",\"shipmentId\":\"A\"}]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.TRANSPORT_OPTIONS),any(),any(),any());
        service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS);
        assertNull(store.current.checkpoint().options().get("unknown-transport").charge());
        assertFalse(store.current.checkpoint().origin().quotes().containsKey("unknown-transport"));
    }
    @Test void unknownOrMalformedAmountsNeverBecomeFreeOptions(){
        fixture();step(CREATE,null,null);
        doReturn(json.readTree("{\"placementOptions\":[{\"placementOptionId\":\"missing-fees\"}]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.PLACEMENT_OPTIONS),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.PLACEMENT_OPTIONS));
        doReturn(json.readTree("{\"placementOptions\":[{\"placementOptionId\":\"bad-fee\",\"fees\":[{\"value\":{\"amount\":\"unknown\",\"code\":\"USD\"}}]}]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.PLACEMENT_OPTIONS),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.PLACEMENT_OPTIONS));
        doReturn(json.readTree("{\"transportationOptions\":[{\"transportationOptionId\":\"unknown-transport\",\"shipmentId\":\"A\"}]}")).when(adapter).read(any(),any(),eq(FbaInboundAdapter.Read.TRANSPORT_OPTIONS),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service().refreshOptions(scope,warehouse,"owner",store.current.revision(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS));
        assertFalse(store.current.checkpoint().origin().quotes().containsKey("unknown-transport"));
    }
    @Test void stageSkippingAndForeignScopeNeverInvokeApi(){
        fixture();step(CREATE,null,null);int prior=calls.get();
        assertThrows(IllegalStateException.class,()->service().execute(scope,warehouse,"owner",new Command(UUID.randomUUID(),store.current.revision(),TRANSPORT_OPTIONS,null,null,false)));
        assertEquals(prior,calls.get());Scope foreign=new Scope(UUID.randomUUID(),scope.account(),scope.connection(),scope.replenishment());
        assertThrows(IllegalStateException.class,()->service().execute(foreign,warehouse,"owner",new Command(UUID.randomUUID(),store.current.revision(),PACKING_OPTIONS,null,null,false)));
    }
}
