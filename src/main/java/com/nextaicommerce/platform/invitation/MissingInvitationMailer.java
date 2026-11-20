package com.nextaicommerce.platform.invitation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "disabled", matchIfMissing = true)
public class MissingInvitationMailer implements InvitationMailer, PlatformMailer {
    @Override
    public void send(String recipient, String inviterName, String accountName, String role, String verificationUrl) {
        throw new InvitationException("Email delivery is not configured yet. Add the mail settings and try again.");
    }

    @Override
    public void sendHtml(String recipient, String subject, String html) {
        throw new InvitationException("Email delivery is not configured yet. Add the mail settings and try again.");
    }
}
