package com.nextaicommerce.platform.sync;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class NoticeHotfixStartupGuardTest {
    @Test void explicitStartupPauseDoesNotReadOrEnqueueAnything() {
        var jdbc=mock(JdbcTemplate.class);
        var transactions=mock(TransactionTemplate.class);
        new AmazonStartupOrderSync(jdbc,transactions,false).enqueue();
        verifyNoInteractions(jdbc,transactions);
    }
}
