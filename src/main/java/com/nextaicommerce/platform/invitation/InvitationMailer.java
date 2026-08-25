package com.nextaicommerce.platform.invitation;

public interface InvitationMailer {
    void send(String recipient, String accountName, String role, String verificationUrl);
}
