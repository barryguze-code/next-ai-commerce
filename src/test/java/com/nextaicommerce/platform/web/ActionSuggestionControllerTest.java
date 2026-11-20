package com.nextaicommerce.platform.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
class ActionSuggestionControllerTest {
 @Test void savesAuthenticatedAuthorAndSelectedAccount(){
  var jdbc=mock(JdbcTemplate.class);var controller=new ActionSuggestionController(jdbc);var session=new MockHttpSession();var tenant=UUID.randomUUID();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);
  assertThat(controller.submit(" Move stock "," Save time ","/app/inventory","Inventory actions",session,new TestingAuthenticationToken("person@example.com","unused"))).containsKey("message");
  verify(jdbc).update(anyString(),eq(tenant),eq("person@example.com"),eq("/app/inventory"),eq("Inventory actions"),eq("Move stock"),eq("Save time"));
 }
 @Test void rejectsMissingAccountAndOverlongText(){
  var jdbc=mock(JdbcTemplate.class);var controller=new ActionSuggestionController(jdbc);
  assertThatThrownBy(()->controller.submit("Action","Reason","/app/inventory","Menu",new MockHttpSession(),new TestingAuthenticationToken("person","unused"))).isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->ActionSuggestionController.checked("x".repeat(161),160)).isInstanceOf(ResponseStatusException.class);
  verifyNoInteractions(jdbc);
 }
 @Test void rejectsUnknownReviewStatus(){
  var jdbc=mock(JdbcTemplate.class);var controller=new ActionSuggestionController(jdbc);
  assertThatThrownBy(()->controller.update(UUID.randomUUID(),"DELETE","",new TestingAuthenticationToken("admin","unused"))).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(jdbc);
 }
}
