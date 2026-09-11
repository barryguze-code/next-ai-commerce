package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollaborationEmailContentTest {
    @Test
    void escapesUserTextAndLinksBackToTheConversationList() {
        var mention=new CollaborationRepository.PendingMention(UUID.randomUUID(),UUID.randomUUID(),"Alex","alex@example.com",
            "Ibcore","Cold item <script>","barry@example.com","Please check <b>this</b>","MENTION");
        String html=CollaborationEmailContent.html(mention,"https://example.test/app/collaboration");
        assertThat(html).contains("Cold item &lt;script&gt;","Please check &lt;b&gt;this&lt;/b&gt;",
            "https://example.test/app/collaboration").doesNotContain("<script>","<b>this</b>");
    }

    @Test
    void completionEmailClearlyNamesTheCloserAndRecord() {
        var completion=new CollaborationRepository.PendingMention(UUID.randomUUID(),UUID.randomUUID(),"Alex","alex@example.com",
            "Ibcore","Cold inventory","barry@example.com","Conversation completed by barry@example.com.","COMPLETED");
        assertThat(CollaborationEmailContent.subject(completion)).isEqualTo("Conversation completed · Cold inventory");
        assertThat(CollaborationEmailContent.html(completion,"https://example.test/app/collaboration?status=CLOSED"))
            .contains("Conversation completed by barry@example.com","Cold inventory","status=CLOSED");
    }
}
