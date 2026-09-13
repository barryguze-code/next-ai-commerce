package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.ObjectMapper;

class ReleaseNotesControllerTest {
    @Test void patchVersionsShareOneEntryAndHistoryKeepsFiveFeatureReleases(){
        var history=java.util.stream.Stream.of("1.5.2","1.5.1","1.4.0","1.3.1","1.2.0","1.1.0","1.0.1")
            .map(version->new ReleaseNotesController.Release(version,version,java.util.List.of())).toList();
        assertThat(ReleaseNotesController.featureReleases(history)).extracting(ReleaseNotesController.Release::version)
            .containsExactly("1.5","1.4","1.3","1.2","1.1");
        assertThat(ReleaseNotesController.featureVersion("1.1.3")).isEqualTo("1.1");
    }
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
