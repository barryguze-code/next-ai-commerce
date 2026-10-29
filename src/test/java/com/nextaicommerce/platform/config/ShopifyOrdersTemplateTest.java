package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.shopify.*;
import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import static org.assertj.core.api.Assertions.*;

class ShopifyOrdersTemplateTest {
    @Test void sharedOrdersGeometryRendersNativeDataAndMissingPictureWithoutAmazonActions(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var servlet=new MockServletContext();var request=new MockHttpServletRequest(servlet);request.setRequestURI("/app/orders");
        var context=new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,new MockHttpServletResponse()));
        UUID variant=UUID.randomUUID();
        var item=new ShopifyOrderReadRepository.Line(UUID.randomUUID(),variant,"a".repeat(64),"000001-EAx3","Pizza · Brand",null,3,3,BigDecimal.TEN,"USD","READY_TO_SHIP","LIVE",null,List.of(new ShopifyOrderReadRepository.Part(UUID.randomUUID(),"000001",BigDecimal.valueOf(3))),BigDecimal.valueOf(4),"1 | 2 | 3 | 4");
        var order=new ShopifyOrderReadRepository.Order(UUID.randomUUID(),"gid://shopify/Order/1","#1001",Instant.EPOCH,"PENDING","UNFULFILLED","USD",BigDecimal.TEN,true,false,null,"https://fixture.myshopify.com/admin/orders/1",List.of(item));
        context.setVariable("shopifyOrdersPage",new ShopifyOrderReadRepository.Page(List.of(order),1,0,25));context.setVariable("shopifyOrderTabs",List.of(new ShopifyOrderReadRepository.Tab("ALL","All",1)));
        context.setVariable("shopifyOrderSetup",new ShopifyConnectionService.SetupState(true,true,true));context.setVariable("shopifyOrderThreads",Map.of());
        context.setVariable("canSyncShopifyOrders",true);context.setVariable("shopifyShippingSandbox",true);context.setVariable("selectedStatus","ALL");context.setVariable("query","");context.setVariable("orderStreamKey","test");context.setVariable("orderTime",DateTimeFormatter.ISO_INSTANT);
        String html=engine.process("fragments/shopify-orders",context);
        var shipped=new ShopifyOrderReadRepository.Line(UUID.randomUUID(),variant,"a".repeat(64),"DONE","Shipped line",null,1,0,BigDecimal.ONE,"USD","SHIPPED","LIVE",null,item.parts(),BigDecimal.ONE,"0 | 0 | 0 | 0");
        var partial=new ShopifyOrderReadRepository.Order(order.id(),order.gid(),order.name(),order.created(),"PAID","PARTIALLY_FULFILLED","USD",BigDecimal.TEN,true,false,null,order.adminUrl(),List.of(shipped,item));
        context.setVariable("shopifyOrdersPage",new ShopifyOrderReadRepository.Page(List.of(partial),1,0,25));
        String partiallyShippedHtml=engine.process("fragments/shopify-orders",context);
        assertThat(partiallyShippedHtml.split("data-fedex-shipping",-1)).hasSize(2);
        assertThat(partial.sandboxShippingEligible()).isTrue();
        assertThat(html).contains("data-table-widget=\"orders\"","order-pagination","order-product-stage","#1001","000001-EAx3","Pizza · Brand","Pending","Orders/Units","data-profit-kind=\"SHOPIFY_ORDER\"","1 | 2 | 3 | 4","data-provider=\"SHOPIFY\"","data-tone=\"pending\"","state-ready_to_ship","data-fedex-shipping","window.openFedExShipping(this)")
            .doesNotContain("Buy Box","FBA","Seller Central","data-profit-kind=\"ORDER\"","null ×","Payment:","Historical");
        var unmapped=new ShopifyOrderReadRepository.Line(UUID.randomUUID(),variant,"a".repeat(64),"MISSING","Unmapped product",null,1,1,BigDecimal.ONE,"USD","NEEDS_MAPPING","LIVE",null,List.of(),null,null);
        var missing=new ShopifyOrderReadRepository.Order(order.id(),order.gid(),order.name(),order.created(),"PAID","UNFULFILLED","USD",BigDecimal.ONE,true,false,null,order.adminUrl(),List.of(unmapped));
        context.setVariable("shopifyOrdersPage",new ShopifyOrderReadRepository.Page(List.of(missing),1,0,25));
        String missingHtml=engine.process("fragments/shopify-orders",context);
        assertThat(missingHtml).contains("aria-label=\"Map Unmapped product\"","order-inline-mapping-trigger').click()")
            .doesNotContain("Sync details",">Needs mapping<");
        assertThat(html.indexOf("order-pagination")).isGreaterThan(html.indexOf("order-item-grid-header"));
    }
}
