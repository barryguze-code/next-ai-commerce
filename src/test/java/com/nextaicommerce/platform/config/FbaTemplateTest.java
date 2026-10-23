package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class FbaTemplateTest {
    @Test void shipmentPaginationUsesSharedServerPagerContract(){
        var context=context();context.setVariable("query","SKU");context.setVariable("amazonStore",true);
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),50,0,25));
        String html=engine().process("fba-shipments",context);
        // table-data-tools recognizes this class and these attributes; without them it
        // creates a second client-side pager counting only the currently loaded rows.
        assertThat(html).contains("table-pagination","data-page=\"0\"","data-page-max=\"2\"","data-page-size=\"25\"","Total: 50 shipments");
        assertThat(html).contains("FBA Shipment Date").doesNotContain("Last imported");
    }
    @Test void stockRendersUnknownZeroAndFeesWithoutInventingTransfers()throws Exception{
        var context=context();context.setVariable("sku","KARACA <test>");
        context.setVariable("stock",new FbaReadRepository.Stock("KARACA",0,0,100,2,3,0,Instant.now(),new BigDecimal("12"),new BigDecimal("26.23"),"USD",Instant.now(),3));
        String html=engine().process("fragments/fba-stock",context);
        if(Boolean.getBoolean("fbaPreview")){
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local/fba-preview"));
            java.nio.file.Files.writeString(java.nio.file.Path.of(".local/fba-preview/stock.html"),html);
        }
        assertThat(html).contains("<dt>Available</dt>","26.23","Warehouse available","FC transfer","102","fba-shipments?q=").doesNotContain("<test>","Not imported");
        context.setVariable("stock",null);
        assertThat(engine().process("fragments/fba-stock",context)).contains("Pending","Waiting for an Amazon inventory snapshot");
    }
    @Test void shipmentPageRendersEmptyAndPopulatedStates()throws Exception{
        var context=context();context.setVariable("query","");context.setVariable("amazonStore",true);
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),0,0,25));
        assertThat(engine().process("fba-shipments",context)).contains("No FBA shipments imported yet","table-pagination","Ship from");
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(new FbaReadRepository.Shipment("FBA123","<script>bad</script>","SHIPPED","SCK8",null,null,2,30,6)),1,0,25));
        assertThat(engine().process("fba-shipments",context)).contains("FBA123","SCK8","Not supplied","&lt;script&gt;").doesNotContain("<script>bad</script>");
        if(Boolean.getBoolean("fbaPreview")){
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local/fba-preview"));
            java.nio.file.Files.writeString(java.nio.file.Path.of(".local/fba-preview/shipments.html"),engine().process("fba-shipments",context));
        }
    }
    private static SpringTemplateEngine engine(){var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);return engine;}
    private static WebContext context(){var servlet=new MockServletContext();return new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(new MockHttpServletRequest(servlet),new MockHttpServletResponse()));}
}
