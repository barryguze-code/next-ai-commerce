package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.web.ShopifyConnectionService.SetupState;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ShopifySetupTemplateTest {
    String render(SetupState state){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var context=new Context();context.setVariable("shopifySetup",state);context.setVariable("selectedStoreName","Unit Shopify store");context.setVariable("selectedAccountName","Current account");
        return engine.process("fragments/shopify-setup",context);
    }
    @Test void previewShowsRealConnectionAndPendingStepsWithoutAnImportAction(){
        String html=render(new SetupState(true,false));
        assertThat(html).contains("Unit Shopify store","Awaiting product permission","Verified","Not started","Not enabled · coming next","disabled","Design preview only").doesNotContain("<form","Switch to an Amazon","Walmart","sync-loader","100%");
    }
    @Test void expiredConnectionDoesNotShowCompletedAndProductScopeStillCannotEnablePreview(){
        assertThat(render(new SetupState(false,false))).contains("Verify this store’s connection","Verification needed").doesNotContain("class=\"completed\"");
        assertThat(render(new SetupState(true,true))).contains("Ready for setup review","SKU collection stays off","disabled");
    }
}
