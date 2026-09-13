package com.nextaicommerce.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** A visible build marker makes it clear which local deployment is currently running. */
@ControllerAdvice
public class BuildVersionAdvice {
    @Value("${app.build-version:development}") private String buildVersion;
    @Value("${app.build-branch:}") private String buildBranch;
    @Value("${app.local-development:false}") private boolean localDevelopment;
    @ModelAttribute("buildVersion") String buildVersion() { return buildVersion; }
    @ModelAttribute("buildBranch") String buildBranch() {
        return localDevelopment ? checkoutBranch(java.nio.file.Path.of("."),buildBranch) : buildBranch;
    }
    static String checkoutBranch(java.nio.file.Path root,String fallback) {
        try {
            var git=root.resolve(".git");
            if(java.nio.file.Files.isRegularFile(git)) {
                String pointer=java.nio.file.Files.readString(git).trim();
                if(!pointer.startsWith("gitdir: "))return fallback;
                git=root.resolve(pointer.substring(8)).normalize();
            }
            String head=java.nio.file.Files.readString(git.resolve("HEAD")).trim();
            if(head.startsWith("ref: refs/heads/"))return head.substring(16);
            return head.matches("[a-f0-9]{40}") ? "detached · "+head.substring(0,8) : fallback;
        } catch(java.io.IOException ex) { return fallback; }
    }
    @ModelAttribute("displayVersion") String displayVersion() {
        var match=java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+){1,3})").matcher(buildVersion);
        if(!match.find()) return buildVersion;
        var version=match.group(1);
        return buildVersion.contains("-local-uat") ? version+" · Local UAT" : version;
    }
}
