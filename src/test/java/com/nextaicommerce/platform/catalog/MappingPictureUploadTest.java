package com.nextaicommerce.platform.catalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class MappingPictureUploadTest {
 @Test void savesForSelectedAccountWithoutRedirectingMapping() throws Exception {
  var repo=mock(CatalogRepository.class);var controller=new CatalogController(repo,null);
  var session=new MockHttpSession();var tenant=UUID.randomUUID();var item=UUID.randomUUID();session.setAttribute("selectedTenantId",tenant);
  var auth=mock(Authentication.class);when(auth.getName()).thenReturn("user");var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);byte[] bytes=out.toByteArray();
  var response=controller.uploadMappingProductImage(item,auth,session,new MockMultipartFile("image","photo.png","image/png",bytes));
  assertThat(response.getStatusCode().value()).isEqualTo(200);
  verify(repo).saveProductImage(tenant,"user",item,"image/png","photo.png",bytes);
 }
 @Test void rejectsUnsupportedUpload() throws Exception {
  var repo=mock(CatalogRepository.class);var controller=new CatalogController(repo,null);
  assertThat(controller.uploadMappingProductImage(UUID.randomUUID(),null,new MockHttpSession(),new MockMultipartFile("image","x.svg","image/svg+xml",new byte[]{1})).getStatusCode().value()).isEqualTo(400);
  verifyNoInteractions(repo);
 }
}
