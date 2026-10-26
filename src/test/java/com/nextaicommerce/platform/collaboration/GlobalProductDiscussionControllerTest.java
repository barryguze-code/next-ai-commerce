package com.nextaicommerce.platform.collaboration;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
class GlobalProductDiscussionControllerTest {
 static final String BASE="/app/platform/catalog/discussions";
 org.springframework.security.core.Authentication auth(String role){return UsernamePasswordAuthenticationToken.authenticated("admin@example.test","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
 @Test void ordinaryAccountRolesCannotReadCreateReplyReopenOrDownloadGlobalDiscussions()throws Exception{
  var repo=mock(GlobalProductDiscussionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new GlobalProductDiscussionController(repo)).build();String id=UUID.randomUUID().toString();
  for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")){
   mvc.perform(get(BASE+"/reviews/"+id).principal(auth(role))).andExpect(status().isForbidden());
   mvc.perform(post(BASE+"/reviews").principal(auth(role)).param("subjectType","GLOBAL_PRODUCT").param("subjectKey",id).param("message","Hello")).andExpect(status().isForbidden());
   mvc.perform(post(BASE+"/reviews/"+id+"/reply").principal(auth(role)).param("body","Reply")).andExpect(status().isForbidden());
   mvc.perform(post(BASE+"/reviews/"+id+"/reopen").principal(auth(role))).andExpect(status().isForbidden());
   mvc.perform(get(BASE+"/attachments/"+id).principal(auth(role))).andExpect(status().isForbidden());
  }verifyNoInteractions(repo);
 }
 @Test void subjectAndCreationUseVerifiedGlobalIdentityAndAuthenticatedActorOnly()throws Exception{
  var repo=mock(GlobalProductDiscussionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new GlobalProductDiscussionController(repo)).build();UUID product=UUID.randomUUID(),discussion=UUID.randomUUID();
  when(repo.subject("admin@example.test",product)).thenReturn(List.of());when(repo.create(eq("admin@example.test"),eq(product),eq("Public note"),any())).thenReturn(discussion);
  mvc.perform(get(BASE+"/subject").principal(auth("PLATFORM_ADMIN")).param("subjectType","GLOBAL_PRODUCT").param("subjectKey",product.toString()).param("tenantId",UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(post(BASE+"/reviews").principal(auth("PLATFORM_ADMIN")).param("subjectType","GLOBAL_PRODUCT").param("subjectKey",product.toString()).param("message","Public note").param("actor","someone-else")).andExpect(status().isOk()).andExpect(jsonPath("$.reviewId").value(discussion.toString()));
  verify(repo).subject("admin@example.test",product);verify(repo).create(eq("admin@example.test"),eq(product),eq("Public note"),eq(List.of()));
  mvc.perform(post(BASE+"/reviews").principal(auth("PLATFORM_ADMIN")).param("subjectType","CATALOG").param("subjectKey",product.toString()).param("message","Private record")).andExpect(status().isBadRequest());
  mvc.perform(get(BASE+"/reviews/"+discussion).principal(auth("PLATFORM_ADMIN")).param("messageType","PRIVATE_NOTE")).andExpect(status().isBadRequest());
 }
 @Test void mixedRealmMissingIdsAndInactiveAdminFailuresHaveNoTenantFallback()throws Exception{
  var repo=mock(GlobalProductDiscussionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new GlobalProductDiscussionController(repo)).build();UUID id=UUID.randomUUID();
  when(repo.conversation("admin@example.test",id)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));when(repo.attachment("admin@example.test",id)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));when(repo.members("admin@example.test")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  mvc.perform(get(BASE+"/reviews/"+id).principal(auth("PLATFORM_ADMIN"))).andExpect(status().isNotFound());mvc.perform(get(BASE+"/attachments/"+id).principal(auth("PLATFORM_ADMIN"))).andExpect(status().isNotFound());mvc.perform(get(BASE+"/members").principal(auth("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var actual=new GlobalProductDiscussionRepository(jdbc);assertThatThrownBy(()->actual.members("inactive@example.test")).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
 }
 @Test void attachmentValidationAndSummaryBoundsReuseExistingLimits()throws Exception{
  var repo=mock(GlobalProductDiscussionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new GlobalProductDiscussionController(repo)).build();UUID id=UUID.randomUUID();
  mvc.perform(multipart(BASE+"/reviews").file(new MockMultipartFile("attachments","unsafe.html","text/html","<script>".getBytes())).principal(auth("PLATFORM_ADMIN")).param("subjectType","GLOBAL_PRODUCT").param("subjectKey",id.toString()).param("message","File")).andExpect(status().isBadRequest());
  var request=get(BASE+"/summaries").principal(auth("PLATFORM_ADMIN")).param("entityType","GLOBAL_PRODUCT");for(int i=0;i<251;i++)request.param("entityId",UUID.randomUUID().toString());mvc.perform(request).andExpect(status().isBadRequest());verifyNoInteractions(repo);
 }
 @Test void closeReplyAndReopenKeepGlobalRouteAndActor()throws Exception{
  var repo=mock(GlobalProductDiscussionRepository.class);var mvc=MockMvcBuilders.standaloneSetup(new GlobalProductDiscussionController(repo)).build();UUID id=UUID.randomUUID();when(repo.reply(eq("admin@example.test"),eq(id),isNull(),eq("CLOSED"),any())).thenReturn(id);when(repo.reopen("admin@example.test",id)).thenReturn(id);
  mvc.perform(post(BASE+"/reviews/"+id+"/reply").principal(auth("PLATFORM_ADMIN")).param("status","CLOSED")).andExpect(status().isOk());mvc.perform(post(BASE+"/reviews/"+id+"/reopen").principal(auth("PLATFORM_ADMIN"))).andExpect(status().isOk());verify(repo).reopen("admin@example.test",id);
 }
}
