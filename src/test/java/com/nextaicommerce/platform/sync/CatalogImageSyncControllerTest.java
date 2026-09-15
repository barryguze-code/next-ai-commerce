package com.nextaicommerce.platform.sync;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
class CatalogImageSyncControllerTest {
    @Test void onlyDownloadsFromAmazonImageHosts(){
        assertThat(CatalogImageSyncController.safeImageUri(URI.create("https://m.media-amazon.com/images/I/a.jpg"))).isTrue();
        assertThat(CatalogImageSyncController.safeImageUri(URI.create("https://images-na.ssl-images-amazon.com/images/I/a.jpg"))).isTrue();
        for(String url:new String[]{"http://m.media-amazon.com/a","https://localhost/a","https://m.media-amazon.com.evil.test/a","https://user@m.media-amazon.com/a","https://m.media-amazon.com:8080/a"})
            assertThat(CatalogImageSyncController.safeImageUri(URI.create(url))).isFalse();
    }
}
