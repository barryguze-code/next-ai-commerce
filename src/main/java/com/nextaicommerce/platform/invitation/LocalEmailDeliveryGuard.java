package com.nextaicommerce.platform.invitation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Limits real email delivery during local UAT to explicitly approved mailboxes. */
@Component
class LocalEmailDeliveryGuard {
    private final boolean local;
    private final boolean enabled;
    private final Set<String> recipients;

    LocalEmailDeliveryGuard(Environment environment,
            @Value("${app.local-email-uat-enabled:false}") boolean enabled,
            @Value("${app.mail.local-uat-recipients:}") String recipients) {
        local = Arrays.asList(environment.getActiveProfiles()).contains("local");
        this.enabled = enabled;
        this.recipients = Arrays.stream(recipients.split(","))
            .map(value->value.strip().toLowerCase(Locale.ROOT)).filter(value->!value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    void verify(String recipient) {
        if(!local)return;
        String normalized=recipient==null?"":recipient.strip().toLowerCase(Locale.ROOT);
        if(!enabled||!recipients.contains(normalized))
            throw new InvitationException("Local email UAT can send only to the configured test mailbox.");
    }
}
