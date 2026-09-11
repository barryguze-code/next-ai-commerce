package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CollaborationPrivacyContractTest {
    @Test
    void repositoryNeverReadsAnotherUsersPrivateNotesOrFiles() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/nextaicommerce/platform/collaboration/CollaborationRepository.java"));
        assertThat(source).contains("private_message.message_type='PRIVATE_NOTE'",
            "lower(private_message.author_email)=lower", "lower(message.author_email)=lower",
            "message.message_type='TEAM_CHAT' OR lower(message.author_email)=lower(?)", "app.user_email",
            "count(message.id) FILTER", "HAVING bool_or(message.message_type='TEAM_CHAT'",
            "message.message_type='PRIVATE_NOTE' AND lower(message.author_email)=lower(%s)");
    }

    @Test
    void privateComposerDoesNotOfferMentionsAndAttachmentsAreBounded() throws Exception {
        String javascript=Files.readString(Path.of("src/main/resources/static/js/collaboration.js"));
        String controller=Files.readString(Path.of("src/main/java/com/nextaicommerce/platform/collaboration/CollaborationController.java"));
        assertThat(javascript).contains("state?.messageType==='PRIVATE_NOTE'", "Private to you");
        assertThat(controller).contains("if(\"TEAM_CHAT\".equals(type))mentions.queue", "present.size()>3",
            "file.getSize()>5_000_000", "total>10_000_000");
    }
}
