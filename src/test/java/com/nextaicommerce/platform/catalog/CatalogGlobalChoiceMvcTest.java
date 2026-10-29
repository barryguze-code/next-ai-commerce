package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogGlobalChoiceMvcTest {
 org.springframework.security.core.Authentication auth(String role){return UsernamePasswordAuthenticationToken.authenticated("test@example.test","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
 MockHttpSession session(UUID tenant){var s=new MockHttpSession();s.setAttribute("selectedTenantId",tenant);return s;}
 CatalogController controller(CatalogRepository repo){var c=new CatalogController(repo,null);var access=mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);when(access.canOperateAccount(any(),any())).thenReturn(true);org.springframework.test.util.ReflectionTestUtils.setField(c,"workspaceAccess",access);return c;}
 @Test void globalLookupRequiresAnEditorAndUsesOnlySessionTenant()throws Exception{
  UUID tenant=UUID.randomUUID();var repo=mock(CatalogRepository.class);when(repo.searchGlobalProducts(tenant,"Cheese")).thenReturn(List.of());
  var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(get("/app/catalog/product-search").param("scope","global").param("q","Cheese").session(session(tenant)).principal(auth("VIEWER"))).andExpect(status().isForbidden());verifyNoInteractions(repo);
  mvc.perform(get("/app/catalog/product-search").param("scope","global").param("q","Cheese").param("tenantId",UUID.randomUUID().toString()).session(session(tenant)).principal(auth("OPERATOR"))).andExpect(status().isOk()).andExpect(content().json("[]"));verify(repo).searchGlobalProducts(tenant,"Cheese");
 }
 @Test void selectedAccountMustBeAuthorizedBeforePrivateGlobalCodeLookup()throws Exception{
  UUID tenant=UUID.randomUUID();var repo=mock(CatalogRepository.class);var c=controller(repo);
  var access=(com.nextaicommerce.platform.web.WorkspaceAccessRepository)org.springframework.test.util.ReflectionTestUtils.getField(c,"workspaceAccess");
  when(access.canOperateAccount(tenant,"test@example.test")).thenReturn(false);
  var mvc=MockMvcBuilders.standaloneSetup(c).build();
  mvc.perform(get("/app/platform/catalog").session(session(tenant)).principal(auth("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
  mvc.perform(get("/app/catalog/product-search").param("scope","global").param("q","342934").session(session(tenant)).principal(auth("OWNER"))).andExpect(status().isForbidden());
  verifyNoInteractions(repo);
 }
 @Test void canonicalImageAliasNeverCallsPrivatePictureLookupAndRejectsViewer()throws Exception{
  UUID id=UUID.randomUUID(),tenant=UUID.randomUUID();var repo=mock(CatalogRepository.class);when(repo.sharedGlobalImage(id)).thenReturn(new CatalogRepository.ProductImage(new byte[]{1,2},"image/png"));
  var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(get("/app/catalog/global-products/"+id+"/image").session(session(tenant)).principal(auth("VIEWER"))).andExpect(status().isForbidden());verifyNoInteractions(repo);
  mvc.perform(get("/app/catalog/global-products/"+id+"/image").session(session(tenant)).principal(auth("OWNER"))).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2}));verify(repo).sharedGlobalImage(id);verify(repo,never()).productImage(any(),any());
 }
 @Test void explicitPostUsesAuthenticatedAccountAndPreservesSelectedRevision()throws Exception{
  UUID tenant=UUID.randomUUID(),global=UUID.randomUUID(),item=UUID.randomUUID();var repo=mock(CatalogRepository.class);
  when(repo.addProductToAccount(eq(tenant),eq("test@example.test"),any())).thenReturn(new CatalogRepository.AccountAddition(item,true,"079834140503"));
  var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(post("/app/catalog/products").session(session(tenant)).principal(auth("OWNER")).param("globalProductId",global.toString()).param("globalRevision","revision").param("accountSku","ACCOUNT-ONLY").param("tenantId",UUID.randomUUID().toString()))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app/catalog?q=079834140503#catalog-item-"+item));
  verify(repo).addProductToAccount(eq(tenant),eq("test@example.test"),argThat(d->global.equals(d.globalProductId())&&"revision".equals(d.revision())&&"ACCOUNT-ONLY".equals(d.accountSku())));
 }
 @Test void mappingJsonUsesSameAccountAdditionAndReturnsOnlyConfirmedAccountIdentity()throws Exception{
  UUID tenant=UUID.randomUUID(),global=UUID.randomUUID(),item=UUID.randomUUID();var repo=mock(CatalogRepository.class);
  when(repo.addProductToAccount(eq(tenant),eq("test@example.test"),any())).thenReturn(new CatalogRepository.AccountAddition(item,true,"079834140503"));
  var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(post("/app/catalog/products").accept("application/json").session(session(tenant)).principal(auth("VIEWER"))).andExpect(status().isForbidden());verifyNoInteractions(repo);
  mvc.perform(post("/app/catalog/products").accept("application/json").session(session(tenant)).principal(auth("OWNER")).param("globalProductId",global.toString()).param("globalRevision","revision").param("tenantId",UUID.randomUUID().toString()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(item.toString())).andExpect(jsonPath("$.created").value(true));
  verify(repo).addProductToAccount(eq(tenant),eq("test@example.test"),argThat(d->global.equals(d.globalProductId())&&"revision".equals(d.revision())));
  when(repo.addProductToAccount(eq(tenant),any(),any())).thenThrow(new IllegalArgumentException("Choose again."));
  mvc.perform(post("/app/catalog/products").accept("application/json").session(session(tenant)).principal(auth("OWNER"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Choose again."));
 }

 @Test void globalCatalogueSearchIsForwardedToTheServer()throws Exception{
  var repo=mock(CatalogRepository.class);UUID tenant=UUID.randomUUID();when(repo.pageGlobalProducts(tenant,"339807",0,25,"product","asc")).thenReturn(new CatalogRepository.GlobalProductPage(List.of(),0,0,25));var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(get("/app/platform/catalog").param("q","339807").session(session(tenant)).principal(auth("PLATFORM_ADMIN")))
   .andExpect(status().isOk()).andExpect(model().attribute("query","339807"));
  verify(repo).pageGlobalProducts(tenant,"339807",0,25,"product","asc");verify(repo,never()).listGlobalProducts();
  mvc.perform(get("/app/platform/catalog").session(session(UUID.randomUUID())).principal(auth("OWNER"))).andExpect(status().isForbidden());
 }

 @Test void newProductDetailsReachTheExistingAccountAddTransaction()throws Exception{
  var repo=mock(CatalogRepository.class);UUID tenant=UUID.randomUUID();when(repo.addProductToAccount(any(),any(),any())).thenReturn(new CatalogRepository.AccountAddition(UUID.randomUUID(),true,"079834140503"));var mvc=MockMvcBuilders.standaloneSetup(controller(repo)).build();
  mvc.perform(post("/app/catalog/products").accept("application/json").session(session(tenant)).principal(auth("OWNER")).param("name","New product").param("identifier","079834140503").param("size","12 oz").param("unitOfMeasure","EA")).andExpect(status().isOk());
  verify(repo).addProductToAccount(eq(tenant),eq("test@example.test"),argThat(d->"12 oz".equals(d.size())&&"EA".equals(d.unitOfMeasure())));
 }
 @Test void globalSearchSharesOnlyThePublicPictureEndpointAndCanonicalFields()throws Exception{
  var repo=mock(CatalogRepository.class);var pictures=mock(CatalogPictureService.class);UUID tenant=UUID.randomUUID(),product=UUID.randomUUID();var choice=new CatalogRepository.GlobalChoice(product,"Shared product","Brand","UPC","079834140503","12 oz","EA",false,"revision",null,null);when(repo.searchGlobalProducts(tenant,"079834140503")).thenReturn(List.of(choice));when(pictures.publicProductFallback(tenant,"test@example.test",product)).thenReturn("https://m.media-amazon.com/images/I/public.jpg");var controller=controller(repo);org.springframework.test.util.ReflectionTestUtils.setField(controller,"catalogPictures",pictures);var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(get("/app/catalog/product-search").param("scope","global").param("q","079834140503").session(session(tenant)).principal(auth("OWNER"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].imageUrl").value("/app/catalog/global-products/"+product+"/image")).andExpect(jsonPath("$[0].size").value("12 oz")).andExpect(jsonPath("$[0].unitOfMeasure").value("EA")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("media-amazon"))));
  verifyNoInteractions(pictures);
 }

 @Test void unionedLoginRoleCannotAddOrEditInsideAViewerAccount()throws Exception{
  var repo=mock(CatalogRepository.class);var c=controller(repo);var access=(com.nextaicommerce.platform.web.WorkspaceAccessRepository)org.springframework.test.util.ReflectionTestUtils.getField(c,"workspaceAccess");when(access.canOperateAccount(any(),any())).thenReturn(false);var mvc=MockMvcBuilders.standaloneSetup(c).build();var own=session(UUID.randomUUID());
  mvc.perform(post("/app/catalog/products").accept("application/json").principal(auth("OWNER")).session(own).param("name","New")).andExpect(status().isForbidden());
  mvc.perform(post("/app/catalog/products/"+UUID.randomUUID()).principal(auth("OWNER")).session(own).param("itemCost","2")).andExpect(status().isForbidden());
  mvc.perform(get("/app/catalog/product-defaults").principal(auth("OWNER")).session(own).param("globalProductId",UUID.randomUUID().toString()).param("vendorId",UUID.randomUUID().toString())).andExpect(status().isForbidden());verifyNoInteractions(repo);
 }
 @Test void defaultsAndExplicitVendorEditUseOnlySelectedAccount()throws Exception{
  var repo=mock(CatalogRepository.class);var c=controller(repo);var mvc=MockMvcBuilders.standaloneSetup(c).build();UUID tenant=UUID.randomUUID(),global=UUID.randomUUID(),vendor=UUID.randomUUID(),item=UUID.randomUUID();when(repo.entryDefaults(tenant,global,vendor)).thenReturn(new CatalogRepository.EntryDefaults("OWN","339807",new java.math.BigDecimal("4.25"),"USD"));
  mvc.perform(get("/app/catalog/product-defaults").principal(auth("OWNER")).session(session(tenant)).param("globalProductId",global.toString()).param("vendorId",vendor.toString()).param("tenantId",UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.vendorItemCode").value("339807")).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(post("/app/catalog/products/"+item).principal(auth("OWNER")).session(session(tenant)).param("displayName","Own name").param("accountSku","OWN").param("itemCost","4.25").param("costVendorId",vendor.toString()).param("vendorItemCode","339807")).andExpect(status().is3xxRedirection());
  verify(repo).updateAccountProductVendor(tenant,item,"Own name","OWN","ACTIVE",new java.math.BigDecimal("4.25"),vendor,"339807","test@example.test");
 }

}
