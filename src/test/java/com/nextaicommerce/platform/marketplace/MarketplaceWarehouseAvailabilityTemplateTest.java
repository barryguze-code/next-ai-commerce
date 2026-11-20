package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.math.BigDecimal;
import org.junit.jupiter.api.Test;import org.thymeleaf.context.Context;import org.thymeleaf.spring6.SpringTemplateEngine;import org.thymeleaf.templateresolver.StringTemplateResolver;
class MarketplaceWarehouseAvailabilityTemplateTest {
    String render(boolean fba,boolean parent,MarketplaceWarehouseAvailability.Availability availability)throws Exception{
        String source=Files.readString(Path.of("src/main/resources/templates/marketplace-skus.html"));int start=source.indexOf("<td data-column=\"warehouse-available\"");String cell=source.substring(start,source.indexOf("</td>",start)+5);
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());var context=new Context();context.setVariables(Map.of("row",MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","SKU","fulfillmentChannel",fba?"AMAZON_NA":"DEFAULT")),"isParent",parent,"warehouseAvailability",availability==null?Map.of():Map.of("SKU",availability)));
        return engine.process("<table><tbody><tr>"+cell+"</tr></tbody></table>",context);
    }
    @Test void physicalLinesRenderSeparatelyWithWarehouseNameAndCaseEquivalent()throws Exception{
        var line=new MarketplaceWarehouseAvailability.Line(UUID.randomUUID(),"Bundle product","CODE",UUID.randomUUID(),"77377 TX","South warehouse",new BigDecimal("18"),new BigDecimal("12"));
        String html=render(true,false,new MarketplaceWarehouseAvailability.Availability("AVAILABLE",List.of(line),true));assertThat(html).contains("77377 TX","South warehouse","CODE · Bundle product","18 each · 1 case + 6 each").doesNotContain(">Unavailable<");
    }
    @Test void notApplicableUnknownAndUnmappedNeverRenderInventedZero()throws Exception{
        assertThat(render(false,false,null)).contains("—").doesNotContain("Unavailable");assertThat(render(true,true,null)).contains("—");assertThat(render(true,false,null)).contains("Unavailable").doesNotContain("0 each");assertThat(render(true,false,MarketplaceWarehouseAvailability.Availability.notMapped())).contains("Not mapped").doesNotContain("0 each");
    }
}
