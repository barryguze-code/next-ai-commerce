package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import java.util.*;import com.nextaicommerce.platform.sync.AmazonSpApiClient;
class FbaInboundAdapterTest {
 @Test void localGuardPreventsEveryOutboundOperationBeforeApiOrCredentials(){var api=mock(AmazonSpApiClient.class);var adapter=new FbaInboundAdapter(api,false,false,false);for(var op:FbaInboundAdapter.Operation.values())assertThrows(IllegalStateException.class,()->adapter.invoke(UUID.randomUUID(),UUID.randomUUID(),op,"verified-plan","option","{}",true));verifyNoInteractions(api);}
 @Test void globalGuardCannotBeBypassedByFeatureFlag(){var api=mock(AmazonSpApiClient.class);assertThrows(IllegalStateException.class,()->new FbaInboundAdapter(api,true,false,true).invoke(UUID.randomUUID(),UUID.randomUUID(),FbaInboundAdapter.Operation.CREATE,null,null,"{}",true));verifyNoInteractions(api);}
 @Test void paidActionsRequireBothChargeFlagAndExplicitConfirmation(){var api=mock(AmazonSpApiClient.class);for(boolean charges:new boolean[]{false,true})assertThrows(IllegalStateException.class,()->new FbaInboundAdapter(api,true,true,charges).invoke(UUID.randomUUID(),UUID.randomUUID(),FbaInboundAdapter.Operation.CONFIRM_TRANSPORT,"plan",null,"{}",false));verifyNoInteractions(api);}
 @Test void endpointIdsCannotEscapeOperationPath(){assertEquals("/inbound/fba/2024-03-20/inboundPlans/plan%2Fother/placementOptions/opt%20x/confirmation",FbaInboundAdapter.path(FbaInboundAdapter.Operation.CONFIRM_PLACEMENT,"plan/other","opt x"));assertThrows(IllegalArgumentException.class,()->FbaInboundAdapter.path(FbaInboundAdapter.Operation.CANCEL,"",null));}
 @Test void cancellationUsesOfficialPutAndNeverPost(){
  var api=mock(AmazonSpApiClient.class);var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var node=new tools.jackson.databind.ObjectMapper().readTree("{}");
  when(api.put(tenant,connection,"/inbound/fba/2024-03-20/inboundPlans/plan/cancellation","{}")).thenReturn(new AmazonSpApiClient.ApiResponse(202,"request",node,"{}"));
  new FbaInboundAdapter(api,true,true,false).invoke(tenant,connection,FbaInboundAdapter.Operation.CANCEL,"plan",null,"{}",false);
  verify(api).put(tenant,connection,"/inbound/fba/2024-03-20/inboundPlans/plan/cancellation","{}");verify(api,never()).post(any(),any(),any(),any());
 }
 @Test void typedReadAndDocumentsRemainOffBeforeApiAccess(){
  var api=mock(AmazonSpApiClient.class);var a=new FbaInboundAdapter(api,false,false,false);
  for(var read:FbaInboundAdapter.Read.values())assertThrows(IllegalStateException.class,()->a.read(UUID.randomUUID(),UUID.randomUUID(),read,"plan","child",null));
  assertThrows(IllegalStateException.class,()->a.read(UUID.randomUUID(),UUID.randomUUID(),FbaInboundAdapter.Read.TRANSPORT_OPTIONS,"plan",null,null,"placement"));
  assertThrows(IllegalStateException.class,()->a.billOfLading(UUID.randomUUID(),UUID.randomUUID(),"FBA"));
  assertThrows(IllegalStateException.class,()->a.labels(UUID.randomUUID(),UUID.randomUUID(),"FBA",FbaInboundAdapter.LabelPage.PackageLabel_Letter_2,List.of("box")));verifyNoInteractions(api);
 }
 @Test void typedReadsEncodeChildAndPaginationWithoutPathInjection(){
  assertEquals("/inbound/fba/2024-03-20/inboundPlans/plan/shipments/child%2Fother/items?paginationToken=page%2Btoken",FbaInboundAdapter.readPath(FbaInboundAdapter.Read.SHIPMENT_ITEMS,"plan","child/other","page+token"));
 }
 @Test void transportationChoicesCarryEncodedConfirmedPlacementAndPagination(){
  assertEquals("/inbound/fba/2024-03-20/inboundPlans/plan/transportationOptions?paginationToken=page%2Btoken&placementOptionId=placement%2Fone",FbaInboundAdapter.readPath(FbaInboundAdapter.Read.TRANSPORT_OPTIONS,"plan",null,"page+token","placement/one"));
  assertEquals("/inbound/fba/2024-03-20/inboundPlans/plan/transportationOptions?placementOptionId=placement",FbaInboundAdapter.readPath(FbaInboundAdapter.Read.TRANSPORT_OPTIONS,"plan",null,null,"placement"));
  assertThrows(IllegalArgumentException.class,()->FbaInboundAdapter.readPath(FbaInboundAdapter.Read.TRANSPORT_OPTIONS,"plan",null,null,""));
 }
 @Test void billOfLadingUsesOfficialTypedReadPath(){assertEquals("/fba/inbound/v0/shipments/FBA%2Fone/billOfLading",FbaInboundAdapter.billOfLadingPath("FBA/one"));}
}
