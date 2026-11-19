package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
class FbaInventorySnapshotTest {
 ObjectMapper json=new ObjectMapper();
 ArrayNode pages(){var pages=json.createArrayNode();var page=pages.addObject();page.put("observedAt",Instant.now().toString());var payload=page.putObject("response").putObject("payload");payload.putObject("granularity").put("granularityType","Marketplace").put("granularityId","US");var r=payload.putArray("inventorySummaries").addObject();r.put("sellerSku","sku").put("totalQuantity",12);var d=r.putObject("inventoryDetails");d.put("fulfillableQuantity",4).put("inboundWorkingQuantity",0).put("inboundShippedQuantity",0).put("inboundReceivingQuantity",0);d.putObject("reservedQuantity").put("totalReservedQuantity",8).put("pendingTransshipmentQuantity",3);d.putObject("unfulfillableQuantity").put("totalUnfulfillableQuantity",0);d.putObject("researchingQuantity").put("totalResearchingQuantity",0);return pages;}
 @Test void preservesRawAndDoesNotDoubleSubtractReserved(){var r=FbaInventorySnapshot.validate("US",pages()).getFirst();assertThat(r.available()).isEqualTo(4);assertThat(r.reserved()).isEqualTo(8);assertThat(r.raw()).contains("afn-fc-transfer-quantity\":3");}
 @Test void rejectsWrongMarketplace(){assertThatThrownBy(()->FbaInventorySnapshot.validate("OTHER",pages())).isInstanceOf(IllegalArgumentException.class);}
 @Test void missingIsNotZero(){var p=pages();((tools.jackson.databind.node.ObjectNode)p.get(0).at("/response/payload/inventorySummaries/0/inventoryDetails")).remove("fulfillableQuantity");assertThatThrownBy(()->FbaInventorySnapshot.validate("US",p)).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsPartialPagination(){var p=pages();((tools.jackson.databind.node.ObjectNode)p.get(0).path("response")).putObject("pagination").put("nextToken","more");assertThatThrownBy(()->FbaInventorySnapshot.validate("US",p)).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsRepeatedSku(){var p=pages();var a=(ArrayNode)p.get(0).at("/response/payload/inventorySummaries");a.add(a.get(0).deepCopy());assertThatThrownBy(()->FbaInventorySnapshot.validate("US",p)).isInstanceOf(IllegalArgumentException.class);}
 @Test void staleCannotBecomeFresh(){var p=pages();((tools.jackson.databind.node.ObjectNode)p.get(0)).put("observedAt",Instant.now().minusSeconds(86400).toString());assertThatThrownBy(()->FbaInventorySnapshot.validate("US",p)).isInstanceOf(IllegalArgumentException.class);}
 @Test void absentSkuDoesNotGenerateZero(){var p=pages();((ArrayNode)p.get(0).at("/response/payload/inventorySummaries")).removeAll();assertThat(FbaInventorySnapshot.validate("US",p)).isEmpty();}
 @Test void rejectsNegative(){var p=pages();((tools.jackson.databind.node.ObjectNode)p.get(0).at("/response/payload/inventorySummaries/0/inventoryDetails")).put("fulfillableQuantity",-1);assertThatThrownBy(()->FbaInventorySnapshot.validate("US",p)).isInstanceOf(IllegalArgumentException.class);}
}
