package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.core.RowMapper;
class FbaSkuShipmentHistoryTest {
 @Test void operationalDispatchWinsWithoutChangingAmazonEvidence(){var q=FbaSkuShipmentHistory.choose(true,1,12,8,true,5);assertThat(q.sent()).isEqualTo(12);assertThat(q.source()).isEqualTo("Local dispatch");}
 @Test void auditedCorrectionWinsOtherwise(){var q=FbaSkuShipmentHistory.choose(true,0,null,8,true,5);assertThat(q.sent()).isEqualTo(8);assertThat(q.source()).isEqualTo("Local correction");}
 @Test void verifiedAmazonShippedIsFallback(){assertThat(FbaSkuShipmentHistory.choose(true,0,null,null,true,5).sent()).isEqualTo(5);}
 @Test void unknownDoesNotBecomeZero(){assertThat(FbaSkuShipmentHistory.choose(true,0,null,null,false,0).sent()).isNull();}
 @Test void verifiedZeroRemainsZero(){assertThat(FbaSkuShipmentHistory.choose(true,0,null,null,true,0).sent()).isZero();}
 @Test void cannotMergeTwoLocalOrigins(){assertThat(FbaSkuShipmentHistory.choose(true,2,12,8,true,5).sent()).isNull();}
 @Test void mismatchedIdentityCannotBorrowQuantity(){assertThat(FbaSkuShipmentHistory.choose(false,1,12,8,true,5).sent()).isNull();}
 @Test void queryBindsExactTenantStoreMarketplaceSkuAndIsReadOnly(){var jdbc=mock(JdbcTemplate.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();new FbaSkuShipmentHistory(jdbc).recent(tenant,store,"US","Exact_SKU%");verify(jdbc).query(eq(FbaSkuShipmentHistory.SQL),any(RowMapper.class),eq(tenant),eq(store),eq("US"),eq("Exact_SKU%"));assertThat(FbaSkuShipmentHistory.SQL).contains("i.seller_sku=?","LIMIT 3","s.activity_at DESC NULLS LAST,s.shipment_id DESC").doesNotContain("quantity_received","sum(quantity_shipped)","ILIKE");}
}
