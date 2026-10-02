package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.web.MemberAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentMembershipFilterTest {
    MemberAccessService service=mock(MemberAccessService.class);
    UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    MockHttpServletRequest request=new MockHttpServletRequest();
    MockHttpServletResponse response=new MockHttpServletResponse();
    CurrentMembershipFilter filter;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        ObjectProvider<MemberAccessService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        filter=new CurrentMembershipFilter(provider);
        request.setServletPath("/app/orders");request.getSession().setAttribute("selectedTenantId",tenant);request.getSession().setAttribute("selectedStoreId",store);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("member@example.test","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    @Test void revokedMembershipCannotReuseSession() throws Exception {
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("Must not reach controller");});
        assertThat(response.getStatus()).isEqualTo(403);assertThat(request.getSession().getAttribute("selectedTenantId")).isNull();
    }
    @Test void revokedStoreCannotReuseSession() throws Exception {
        when(service.current(tenant,"member@example.test")).thenReturn(new MemberAccessService.Access("ADMIN",true,Set.of()));
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("Must not reach controller");});
        assertThat(response.getStatus()).isEqualTo(403);assertThat(request.getSession().getAttribute("selectedStoreId")).isNull();
    }
    @Test void roleDowngradeAppliesBeforeAuthorization() throws Exception {
        when(service.current(tenant,"member@example.test")).thenReturn(new MemberAccessService.Access("VIEWER",true,Set.of(store)));
        filter.doFilter(request,response,(req,res)->assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER"));
        assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void restrictedUserCannotUseAllStoresView() throws Exception {
        request.getSession().removeAttribute("selectedStoreId");
        when(service.current(tenant,"member@example.test")).thenReturn(new MemberAccessService.Access("VIEWER",true,Set.of(store)));
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("Must select an assigned store");});assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void accountSelectionRemainsAvailable() throws Exception {
        request.setServletPath("/app/select-account");filter.doFilter(request,response,(req,res)->{});verifyNoInteractions(service);
    }
}
