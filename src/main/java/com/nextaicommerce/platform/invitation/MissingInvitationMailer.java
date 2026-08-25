package com.nextaicommerce.platform.invitation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class MissingInvitationMailer implements InvitationMailer {
    @Override
    public void send(String recipient, String accountName, String role, String verificationUrl) {
        throw new InvitationException("Email delivery is not configured yet. Add the mail settings and try again.");
    }
}
