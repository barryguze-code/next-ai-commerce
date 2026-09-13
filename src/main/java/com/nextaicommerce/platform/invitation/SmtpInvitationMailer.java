package com.nextaicommerce.platform.invitation;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpInvitationMailer implements InvitationMailer, PlatformMailer {
    private final JavaMailSender mailSender;
    private final String from;
    private final String fromName;

    SmtpInvitationMailer(JavaMailSender mailSender, @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Next AI Commerce}") String fromName) {
        this.mailSender = mailSender;
        this.from = from;
        this.fromName = fromName;
    }

    @Override
    public void send(String recipient, String inviterName, String accountName, String role, String verificationUrl) {
        sendHtml(recipient, InvitationEmailContent.subject(accountName),
            InvitationEmailContent.html(inviterName, accountName, role, verificationUrl));
    }

    @Override
    public void sendHtml(String recipient, String subject, String html) {
        if (from == null || from.isBlank()) throw new InvitationException("Invitation email sender is not configured.");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (InvitationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvitationException("The invitation could not be emailed. Check the email delivery settings and try again.");
        }
    }

}
