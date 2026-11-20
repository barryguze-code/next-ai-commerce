package com.nextaicommerce.platform.fees;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

class AmazonOrderFeeParserTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AmazonOrderFeeParser parser=new AmazonOrderFeeParser();
    private final String market="ATVPDKIKX0DER",seller="SYNTHETIC";
    private ObjectNode money(String type,String amount){
        var n=json.createObjectNode().put("breakdownType",type);
        n.putObject("breakdownAmount").put("currencyCode","USD").put("currencyAmount",new BigDecimal(amount));return n;
    }
    private ObjectNode transaction(){
        var t=json.createObjectNode().put("transactionId","SYN-TX").put("transactionType","Shipment")
            .put("transactionStatus","RELEASED").put("postedDate","2026-10-24T12:00:00Z");
        t.putObject("marketplaceDetails").put("marketplaceId",market);
        t.putObject("sellingPartnerMetadata").put("sellingPartnerId",seller).put("marketplaceId",market);
        t.putArray("relatedIdentifiers").addObject().put("relatedIdentifierName","ORDER_ID").put("relatedIdentifierValue","SYN-ORDER");
        var item=t.putArray("items").addObject();
        item.putArray("contexts").addObject().put("contextType","ProductContext").put("sku","00001")
            .put("quantityShipped",2).put("fulfillmentNetwork","AFN");
        var product=money("ProductCharges","60");product.putArray("breakdowns").add(money("OurPricePrincipal","60"));
        ObjectNode fees=money("AmazonFees","-18"),referral=money("Commission","-6"),fba=money("FBAPerUnitFulfillmentFee","-12");
        referral.putArray("breakdowns").add(money("Base","-6"));fba.putArray("breakdowns").add(money("Base","-12"));
        fees.putArray("breakdowns").add(referral).add(fba);
        var tax=money("Tax","0");tax.putArray("breakdowns").add(money("OurPriceTax","2")).add(money("MarketplaceFacilitatorTax-Principal","-2"));
        item.putArray("breakdowns").add(product).add(fees).add(tax);return t;
    }
    private ArrayNode feeChildren(ObjectNode t){return (ArrayNode)t.path("items").get(0).path("breakdowns").get(1).path("breakdowns");}
    @Test void retainedBaseShapeProducesOneExpensePerFeeWithoutCountingChildrenAgain(){
        var charges=parser.parse(transaction(),market,seller);
        assertThat(charges).hasSize(2);assertThat(charges).allMatch(c->c.state().equals("SUPPORTED"));
        assertThat(charges.stream().filter(c->c.type().equals("FBA")).findFirst().orElseThrow().expense()).isEqualByComparingTo("12");
        assertThat(charges.stream().filter(c->c.type().equals("REFERRAL")).findFirst().orElseThrow().expense()).isEqualByComparingTo("6");
    }
    @Test void mismatchedBaseChildrenDoNotBecomeAnExpense(){
        var t=transaction();((ObjectNode)feeChildren(t).get(1).path("breakdowns").get(0).path("breakdownAmount")).put("currencyAmount",-11);
        assertThat(parser.parse(t,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
    }
    @Test void feeTaxOrPromotionNeedsAnExplicitComparableAssessmentContract(){
        for(String extra:List.of("Tax","Promo")){
            var t=transaction();((ArrayNode)feeChildren(t).get(1).path("breakdowns")).add(money(extra,"0"));
            assertThat(parser.parse(t,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
        }
    }
    @Test void knownTaxNameUnderFeesIsNotGloballyIgnored(){
        var t=transaction();var disguised=money("Tax","-1");disguised.putArray("breakdowns").add(money("OurPriceTax","-1"));feeChildren(t).add(disguised);
        assertThat(parser.parse(t,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
    }
    @Test void mixedMonetaryCurrenciesAndContradictoryMarketplacesStayUnresolved(){
        var currency=transaction();((ObjectNode)currency.path("items").get(0).path("breakdowns").get(2).path("breakdowns").get(0).path("breakdownAmount")).put("currencyCode","CAD");
        assertThat(parser.parse(currency,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
        var scope=transaction();((ObjectNode)scope.path("sellingPartnerMetadata")).put("marketplaceId","OTHER");
        assertThat(parser.parse(scope,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
    }
    @Test void unreconciledRecognizedContainersAndDuplicateContainersRemainUnresolved(){
        for(int index:List.of(0,1,2)){
            var t=transaction();((ObjectNode)t.path("items").get(0).path("breakdowns").get(index).path("breakdownAmount")).put("currencyAmount",999);
            assertThat(parser.parse(t,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
        }
        var t=transaction();var nodes=(ArrayNode)t.path("items").get(0).path("breakdowns");nodes.add(nodes.get(0).deepCopy());
        assertThat(parser.parse(t,market,seller)).noneMatch(c->c.state().equals("SUPPORTED"));
    }

}
