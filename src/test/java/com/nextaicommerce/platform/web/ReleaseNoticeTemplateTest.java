package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ReleaseNoticeTemplateTest {
    private String render(ReleaseNoticeAdvice.Notice notice) {
        var resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var context=new Context();context.setVariable("releaseNotice",notice);
        return engine.process("fragments/release-notice",context);
    }
    @Test void disabledNoticeDoesNotLoadAssetsOrControls() {
        assertThat(render(null)).doesNotContain("data-release-notice","release-notice.js","Got it");
    }
    @Test void enabledNoticeRendersOnlyExplicitDisplayMetadata() {
        assertThat(render(new ReleaseNoticeAdvice.Notice("release-1.7.0-r1","2026-10-23T23:00:00Z")))
            .contains("data-notice-version=\"release-1.7.0-r1\"","data-valid-until=\"2026-10-23T23:00:00Z\"",
                "To be confirmed.","Estimated release work:","30–60 minutes.","Service interruption has not yet been confirmed.","Got it","We’re preparing improvements")
            .doesNotContain("<form","fetch(","Maintenance in progress");
    }
}
