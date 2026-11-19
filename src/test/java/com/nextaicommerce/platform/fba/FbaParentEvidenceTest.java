package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class FbaParentEvidenceTest {
 @Test void staleCompletedMarkerCannotValidateChangedReceivedRows(){var jdbc=mock(JdbcTemplate.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();var row=new HashMap<String,Object>();row.put("shipment_id","A");row.put("evidence_version","1");row.put("observed_at","2026-11-19T10:00:00Z");row.put("seller_sku","SKU");row.put("fnsku","FN");row.put("quantity_shipped",5);row.put("quantity_received",2);row.put("item_evidence","[{\"SellerSKU\":\"SKU\",\"FulfillmentNetworkSKU\":\"FN\",\"QuantityShipped\":5,\"QuantityReceived\":1}]");when(jdbc.queryForList(contains("evidence_version"),eq(tenant),eq(store),eq("A"))).thenReturn(List.of(row));var repo=new FbaReadRepository(jdbc);assertThat(repo.completedShipmentItems(tenant,store,List.of("A"))).isEmpty();row.put("quantity_received",1);assertThat(repo.completedShipmentItems(tenant,store,List.of("A")).get("A").received()).isEqualTo(1);row.remove("evidence_version");assertThat(repo.completedShipmentItems(tenant,store,List.of("A"))).isEmpty();}
}
