package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import tools.jackson.databind.ObjectMapper;
/** Executes the actual ownership SQL over synthetic CTE rows in the existing CI disposable PostgreSQL pattern. */
class FbaReplenishmentOwnershipSqlTest {
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;static JdbcTemplate real;
 final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();final ObjectMapper json=new ObjectMapper();
 @BeforeAll static void start()throws Exception{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();real=new JdbcTemplate(postgres.getPostgresDatabase());}
 @AfterAll static void stop()throws Exception{if(postgres!=null)postgres.close();}
 FbaDepletionRepository repo(boolean historical)throws Exception{return repo(historical,Map.of("api-child-123",Map.of("shipmentId","api-child-123","confirmationId","FBA-CONFIRM-456")));}
 FbaDepletionRepository repo(boolean historical,Object children)throws Exception{
  var origin=Map.of("origin",Map.of("children",children));
  String checkpoint=json.writeValueAsString(historical?Map.of("inboundOriginHistory",Map.of("warehouse",List.of(origin))):Map.of("inboundOrigins",Map.of("warehouse",origin)));
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(startsWith("SELECT to_regclass"),eq(Boolean.class))).thenReturn(true);
  when(jdbc.queryForObject(contains("jsonb_each"),eq(Boolean.class),any(),any(),any(),any(),any())).thenAnswer(call->{Object[] args={tenant,store,checkpoint,call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6)};return real.queryForObject("WITH fba_replenishment_plans(tenant_id,marketplace_connection_id,checkpoint) AS (VALUES(CAST(? AS uuid),CAST(? AS uuid),CAST(? AS jsonb))) "+call.getArgument(0),Boolean.class,args);});return new FbaDepletionRepository(jdbc,json);
 }
 @Test void currentCheckpointBlocksBothApiIdAndDifferentImportedConfirmationId()throws Exception{var r=repo(false);assertTrue(r.managedByReplenishment(tenant,store,"api-child-123"));assertTrue(r.managedByReplenishment(tenant,store,"FBA-CONFIRM-456"));assertFalse(r.managedByReplenishment(tenant,store,"unrelated"));}
 @Test void historicalCheckpointBlocksBothAliasesAfterRetirement()throws Exception{var r=repo(true);assertTrue(r.managedByReplenishment(tenant,store,"api-child-123"));assertTrue(r.managedByReplenishment(tenant,store,"FBA-CONFIRM-456"));}
 @Test void matchingAliasNeverCrossesTenantOrStore()throws Exception{for(boolean old:List.of(false,true)){var r=repo(old);assertFalse(r.managedByReplenishment(UUID.randomUUID(),store,"FBA-CONFIRM-456"));assertFalse(r.managedByReplenishment(tenant,UUID.randomUUID(),"api-child-123"));}}
 @Test void valueAliasesAndAmbiguousConfirmationRemainProtected()throws Exception{for(boolean history:List.of(false,true)){var r=repo(history,Map.of("map-key",Map.of("shipmentId","API-VALUE","confirmationId","FBA-SHARED"),"other-key",Map.of("shipmentId","API-OTHER","confirmationId","FBA-SHARED")));assertTrue(r.managedByReplenishment(tenant,store,"map-key"));assertTrue(r.managedByReplenishment(tenant,store,"API-VALUE"));assertTrue(r.managedByReplenishment(tenant,store,"FBA-SHARED"));}}
 @Test void emptyChildrenDoNotInventOwnershipAndMalformedChildrenFailClosed()throws Exception{assertFalse(repo(false,Map.of()).managedByReplenishment(tenant,store,"FBA-CONFIRM-456"));assertThrows(org.springframework.dao.DataAccessException.class,()->repo(false,List.of("malformed")).managedByReplenishment(tenant,store,"FBA-CONFIRM-456"));}
}
