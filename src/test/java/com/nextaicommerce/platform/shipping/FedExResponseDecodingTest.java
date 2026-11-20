package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class FedExResponseDecodingTest {
    @Test void gzipCarrierJsonIsDecodedBeforeParsing() throws Exception {
        String reply = "{\"errors\":[{\"code\":\"ACCOUNT.NUMBER.MISMATCH\"}]}";
        assertThat(FedExClient.readBody(new ByteArrayInputStream(gzip(reply)), "gzip")).isEqualTo(reply);
    }
    @Test void gzipWithoutEncodingHeaderAndCaseInsensitiveHeaderAreSupported() throws Exception {
        for (String encoding : new String[]{"", " GZip "})
            assertThat(FedExClient.readBody(new ByteArrayInputStream(gzip("{}")), encoding)).isEqualTo("{}");
    }
    @Test void plainJsonRemainsUnchanged() throws Exception {
        for (String encoding : new String[]{"", "identity"})
            assertThat(FedExClient.readBody(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), encoding)).isEqualTo("{}");
    }
    @Test void unsupportedAndCorruptEncodingFailClosed() {
        assertThatThrownBy(() -> FedExClient.readBody(new ByteArrayInputStream(new byte[]{1, 2}), "br")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> FedExClient.readBody(new ByteArrayInputStream(new byte[]{1, 2}), "gzip")).isInstanceOf(IOException.class);
    }
    @Test void compressedAndExpandedBodiesBothHaveSizeLimits() throws Exception {
        assertThatThrownBy(() -> FedExClient.readBody(new ByteArrayInputStream(new byte[8_000_001]), ""))
            .isInstanceOf(IllegalStateException.class).hasMessage("FedEx response exceeded the supported size.");
        byte[] bomb = gzip("x".repeat(8_000_001));
        assertThatThrownBy(() -> FedExClient.readBody(new ByteArrayInputStream(bomb), "gzip"))
            .isInstanceOf(IllegalStateException.class).hasMessage("FedEx response exceeded the supported size.");
    }
    private static byte[] gzip(String value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(value.getBytes(StandardCharsets.UTF_8)); }
        return bytes.toByteArray();
    }
}
