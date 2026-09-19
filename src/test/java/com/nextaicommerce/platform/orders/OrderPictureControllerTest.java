package com.nextaicommerce.platform.orders;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

class OrderPictureControllerTest {
 @Test void validatesActualImageFormatInsteadOfBrowserMimeType() throws Exception {
  var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);
  assertThat(OrderPictureController.imageType(output.toByteArray())).isEqualTo("image/png");
  assertThatThrownBy(()->OrderPictureController.imageType("<svg onload='bad()'/>".getBytes())).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsOversizeAndEmptyFiles(){
  assertThatThrownBy(()->OrderPictureController.imageType(new byte[0])).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->OrderPictureController.imageType(new byte[5_000_001])).isInstanceOf(IllegalArgumentException.class);
 }
}
