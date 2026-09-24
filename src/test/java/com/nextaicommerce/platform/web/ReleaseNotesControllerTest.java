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
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.3.10");
        controller.releases("1.0.2",null,model);
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.0.2");
        controller.releases("unknown",null,model);
        assertThat(((ReleaseNotesController.Release)model.get("release")).version()).isEqualTo("1.3.10");
        assertThat((java.util.List<?>)model.get("releases")).hasSizeLessThanOrEqualTo(5);
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        assertThat(engine.process("release-notes",new Context(null,model)))
            .contains("Shared catalogue barcode import fix","v1.3.10","Deployment history","/css/release-notes.css");
        assertThat(controller.history()).extracting(ReleaseNotesController.Release::version)
            .containsExactly("1.3.10","1.3.9","1.3.8","1.3.7","1.3.6","1.3.5","1.3.4","1.3.3","1.3.2","1.3.1","1.3.0","1.2.7","1.2.6","1.2.5","1.2.4","1.2.3","1.2.2","1.2.1","1.2.0","1.1.1","1.1.0","1.0.5","1.0.4","1.0.3","1.0.2","1.0.1","1.0.0");
    }
}
