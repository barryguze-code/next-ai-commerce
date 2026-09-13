package com.nextaicommerce.platform.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

class InvitationSenderNameTest {
    @Test void invitationsKeepConfiguredMailboxAndUseInvitingUserName() throws Exception {
        var transport=mock(JavaMailSender.class);
        var message=new MimeMessage(Session.getInstance(new java.util.Properties()));
        when(transport.createMimeMessage()).thenReturn(message);
        var mailer=new SmtpInvitationMailer(transport,"barry.guze@nextaicommerce.com","Next AI Commerce");
        mailer.send("test@example.test","Ibcore Admin","Ibcore","Viewer","https://example.test/activate");
        var sender=(InternetAddress)message.getFrom()[0];
        assertThat(sender.getAddress()).isEqualTo("barry.guze@nextaicommerce.com");
        assertThat(sender.getPersonal()).isEqualTo("Ibcore Admin");
        verify(transport).send(message);
    }
    @Test void senderDisplayCannotInjectHeadersAndHasSafeFallback(){
        assertThat(InvitationEmailContent.senderName("Alice\r\nTest","Platform")).isEqualTo("Alice Test");
        assertThat(InvitationEmailContent.senderName(" ","Platform")).isEqualTo("Platform");
        assertThat(InvitationEmailContent.senderName(null,null)).isEqualTo("Next AI Commerce");
    }
}
