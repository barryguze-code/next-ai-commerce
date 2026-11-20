package com.nextaicommerce.platform.orders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
class RecordPictureControllerTest {
 @Test void requiresSelectedAccountBeforeReading(){
  var controller=new RecordPictureController(null,null,mock(WorkspaceAccessRepository.class));
  assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.get("VENDOR",UUID.randomUUID(),new MockHttpSession(),new UsernamePasswordAuthenticationToken("user",""))).getStatusCode().value());
 }
 @Test void rejectsViewerWritesBeforeSaving() {
  var access=mock(WorkspaceAccessRepository.class);var tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);
  when(access.canAccessAccount(tenant,"user",false)).thenReturn(true);
  var controller=new RecordPictureController(null,null,access);
  assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.upload("VENDOR",UUID.randomUUID(),new MockMultipartFile("image",new byte[]{1}),session,new UsernamePasswordAuthenticationToken("user",""))).getStatusCode().value());
 }
 @Test void rejectsInvalidBytesBeforeDatabaseWrite() throws Exception {
  var access=mock(WorkspaceAccessRepository.class);var tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);
  when(access.canAccessAccount(tenant,"user",false)).thenReturn(true);when(access.canOperateAccount(tenant,"user")).thenReturn(true);
  var response=new RecordPictureController(null,null,access).upload("VENDOR",UUID.randomUUID(),new MockMultipartFile("image","bad.png","image/png",new byte[]{1}),session,new UsernamePasswordAuthenticationToken("user",""));
  assertEquals(400,response.getStatusCode().value());
 }
}
