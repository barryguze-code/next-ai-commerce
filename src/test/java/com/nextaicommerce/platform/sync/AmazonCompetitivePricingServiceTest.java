package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AmazonCompetitivePricingServiceTest {
    @Test
    void readsNewBuyBoxLandedPriceFromCompetitivePricingResponse() throws Exception {
        var response=new ObjectMapper().readTree("""
            {"payload":[{"status":"Success","SellerSKU":"SKU-123","Product":{"CompetitivePricing":{
              "CompetitivePrices":[{"CompetitivePriceId":"1","condition":"New","Price":{
                "ListingPrice":{"CurrencyCode":"USD","Amount":12.49},
                "LandedPrice":{"CurrencyCode":"USD","Amount":14.48}}}]}}}]}
            """);

        var prices=AmazonCompetitivePricingService.readPrices(response);

        assertThat(prices).hasSize(1);
        assertThat(prices.getFirst().identifier()).isEqualTo("SKU-123");
        assertThat(prices.getFirst().amount()).isEqualByComparingTo(new BigDecimal("14.48"));
        assertThat(prices.getFirst().currency()).isEqualTo("USD");
    }

    @Test
    void readsBuyBoxForAsinEvenWhenTheSellerOfferIsNotActive() throws Exception {
        var response=new ObjectMapper().readTree("""
            {"payload":[{"status":"Success","ASIN":"B012345678","Product":{"CompetitivePricing":{
              "CompetitivePrices":[{"CompetitivePriceId":"1","condition":"New","Price":{
                "LandedPrice":{"CurrencyCode":"USD","Amount":21.95}}}]}}}]}
            """);

        var prices=AmazonCompetitivePricingService.readPrices(response,true);

        assertThat(prices).hasSize(1);
        assertThat(prices.getFirst().identifier()).isEqualTo("B012345678");
        assertThat(prices.getFirst().amount()).isEqualByComparingTo("21.95");
    }

    @Test
    void readsFeaturedOfferFromItemOffersFallback() throws Exception {
        var response=new ObjectMapper().readTree("""
            {"responses":[{"status":{"statusCode":200},"body":{"payload":{"ASIN":"B012345678",
              "Summary":{"BuyBoxPrices":[{"condition":"New","LandedPrice":{
                "CurrencyCode":"USD","Amount":24.75}}]}}}}]}
            """);

        var prices=AmazonCompetitivePricingService.readOfferPrices(response);

        assertThat(prices).hasSize(1);
        assertThat(prices.getFirst().identifier()).isEqualTo("B012345678");
        assertThat(prices.getFirst().amount()).isEqualByComparingTo("24.75");
    }

    @Test
    void readsWinningOfferWhenSummaryOmitsBuyBoxPrice() throws Exception {
        var response=new ObjectMapper().readTree("""
            {"responses":[{"body":{"payload":{"ASIN":"B087654321","Offers":[
              {"IsBuyBoxWinner":false,"ListingPrice":{"CurrencyCode":"USD","Amount":19.00}},
              {"IsBuyBoxWinner":true,"ListingPrice":{"CurrencyCode":"USD","Amount":20.00},
               "Shipping":{"CurrencyCode":"USD","Amount":2.99}}]}}}]}
            """);

        var prices=AmazonCompetitivePricingService.readOfferPrices(response);

        assertThat(prices).hasSize(1);
        assertThat(prices.getFirst().identifier()).isEqualTo("B087654321");
        assertThat(prices.getFirst().amount()).isEqualByComparingTo("22.99");
    }
}
