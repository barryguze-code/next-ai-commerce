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
        var slip=new PackingSlipRepository.Slip("113-1234567-1234567","https://sellercentral.amazon.com/orders-v3/order/113-1234567-1234567",
            "XSmall",true,List.of(new PackingSlipRepository.Line("IB-MKH-53383-GJETOST-SKIQUEEN-EA","Cheese Gjetost",1,LocalDate.of(2027,6,17),"MAIN")));
        assertThat(engine.process("packing-slip",new Context(null,java.util.Map.of("slip",slip))))
            .contains("NextAI Commerce","XSmall","Cheese Gjetost","2027-06-17","MAIN","Seller Central","Save package","Print 2 × 1 slip")
            .doesNotContain("No historical package match");
    }
}
