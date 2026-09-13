package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.ObjectMapper;

class ReleaseNotesControllerTest {
    @Test void selectsCurrentPreviousAndUnknownReleases() throws Exception {
        var controller=new ReleaseNotesController(new ObjectMapper());
        var model=new ExtendedModelMap();
        assertThat(controller.releases(null,null,model)).isEqualTo("release-notes");
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.0");
        controller.releases("1.0",null,model);
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.0");
        controller.releases("unknown",null,model);
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.0");
        assertThat((java.util.List<?>)model.get("releases")).hasSizeLessThanOrEqualTo(5);
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        assertThat(engine.process("release-notes",new Context(null,model)))
            .contains("Clearer orders and easier packing","v1.0","three hours","/css/release-notes.css")
            .doesNotContain("value=\"1.0.1\"", "value=\"1.0.0\"");
    }
}
