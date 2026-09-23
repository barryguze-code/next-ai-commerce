package com.nextaicommerce.platform.orders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
class MappingPictureControllerTest {
 @Test void uploadsComponentPictureWithinSelectedAccount() throws Exception {
  var pictures=mock(OrderPictureController.class);var catalog=mock(CatalogRepository.class);
  var session=new MockHttpSession();var auth=mock(Authentication.class);when(auth.getName()).thenReturn("operator");
  var context=new OrderPictureController.Context(UUID.randomUUID(),UUID.randomUUID(),"SKU","ASIN","US");
  when(pictures.skuContext(session,"SKU",auth)).thenReturn(context);
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
  var bytes=out.toByteArray();var id=UUID.randomUUID();
  var response=new MappingPictureController(pictures,catalog).update("SKU",id,false,new MockMultipartFile("image","item.png","image/png",bytes),session,auth);
  assertEquals(200,response.getStatusCode().value());
  verify(catalog).saveProductImage(context.tenant(),"operator",id,"image/png","item.png",bytes);
 }
 @Test void rejectsInvalidImageWithoutSaving(){
  var pictures=mock(OrderPictureController.class);var catalog=mock(CatalogRepository.class);
  var session=new MockHttpSession();var auth=mock(Authentication.class);
  var controller=new MappingPictureController(pictures,catalog);
  var response=controller.update("SKU",UUID.randomUUID(),false,new MockMultipartFile("image","bad.png","image/png",new byte[]{1,2,3}),session,auth);
  assertEquals(400,response.getStatusCode().value());verifyNoInteractions(catalog);
  verify(pictures).skuContext(session,"SKU",auth);verifyNoMoreInteractions(pictures);
 }
 @Test void syncUsesSelectedSkuAndKeepsImageWhenAmazonHasNone() throws Exception {
  var pictures=mock(OrderPictureController.class);var catalog=mock(CatalogRepository.class);
  var session=new MockHttpSession();var auth=mock(Authentication.class);
  var context=new OrderPictureController.Context(UUID.randomUUID(),UUID.randomUUID(),"SKU","ASIN","US");
  when(pictures.skuContext(session,"SKU",auth)).thenReturn(context);
  when(pictures.amazonImage(context)).thenThrow(new IllegalArgumentException("No Amazon image."));
  var response=new MappingPictureController(pictures,catalog).update("SKU",UUID.randomUUID(),true,null,session,auth);
  assertEquals(400,response.getStatusCode().value());verifyNoInteractions(catalog);
 }
}
