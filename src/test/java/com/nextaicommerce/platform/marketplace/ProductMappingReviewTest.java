package com.nextaicommerce.platform.marketplace;
import com.nextaicommerce.platform.web.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProductMappingReviewTest {
 @Test void unauthorizedAccountCannotReadOrWrite(){var jdbc=mock(JdbcTemplate.class);var access=mock(WorkspaceAccessRepository.class);var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,UUID.randomUUID());var actor=new UsernamePasswordAuthenticationToken("user","",List.of());var controller=new ProductMappingReviewController(jdbc,access);assertThrows(ResponseStatusException.class,()->controller.products("",null,null,session,actor));verifyNoInteractions(jdbc);}
 @Test void documentAndProductAreAlwaysBoundToSelectedTenant(){var jdbc=mock(JdbcTemplate.class);var access=mock(WorkspaceAccessRepository.class);UUID tenant=UUID.randomUUID(),item=UUID.randomUUID(),doc=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);var actor=new UsernamePasswordAuthenticationToken("user","",List.of());when(access.canOperateAccount(tenant,"user")).thenReturn(true);var controller=new ProductMappingReviewController(jdbc,access);controller.products("1000015745001",item,doc,session,actor);verify(jdbc).query(contains("l.tenant_id=a.tenant_id"),any(RowMapper.class),eq(tenant),eq(item),eq(item),eq(doc),eq(doc),eq("%1000015745001%"),eq("%1000015745001%"),eq("%1000015745001%"));verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void unauthorizedStoreCannotReadListings(){var jdbc=mock(JdbcTemplate.class);var access=mock(WorkspaceAccessRepository.class);UUID tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,UUID.randomUUID());var actor=new UsernamePasswordAuthenticationToken("user","",List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));when(access.canOperateAccount(tenant,"user")).thenReturn(true);when(access.listAccountOptions("user",false)).thenReturn(List.of());assertThrows(ResponseStatusException.class,()->new ProductMappingReviewController(jdbc,access).listings("sku",UUID.randomUUID(),session,actor));verify(access,never()).findConnection(any(),any());}
}
