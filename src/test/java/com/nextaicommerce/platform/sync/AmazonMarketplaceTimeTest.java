package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmazonMarketplaceTimeTest {
    @Test
    void parsesAmazonLedgerOffsetTimestampAndUsDate() {
        var precise=AmazonMarketplaceTime.parse("2026-08-25T00:00:00-0700","ATVPDKIKX0DER");
        assertThat(precise.instant().toInstant()).isEqualTo(Instant.parse("2026-08-25T07:00:00Z"));
        assertThat(precise.marketplaceDate()).isEqualTo(LocalDate.of(2026,8,25));

        var dateOnly=AmazonMarketplaceTime.parse("08/25/2026","ATVPDKIKX0DER");
        assertThat(dateOnly.marketplaceDate()).isEqualTo(LocalDate.of(2026,8,25));
    }
}
