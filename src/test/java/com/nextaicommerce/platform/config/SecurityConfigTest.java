package com.nextaicommerce.platform.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.hamcrest.Matchers.containsString;
import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.AdministrationController;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import com.nextaicommerce.platform.web.MarketplaceCredentialService;
import com.nextaicommerce.platform.sync.AmazonInitializationService;
import com.nextaicommerce.platform.sync.AmazonManualOrderSync;
import com.nextaicommerce.platform.catalog.CatalogController;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.catalog.CatalogImportService;
import com.nextaicommerce.platform.receiving.InventoryController;
import com.nextaicommerce.platform.receiving.InventoryRepository;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuController;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository;
import com.nextaicommerce.platform.orders.OrderController;
import com.nextaicommerce.platform.shipping.ShippingDeskController;
import com.nextaicommerce.platform.shipping.ShippingDeskService;
import com.nextaicommerce.platform.orders.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PageController.class, AdministrationController.class, AccountSelectionController.class, CatalogController.class, InventoryController.class, MarketplaceSkuController.class,OrderController.class,ShippingDeskController.class})
@Import({SecurityConfig.class, SecurityConfigTest.StubConfig.class})
@TestPropertySource(properties={"app.local-admin.email=test@example.com","app.local-admin.password=test-password"})
class SecurityConfigTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @Test
    void loginScriptsArePublicAndNeverSavedAsLoginDestinations() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(get("/js/table-preferences.js?v=test").session(session))
            .andExpect(status().isOk()).andExpect(content().string(containsString("NextAiTablePreferences")));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
    }

    @Test
    void backgroundRequestsDoNotReplaceDocumentLoginDestination() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(get("/app/inventory").servletPath("/app/inventory").session(session)
            .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document"))
            .andExpect(status().is3xxRedirection());
        var original = session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        org.junit.jupiter.api.Assertions.assertNotNull(original);
        mvc.perform(get("/app/collaboration/summaries").session(session)
            .header("Accept", "application/json")).andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertSame(original, session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
    }
    @TestConfiguration
    static class StubConfig {
        @Bean com.nextaicommerce.platform.orders.PackingSlipRepository packingSlips(){
            return org.mockito.Mockito.mock(com.nextaicommerce.platform.orders.PackingSlipRepository.class);
        }
        @Bean MarketplaceCredentialService marketplaceCredentialService(WorkspaceAccessRepository repository) {
            return new MarketplaceCredentialService(repository, new AmazonInitializationService(null), "");
        }
        @Bean AmazonManualOrderSync amazonManualOrderSync(){return new AmazonManualOrderSync(null);}
        @Bean ShippingDeskService shippingDeskService(){return new ShippingDeskService(null,null,null,null,null,null);}
        @Bean WorkspaceAccessRepository workspaceAccessRepository() {
            return new WorkspaceAccessRepository(null) {
                @Override public List<AccountView> listAllAccounts() { return List.of(); }
                @Override public List<AccountView> listAvailableAccounts(String email, boolean superAdmin) { return List.of(); }
                @Override public List<ConnectionView> listConnections(UUID tenantId, String tenantName) { return List.of(); }
                @Override public List<MemberView> listMembers(UUID tenantId) { return List.of(); }
                @Override public List<InvitationView> listInvitations(UUID tenantId) { return List.of(); }
                @Override public int pendingInvitationCount(UUID tenantId) { return 0; }
                @Override public List<SyncStatusView> listCurrentSyncStatuses(UUID tenantId) { return List.of(); }
                @Override public List<SyncJobView> listCurrentSyncJobs(UUID tenantId) { return List.of(); }
                @Override public boolean historicalSyncInProgress(UUID tenantId) { return false; }
                @Override public boolean canAccessAccount(UUID tenantId, String email, boolean superAdmin) { return true; }
                @Override public LogoData accountLogo(UUID tenantId) { return new LogoData(new byte[] {1, 2, 3}, "image/png"); }
                @Override public ConnectionIdentity findConnection(UUID tenantId, UUID connectionId) {
                    return new ConnectionIdentity(connectionId, "AMAZON");
                }
                @Override public void addConnection(UUID tenantId, String channel, String displayName,
                        String sellerIdentifier, String marketplaceIdentifier) {}
            };
        }
        @Bean com.nextaicommerce.platform.catalog.CatalogReadService catalogReadService(CatalogRepository catalog){
            return new com.nextaicommerce.platform.catalog.CatalogReadService(catalog,new PlatformReadCache(false,java.time.Duration.ofSeconds(10),10));
        }
        @Bean CatalogRepository catalogRepository() {
            return new CatalogRepository(null) {
                @Override public List<LocationView> listLocations(UUID tenantId){return List.of();}
                @Override public List<MarketplaceSkuRef> listMarketplaceSkus(UUID tenantId,List<UUID> ids){return List.of();}
                @Override public List<GlobalProductView> listGlobalProducts() { return List.of(); }
                @Override public List<AccountItemView> listAccountItems(UUID tenantId) { return List.of(); }
                @Override public AccountItemPage pageAccountItems(UUID tenantId,String search,int page,int size) { return new AccountItemPage(List.of(),0,0,size); }
                @Override public List<VendorView> listVendors(UUID tenantId) { return List.of(); }
                @Override public List<VendorOfferView> listVendorOffers(UUID tenantId) { return List.of(); }
                @Override public List<VendorOfferView> listVendorOffers(UUID tenantId,List<UUID> itemIds) { return List.of(); }
                @Override public UUID addVendor(UUID tenantId,String name,String code,String currency,
                        java.math.BigDecimal discountRate,java.math.BigDecimal defaultFreightAmount) { return UUID.randomUUID(); }
            };
        }
        @Bean CatalogImportService catalogImportService() {
            return new CatalogImportService(null, null, null);
        }
        @Bean InventoryRepository inventoryRepository() {
            return new InventoryRepository(null) {
                @Override public List<InventoryView> inventory(UUID tenantId) { return List.of(); }
                @Override public ShelfLifePolicy shelfLifePolicy(UUID tenantId) { return new ShelfLifePolicy(10,30); }
                @Override public void saveShelfLifePolicy(UUID tenantId,String actorEmail,int minimumSellableDays,int warningDays) {}
                @Override public void planExpirationAction(UUID tenantId,String actorEmail,UUID itemId,
                        java.time.LocalDate expirationDate,String actionType,String notes) {}
                @Override public LedgerPage ledgerPage(UUID tenantId,String search,int page,int pageSize) {
                    return new LedgerPage(List.of(),0,0,pageSize);
                }
                @Override public LedgerSummary ledgerSummary(UUID tenantId) { return new LedgerSummary(0,0,0); }
            };
        }
        @Bean MarketplaceSkuRepository marketplaceSkuRepository() {
            return new MarketplaceSkuRepository(null) {
                @Override public SkuSummary summary(UUID tenantId, UUID connectionId) {
                    return new SkuSummary(1, 1, 0, 0, 0, 0, 1, 12);
                }
                @Override public SkuPage list(UUID tenantId, UUID connectionId, String search,
                        String status, String sort, String direction, int requestedPage, int pageSize) {
                    return new SkuPage(List.of(), 0, 0, pageSize);
                }
                @Override public Map<String,List<MappingComponentView>> mappingComponents(UUID tenantId,
                        UUID connectionId) { return Map.of(); }
                @Override public Map<String,List<MappingComponentView>> mappingComponents(UUID tenantId,
                        UUID connectionId,List<String> sellerSkus) { return Map.of(); }
                @Override public void saveMapping(UUID tenantId,UUID connectionId,String sellerSku,
                        List<String> productIds,List<String> quantities) {}
            };
        }
        @Bean OrderRepository orderRepository(){return new OrderRepository(null){
            @Override public ReconciliationResult reconcile(UUID tenantId,UUID connectionId){
                return new ReconciliationResult(0,java.math.BigDecimal.ZERO,0,0,0);
            }
            @Override public OrderPage orders(UUID tenantId,UUID connectionId,String filter,String search,int page,int size){return new OrderPage(List.of(),0,0,size);}
        };}
    }

    @Test void loginIsPublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("login"));
    }

    @Test void workspaceRequiresAuthentication() throws Exception {
        mvc.perform(get("/app")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test void workspaceMemberCanLoadTheirAccountLogo() throws Exception {
        mvc.perform(get("/app/platform/accounts/" + TENANT_ID + "/logo")
                .with(user("member@example.com").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
    }

    @Test void logoutPostsWithCsrfAndReturnsToLogin() throws Exception {
        mvc.perform(post("/logout").with(user("admin@example.com")).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test void authenticatedUserCanOpenWorkspace() throws Exception {
        mvc.perform(get("/app").with(user("operator@example.com"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("app"));
    }

    @Test void usersPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/app/users")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test void authenticatedAdminCanOpenUsersPage() throws Exception {
        mvc.perform(get("/app/users").with(user("admin@example.com").roles("PLATFORM_ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("users"));
    }

    @Test void tenantAdminCanManageUsersAndConnections() throws Exception {
        mvc.perform(get("/app/users").with(user("admin@example.com").roles("ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("users"));
        mvc.perform(get("/app/connections").with(user("admin@example.com").roles("ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("connections"));
    }

    @Test void walmartConnectionDoesNotRequireAmazonSellerId() throws Exception {
        mvc.perform(post("/app/connections").with(user("admin@example.com").roles("ADMIN")).with(csrf())
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore")
                .param("channel", "WALMART")
                .param("displayName", "Ibcore Walmart")
                .param("marketplaceIdentifier", "Walmart-US"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/app/connections"));
    }

    @Test void operatorCannotManageUsersOrConnections() throws Exception {
        mvc.perform(get("/app/users").with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/connections").with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().isForbidden());
    }

    @Test void viewerCanOpenOperationalReadPages() throws Exception {
        mvc.perform(get("/app/orders").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("orders"));
        mvc.perform(get("/app/inventory").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("inventory"));
        mvc.perform(get("/app/inventory/ledger").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("inventory-ledger"));
    }

    @Test void viewerCanOpenMarketplaceSkusForSelectedStore() throws Exception {
        mvc.perform(get("/app/marketplace-skus").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore")
                .sessionAttr("selectedStoreId", UUID.randomUUID()).sessionAttr("selectedStoreName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("marketplace-skus"));
    }

    @Test void viewerCannotChangeReceivingData() throws Exception {
        for(String action:java.util.List.of("receive","undo","adjust","close","remove","prepare")){
            mvc.perform(post("/app/receiving/work/"+action+"/"+UUID.randomUUID())
                    .with(user("viewer@example.com").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
            mvc.perform(post("/app/receiving/work/"+action+"/"+UUID.randomUUID())
                    .with(user("owner@example.com").roles("OWNER")))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/app/receiving/start").with(user("viewer@example.com").roles("VIEWER")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/app/inventory/policy").with(user("viewer@example.com").roles("VIEWER")).with(csrf())
                .param("minimumSellableDays","10").param("warningDays","30"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/app/inventory/actions").with(user("viewer@example.com").roles("VIEWER")).with(csrf())
                .param("itemId",UUID.randomUUID().toString()).param("expirationDate","2026-09-15")
                .param("actionType","DISCOUNT"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/app/inventory/receipts").with(user("viewer@example.com").roles("VIEWER")).with(csrf())
                .param("itemId",UUID.randomUUID().toString()).param("quantity","1"))
            .andExpect(status().isForbidden());
    }

    @Test void buyShippingOperationsExcludeViewersAndStoreModeRequiresAnAdministrator() throws Exception {
        mvc.perform(get("/app/orders/112-1234567-1234567/buy-shipping")
                .with(user("viewer@example.com").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/app/connections/"+UUID.randomUUID()+"/buy-shipping").with(csrf())
                .with(user("operator@example.com").roles("OPERATOR"))
                .param("mode","PURCHASE_ENABLED"))
            .andExpect(status().isForbidden());
    }

    @Test void shippingDeskAllowsOperatorsButKeepsPolicyAdministrationRestricted() throws Exception {
        mvc.perform(get("/app/shipping").with(user("viewer@example.com").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/shipping").with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/app/shipping/policy").with(csrf()).with(user("operator@example.com").roles("OPERATOR"))
                .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void viewerCannotEditMarketplaceMappingsButOperatorCan() throws Exception {
        UUID storeId=UUID.randomUUID();
        var request=post("/app/marketplace-skus/mappings").with(csrf())
            .sessionAttr("selectedTenantId",TENANT_ID).sessionAttr("selectedTenantName","Ibcore")
            .sessionAttr("selectedStoreId",storeId).sessionAttr("selectedStoreName","Ibcore")
            .param("sellerSku","IB-TEST-EA").param("productId",UUID.randomUUID().toString())
            .param("quantity","1");
        mvc.perform(request.with(user("viewer@example.com").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(request.with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().is3xxRedirection());
    }

    @Test void onlySuperAdminCanOpenPlatformAccounts() throws Exception {
        mvc.perform(get("/app/platform/accounts").with(user("owner@example.com").roles("OWNER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/platform/accounts").with(user("super@example.com").roles("PLATFORM_ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("accounts"));
    }

    @Test void onlySuperAdminCanOpenGlobalCatalogue() throws Exception {
        mvc.perform(get("/app/platform/catalog").with(user("owner@example.com").roles("OWNER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/platform/catalog").with(user("super@example.com").roles("PLATFORM_ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("global-catalog"));
    }

    @Test void accountMembersCanViewOnlyTheAccountCatalogue() throws Exception {
        mvc.perform(get("/app/catalog").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("account-catalog"))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test void vendorCreationRequiresAndAcceptsCsrfProtection() throws Exception {
        var request=post("/app/catalog/vendors").with(user("admin@example.com").roles("ADMIN"))
            .sessionAttr("selectedTenantId",TENANT_ID).sessionAttr("selectedTenantName","Ibcore")
            .param("name","KEHE").param("currency","USD").param("returnTo","/app/receiving");
        mvc.perform(request).andExpect(status().isForbidden());
        mvc.perform(request.with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/app/receiving"))
            .andExpect(flash().attribute("vendorCreated",true));
    }
}
