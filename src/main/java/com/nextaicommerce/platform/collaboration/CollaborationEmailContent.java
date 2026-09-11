package com.nextaicommerce.platform.collaboration;

final class CollaborationEmailContent {
    private CollaborationEmailContent() {}

    static String subject(CollaborationRepository.PendingMention mention) {
        return "COMPLETED".equals(mention.notificationKind())
            ? "Conversation completed · "+mention.subjectLabel()
            : mention.authorEmail()+" mentioned you · "+mention.subjectLabel();
    }

    static String html(CollaborationRepository.PendingMention mention, String conversationUrl) {
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue',Arial,sans-serif;color:#15243a;max-width:580px;margin:auto;padding:28px">
              <p style="font-size:12px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:#6d7888;margin:0 0 10px">Next AI Commerce · %s</p>
              <h1 style="font-size:23px;margin:0 0 12px">%s</h1>
              <p style="font-size:14px;color:#66758a;margin:0 0 18px">Conversation about <strong>%s</strong></p>
              <div style="font-size:15px;line-height:1.6;background:#f5f7fa;border:1px solid #dfe5ec;border-radius:12px;padding:16px">%s</div>
              <p style="margin:24px 0 10px"><a href="%s" style="background:#2d6cdf;color:white;text-decoration:none;padding:11px 16px;border-radius:9px;font-weight:650">Open conversation</a></p>
              <p style="font-size:12px;color:#7b8795">Reply in the platform so the complete history remains attached to the record.</p>
            </div>
            """.formatted(escape(mention.accountName()),escape("COMPLETED".equals(mention.notificationKind())
                    ? "Conversation completed by "+mention.authorEmail() : mention.authorEmail()+" mentioned you"),escape(mention.subjectLabel()),
                escape(mention.body()).replace("\n","<br>"),escape(conversationUrl));
    }

    private static String escape(String value) {
        return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
