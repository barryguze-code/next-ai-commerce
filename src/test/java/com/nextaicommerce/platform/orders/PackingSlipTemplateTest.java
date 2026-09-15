package com.nextaicommerce.platform.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

class PackingSlipTemplateTest {
    @Test void rendersThePrintSizedInternalPackingDetails(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode(TemplateMode.HTML);
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var slip=new PackingSlipRepository.Slip("113-1234567-1234567","https://sellercentral.amazon.com/orders-v3/order/113-1234567-1234567","Ski Queen",
            "UPS OMBox",true,List.of(new PackingSlipRepository.Line("53383","Cheese Gjetost",1,LocalDate.of(2027,6,17),"MAIN"),
                new PackingSlipRepository.Line("12345","Second product",2,null,"F1"),
                new PackingSlipRepository.Line("67890","Third product",3,null,"F2")));
        assertThat(slip.pages()).hasSize(2).allSatisfy(page->assertThat(page).hasSizeLessThanOrEqualTo(2));
        var html=engine.process("packing-slip",new Context(null,java.util.Map.of("slip",slip)));
        assertThat(html)
            .contains("Ski Queen","UPS OMBox","53383","12345","67890","Cheese Gjetost","Second product","Third product","06/17/27","MAIN","Seller Central","Save package","Print 2 × 1 slip")
            .doesNotContain("IB-MKH-53383-GJETOST-SKIQUEEN-EA","No historical package match","Order 113-1234567-1234567","Internal packing only");
        assertThat(html.substring(html.indexOf("<section class=\"packing-labels\"")))
            .doesNotContain("Seller Central", "<footer", "<a ");
    }
}
