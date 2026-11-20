package com.nextaicommerce.platform.web;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Informational notice only: never starts maintenance or changes operational flags. */
@ControllerAdvice
public class ReleaseNoticeAdvice {
    private final boolean enabled;
    private final String version;
    private final String validUntil;
    private final String fromVersion;
    private final String toVersion;

    public ReleaseNoticeAdvice(@Value("${app.release-notice.enabled:false}") boolean enabled,
            @Value("${app.release-notice.version:}") String version,
            @Value("${app.release-notice.valid-until:}") String validUntil,
            @Value("${app.release-notice.from-version:}") String fromVersion,
            @Value("${app.release-notice.to-version:}") String toVersion) {
        this.enabled=enabled;
        this.version=version;
        this.validUntil=validUntil;
        this.fromVersion=fromVersion;
        this.toVersion=toVersion;
    }

    public record Notice(String version, String validUntil) {}

    @ModelAttribute("releaseNotice")
    public Notice notice() { return current(enabled && featureUpgrade(fromVersion,toVersion),version,validUntil,Instant.now()); }

    static boolean featureUpgrade(String from,String to) {
        if(from==null || to==null || !from.matches("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}") || !to.matches("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}")) return false;
        String[] previous=from.split("\\."),next=to.split("\\.");
        return (Integer.parseInt(next[0])>Integer.parseInt(previous[0])
            || (Integer.parseInt(next[0])==Integer.parseInt(previous[0])
                && Integer.parseInt(next[1])>Integer.parseInt(previous[1])))
            && Integer.parseInt(next[2])==0;
    }

    static Notice current(boolean enabled,String version,String validUntil,Instant now) {
        if(!enabled || version==null || !version.matches("[A-Za-z0-9._-]{1,80}")) return null;
        try {
            Instant expiry=Instant.parse(validUntil);
            return expiry.isAfter(now) ? new Notice(version,expiry.toString()) : null;
        } catch(RuntimeException invalid) { return null; }
    }
}
