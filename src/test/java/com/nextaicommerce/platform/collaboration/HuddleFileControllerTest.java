package com.nextaicommerce.platform.collaboration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;
class HuddleFileControllerTest {
 @Test void detectsFilesRatherThanTrustingBrowserMime(){
  assertThat(HuddleFileController.fileType(new byte[]{(byte)137,80,78,71,13,10,26,10})).isEqualTo("image/png");
  assertThat(HuddleFileController.fileType("%PDF-1.4".getBytes())).isEqualTo("application/pdf");
  assertThat(HuddleFileController.fileType("<script>example</script>".getBytes())).isEqualTo("text/plain");
  assertThatThrownBy(()->HuddleFileController.fileType(new byte[]{0,1,2,3})).isInstanceOf(ResponseStatusException.class);
 }
}
