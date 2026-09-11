package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AmazonReportNormalizerTest {
    @Test
    void removesAmazonTsvQuotesFromHeadersNumbersAndText() {
        assertThat(AmazonReportNormalizer.unquote("\"Event Type\"")).isEqualTo("Event Type");
        assertThat(AmazonReportNormalizer.unquote("\"-1\"")).isEqualTo("-1");
        assertThat(AmazonReportNormalizer.unquote("\"A \"\"quoted\"\" title\"")).isEqualTo("A \"quoted\" title");
    }

    @Test
    void identicalLedgerRowsReceiveDistinctStableOccurrenceKeys() {
        String fingerprint=AmazonReportNormalizer.hashFields(
            "2026-08-25T10:00:00-07:00","Receipts","SKU-1","FNSKU-1","1");

        assertThat(AmazonReportNormalizer.occurrenceKey(fingerprint,1)).isEqualTo(fingerprint+":1");
        assertThat(AmazonReportNormalizer.occurrenceKey(fingerprint,2)).isEqualTo(fingerprint+":2");
        assertThat(AmazonReportNormalizer.hashFields(
            "2026-08-25T10:00:00-07:00","Receipts","SKU-1","FNSKU-1","1")).isEqualTo(fingerprint);
    }
}
