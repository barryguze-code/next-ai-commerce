package com.nextaicommerce.platform.invitation;

public interface InvitationMailer {
    void send(String recipient, String inviterName, String accountName, String role, String verificationUrl);
}
