package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AmazonListingImageSchedulerTest {
    @Test void recognizesPermanentAmazonCatalogueMisses(){
        assertThat(AmazonListingImageScheduler.isNotFound(
            new AmazonSpApiClient.AmazonApiException(404,null,"missing"))).isTrue();
        assertThat(AmazonListingImageScheduler.isNotFound(
            new IllegalStateException("{ \"errors\": [{ \"code\": \"NOT_FOUND\" }] }"))).isTrue();
        assertThat(AmazonListingImageScheduler.isNotFound(new IllegalStateException("rate limited"))).isFalse();
    }
    @Test
    void selectsTheOfficialMainImageForTheMarketplace() throws Exception {
        var root=new ObjectMapper().readTree("""
            {"images":[
              {"marketplaceId":"OTHER","images":[{"variant":"MAIN","link":"https://wrong.example/image.jpg"}]},
              {"marketplaceId":"ATVPDKIKX0DER","images":[
                {"variant":"PT01","link":"https://images.example/alternate.jpg"},
                {"variant":"MAIN","link":"https://images.example/main.jpg"}
              ]}
            ]}
            """);
        assertThat(AmazonListingImageScheduler.mainImage(root,"ATVPDKIKX0DER"))
            .isEqualTo("https://images.example/main.jpg");
    }
}
