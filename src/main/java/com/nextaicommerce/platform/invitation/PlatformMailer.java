package com.nextaicommerce.platform.invitation;

/** Shared HTML delivery contract for invitations and contextual notifications. */
public interface PlatformMailer {
    void sendHtml(String recipient, String subject, String html);
}
