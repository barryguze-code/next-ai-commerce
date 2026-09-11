package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PostFormCsrfTemplateTest {
    private static final Pattern POST_FORM=Pattern.compile(
        "<form\\b([^>]*)method=\"post\"([^>]*)>(.*?)</form>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

    @Test
    void everyPostFormUsesThymeleafActionOrAnExplicitCsrfToken() throws Exception {
        Path templates=Path.of("src/main/resources/templates");
        try(var paths=Files.walk(templates)){
            for(Path path:paths.filter(file->file.toString().endsWith(".html")).toList()){
                String html=Files.readString(path);var matcher=POST_FORM.matcher(html);int formNumber=0;
                while(matcher.find()){
                    formNumber++;
                    String opening=matcher.group(1)+matcher.group(2);String body=matcher.group(3);
                    assertThat(opening.contains("th:action=")||body.contains("_csrf"))
                        .as(path+" POST form "+formNumber+" must carry CSRF protection").isTrue();
                }
            }
        }
    }

    @Test
    void ordersDoNotExposeManualShipmentConfirmation() throws Exception {
        String orders=Files.readString(Path.of("src/main/resources/templates/orders.html"));
        assertThat(orders).doesNotContain("Confirm shipment","/ship");
    }
}
