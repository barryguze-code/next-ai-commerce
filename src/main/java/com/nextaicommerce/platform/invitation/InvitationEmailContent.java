package com.nextaicommerce.platform.invitation;

final class InvitationEmailContent {
    private InvitationEmailContent() {}

    static String subject(String accountName) {
        return "You’re invited to " + accountName + " on Next AI Commerce";
    }

    static String html(String inviterName, String accountName, String role, String verificationUrl) {
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue',Arial,sans-serif;color:#15243a;max-width:560px;margin:auto;padding:28px">
              <h1 style="font-size:24px;margin:0 0 12px">Join %s</h1>
              <p style="font-size:15px;line-height:1.6;color:#5f6d7e"><strong>%s</strong> invited you to join <strong>%s</strong> as %s on Next AI Commerce. Verify this email address to activate your secure access.</p>
              <p style="margin:28px 0"><a href="%s" style="background:#2d6cdf;color:white;text-decoration:none;padding:12px 18px;border-radius:9px;font-weight:650">Verify email and continue</a></p>
              <p style="font-size:12px;line-height:1.5;color:#7b8795">This single-use link expires in 7 days. If you were not expecting this invitation, you can ignore this email.</p>
            </div>
            """.formatted(escape(accountName), escape(inviterName), escape(accountName), escape(role),
                escape(verificationUrl));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
