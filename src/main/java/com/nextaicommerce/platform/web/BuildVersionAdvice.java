package com.nextaicommerce.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** A visible build marker makes it clear which local deployment is currently running. */
@ControllerAdvice
public class BuildVersionAdvice {
    @Value("${app.build-version:development}") private String buildVersion;
    @ModelAttribute("buildVersion") String buildVersion() { return buildVersion; }
    @ModelAttribute("displayVersion") String displayVersion() {
        var match=java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+){1,3})").matcher(buildVersion);
        return match.find()?match.group(1):buildVersion;
    }
}
