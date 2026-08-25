package com.nextaicommerce.platform.invitation;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SmtpInvitationMailer implements InvitationMailer {
    private final JavaMailSender mailSender;
    private final String from;

    SmtpInvitationMailer(JavaMailSender mailSender, @Value("${app.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String recipient, String accountName, String role, String verificationUrl) {
        if (from == null || from.isBlank()) throw new InvitationException("Invitation email sender is not configured.");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject("You’re invited to " + accountName + " on Next AI Commerce");
            helper.setText("""
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue',Arial,sans-serif;color:#15243a;max-width:560px;margin:auto;padding:28px">
                  <h1 style="font-size:24px;margin:0 0 12px">Join %s</h1>
                  <p style="font-size:15px;line-height:1.6;color:#5f6d7e">You were invited as %s on Next AI Commerce. Verify this email address to activate your secure access.</p>
                  <p style="margin:28px 0"><a href="%s" style="background:#2d6cdf;color:white;text-decoration:none;padding:12px 18px;border-radius:9px;font-weight:650">Verify email and continue</a></p>
                  <p style="font-size:12px;line-height:1.5;color:#7b8795">This single-use link expires in 7 days. If you were not expecting this invitation, you can ignore this email.</p>
                </div>
                """.formatted(escape(accountName), escape(role), escape(verificationUrl)), true);
            mailSender.send(message);
        } catch (InvitationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvitationException("The invitation could not be emailed. Check the email delivery settings and try again.");
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
