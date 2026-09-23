package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.MemberView;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.SyncJobView;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.SyncStatusView;
import com.nextaicommerce.platform.catalog.CatalogRepository.AccountItemView;
import com.nextaicommerce.platform.catalog.CatalogRepository.GlobalProductView;
import com.nextaicommerce.platform.catalog.CatalogRepository.VendorView;
import com.nextaicommerce.platform.catalog.CatalogRepository.VendorOfferView;
import com.nextaicommerce.platform.receiving.ReceivingRepository.SessionView;
import com.nextaicommerce.platform.receiving.ReceivingRepository.DocumentView;
import com.nextaicommerce.platform.receiving.ReceivingRepository.ReceiveLineView;
import com.nextaicommerce.platform.receiving.InventoryRepository.InventoryView;
import com.nextaicommerce.platform.receiving.InventoryRepository.LedgerPage;
import com.nextaicommerce.platform.receiving.InventoryRepository.LedgerView;
import com.nextaicommerce.platform.receiving.InventoryRepository.ShelfLifePolicy;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.SkuPage;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.SkuSummary;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.SkuView;
import com.nextaicommerce.platform.orders.OrderRepository.OrderItemView;
import com.nextaicommerce.platform.orders.OrderRepository.OrderPage;
import com.nextaicommerce.platform.orders.OrderRepository.OrderView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class TemplateRenderingTest {
    @Test void inventoryPublicationStatusShowsSimulationAndZeroObservedQuantity(){
        var c=webContext();c.setVariable("publicationMode","DRY_RUN");c.setVariable("publications",List.of(Map.of("seller_sku","TEST-SKU","marketplace_id","US","desired_quantity",0,"observed_quantity",0,"status","DRY_RUN","attempts",0,"last_error","No request sent")));
        assertThat(templateEngine().process("inventory-publications",c)).contains("Local simulation","TEST-SKU","No request sent");
    }

    @Test
    void combinedReceivingRendersIndependentDocumentsAndCompactDrawer() {
        var c=webContext();UUID document=UUID.randomUUID(),session=UUID.randomUUID(),line=UUID.randomUUID();
        var d=new com.nextaicommerce.platform.receiving.ReceivingWorkflowRepository.Document(document,session,"QA supplier",
            "INVOICE","INV-101","invoice.csv",java.time.LocalDate.now(),Instant.now(),"USD",
            new BigDecimal("10"),new BigDecimal("3"),new BigDecimal("7"),false,false,true,null,UUID.randomUUID());
        var l=new com.nextaicommerce.platform.receiving.ReceivingWorkflowRepository.WorkLine(line,document,session,UUID.randomUUID(),
            "Yogurt & cream","QA-100",new BigDecimal("10"),new BigDecimal("3"),new BigDecimal("7"),BigDecimal.ONE,
            new BigDecimal("2"),"USD",true,false,1,BigDecimal.ZERO,BigDecimal.ZERO,UUID.randomUUID());
        c.setVariables(Map.ofEntries(Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","QA only"),Map.entry("signedInEmail","qa@example.test"),Map.entry("roleLabel","Owner"),
            Map.entry("workDocuments",List.of(d)),Map.entry("workLines",List.of(l)),Map.entry("locations",List.of())));
        c.setVariable("workImages",Map.of(l.productId(),"/app/catalog/products/"+l.productId()+"/image"));
        assertThat(templateEngine().process("receiving-work",c)).contains("Receive together","INV-101","Partially received","data-picture-item=","data-image=","Receive overshipped items",
            "Yogurt &amp; cream","Review receipts","receiving-drawer","Back to checklist","data-table-widget=\"receiving-work\"")
            .doesNotContain("Reopen invoice line");
        // Exercise the populated parent page too: empty-list fixtures never evaluate d.statusTone().
        c.setVariable("sessions",List.of());c.setVariable("vendors",List.of());c.setVariable("query","");
        assertThat(templateEngine().process("receiving",c))
            .contains("Invoices &amp; packing lists","INV-101","data-receiving-status=\"partial\"","Partially received")
            .doesNotContain("src=\"/js/receiving-work.js");
    }
    @Test
    void ordersRenderAsPagedReadableOperationalQueue() {
        var context=webContext();var orderId=UUID.randomUUID();var day=java.time.LocalDate.of(2026,8,30);
        var order=new OrderView(orderId,"113-1234567-1234567",Instant.parse("2026-08-30T17:00:00Z"),day,
            "Unshipped","MFN","LIVE","NEEDS_MAPPING",1,2,"USD",new BigDecimal("42.50"),
            "IB-KEHE-63792-2xEA","IB-KEHE-63792-2xEA",1,BigDecimal.ZERO,
            "https://images.example.test/product.jpg","ATVPDKIKX0DER");
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","Ibcore LLC"),Map.entry("signedInEmail","barry.guze@gmail.com"),
            Map.entry("roleLabel","Super Admin"),Map.entry("orders",List.of(order)),
            Map.entry("orderPage",new OrderPage(List.of(order),1,0,25)),
            Map.entry("orderTabs",List.of(
                new com.nextaicommerce.platform.orders.OrderRepository.OrderTab("ALL","All",false,12,15),
                new com.nextaicommerce.platform.orders.OrderRepository.OrderTab("UNSHIPPED","Unshipped",false,2,3),
                new com.nextaicommerce.platform.orders.OrderRepository.OrderTab("WAITING_FOR_PICKUP","Waiting for pickup",false,0,0),
                new com.nextaicommerce.platform.orders.OrderRepository.OrderTab("NEEDS_MAPPING","Unmapped",true,1,1))),
            Map.entry("itemsByOrder",Map.of(order.amazonOrderId(),List.of(new OrderItemView(UUID.randomUUID(),
                order.firstSku(),"B012345678","Test product",2,0,"MAPPED","63792 × 2",BigDecimal.ZERO,
                "https://images.example.test/product.jpg",new BigDecimal("12"),new BigDecimal("42.50"),
                new BigDecimal("6.99"),"USD",new BigDecimal("39.95"),"USD",Instant.parse("2026-09-10T16:00:00Z"))))),
            Map.entry("soldTotals",Map.of()),
            Map.entry("fourWeekSales",Map.of(order.firstSku(),"1 | 2 | 3 | 4")),
            Map.entry("todayOrders",1L),Map.entry("todaySales",new BigDecimal("42.50")),
            Map.entry("sales30Days",new BigDecimal("420.50")),Map.entry("liveCount",1L),
            Map.entry("historicalCount",0L),Map.entry("selectedStatus","ALL"),Map.entry("query",""),
            Map.entry("orderSyncFresh",true),Map.entry("orderSyncActive",false),Map.entry("orderSyncAvailable",false),
            Map.entry("lastOrderSync","Last Amazon order check · Sep 5 · 7:23 AM"),
            Map.entry("orderTime",java.time.format.DateTimeFormatter.ofPattern("MMM d · h:mm a").withZone(java.time.ZoneId.of("America/Los_Angeles"))),
            Map.entry("orderStreamKey","test-key")
        ));
        assertThat(templateEngine().process("orders",context)).contains("Today’s orders","Today’s sales","Sales · 30 days","Live",
            "113-1234567-1234567","Unshipped","/js/mapping-widget.js","/css/mapping-widget.css","Buy shipping label","Profit · margin · markup",
            "https://images.example.test/product.jpg","sellercentral.amazon.com/orders-v3/order/","Available",
            "Item Sales","USD 42.50","Buy Box","USD 39.95","Shipping","USD 6.99","SKU mapping","63792 × 2",
            "Showing 1–1 of 1","data-order-stream","Sync now","Last Amazon order check",
            "New Amazon orders just arrived","Open this order in Seller Central","Open the Amazon product page",
            "/images/platform/table/amazon.com-logo.png","amazon.com/dp/B012345678","Orders/Units","Unmapped","Waiting for pickup",
            "data-copy-sku=","has-issues","aria-current=\"page\"","12 orders / 15 units","status=UNSHIPPED").doesNotContain("order-day",">Historical<","Reporting only","status=PENDING","Stock readiness");
        context.setVariable("selectedStatus","WAITING_FOR_PICKUP");
        context.setVariable("pickupOverrides",java.util.Set.of(order.amazonOrderId()));
        context.setVariable("pickupEligibleOrders",java.util.Set.of(order.amazonOrderId()));
        assertThat(templateEngine().process("orders",context)).contains("Platform only","Undo platform pickup mark","/pickup-override");
        assertThat(templateEngine().process("orders",context)).contains("aria-current=\"page\"").doesNotContain("order-pickup-note");
    }

    @Test
    void catalogueTemplatesRenderRoleSpecificActions() {
        var engine = templateEngine();
        var context = webContext();
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("canManageUsers", true), Map.entry("canEditCatalog", true),
            Map.entry("isSuperAdmin", true), Map.entry("selectedAccountName", "Ibcore LLC"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("items", List.of(new AccountItemView(UUID.randomUUID(), UUID.randomUUID(),
                "Test Product", "Test Brand", "KeHE", "53383", "012345678905", "IB-TEST", 1,
                new BigDecimal("12.50"), "USD", true, "ACTIVE","COMPLETE"))),
            Map.entry("catalogPage",new com.nextaicommerce.platform.catalog.CatalogRepository.AccountItemPage(List.of(),1,0,50)),
            Map.entry("query",""),
            Map.entry("vendors", List.of(new VendorView(UUID.randomUUID(), "KeHE", "KEHE", "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "ACTIVE"))),
            Map.entry("offersByItem",Map.of())
        ));
        assertThat(engine.process("account-catalog", context)).contains(
            "Account Catalogue", "Upload catalogue", "Set price", "Test Product", "Vendor item code", "53383",
            "id=\"location-dialog\"", "Storage locations", "Add new location");

        var globalContext = webContext();
        globalContext.setVariables(Map.ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("canManageUsers", true), Map.entry("canEditCatalog", true),
            Map.entry("isSuperAdmin", true), Map.entry("selectedAccountName", "Ibcore LLC"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("products", List.of(new GlobalProductView(UUID.randomUUID(), "Test Product",
                "Test Brand", "KeHE", "53383", "012345678905", "EA", true, "ACTIVE")))
        ));
        assertThat(engine.process("global-catalog", globalContext)).contains(
            "Global Catalogue", "Product identity only", "Vendor item code", "53383", "012345678905");
    }

    @Test
    void vendorAndReceivingTemplatesRenderOperationalFlows() {
        var vendor = new VendorView(UUID.randomUUID(), "KeHE", "KEHE", "USD",
            new BigDecimal("18"), new BigDecimal("125"), 20, "ACTIVE");
        var base = Map.<String,Object>ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("canManageUsers", true), Map.entry("canEditCatalog", true),
            Map.entry("isSuperAdmin", true), Map.entry("selectedAccountName", "Ibcore LLC"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("vendors", List.of(vendor)), Map.entry("catalogSuccess", "")
        );
        var vendorContext=webContext(); vendorContext.setVariables(base);
        assertThat(templateEngine().process("vendors",vendorContext)).contains("Vendor directory","Default freight amount","USD 125");
        UUID sessionId=UUID.randomUUID();
        var receivingContext=webContext();receivingContext.setVariables(base);receivingContext.setVariable("sessions",
            List.of(new SessionView(sessionId,"Pallet 1","DRAFT","USD",BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,2,14)));
        assertThat(templateEngine().process("receiving",receivingContext)).contains(
            "Upload receiving documents","Receive selected together","Additional landed costs","name=\"files\"","multiple");
        var workspace=webContext();workspace.setVariables(base);workspace.setVariable("receivingSession",
            new SessionView(sessionId,"Pallet 1","MATCHING","USD",new BigDecimal("10.0000"),new BigDecimal("0.0000"),new BigDecimal("0.0000"),1,3));
        workspace.setVariable("documents",List.of(new DocumentView(UUID.randomUUID(),sessionId,"KeHE","INVOICE","invoice.xlsx",
            "4784240",java.time.LocalDate.of(2026,8,11),"EXTRACTED",3,new BigDecimal("50"),"PO-12345678")));
        workspace.setVariable("receiveLines",List.of(new ReceiveLineView(UUID.randomUUID(),UUID.randomUUID(),
            "Ski Queen Gjetost","Ski Queen","https://images.example.test/gjetost.jpg","53383","012345678905",new BigDecimal("2"),new BigDecimal("12"),
            new BigDecimal("24"),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("24"),new BigDecimal("5.85"),
            "USD",true,"OPEN","NONE","NEW_PRODUCT",0)));
        workspace.setVariable("credits",List.of());workspace.setVariable("completedLines",0L);
        workspace.setVariable("recentExpirationDates",List.of(java.time.LocalDate.of(2026,9,4),java.time.LocalDate.of(2026,9,11)));
        workspace.setVariable("suggestedExpirationDate",java.time.LocalDate.of(2026,9,4));
        assertThat(templateEngine().process("receiving-workspace",workspace)).contains(
            "Receive products","PO-12345678","https://images.example.test/gjetost.jpg","Ski Queen",
            ">Shipped</small>",">Received</small>",">Remaining</small>",
            "Search or scan product, UPC, or item code","Batch 1","Short shipped","Mispicked",
            "Over shipped","Extra units received at $0 cost","name=\"files\"","multiple","USD 10.00","USD 0.00",
            "Recent expiration dates","value=\"2026-09-04\"","applyExpirationDate")
            .doesNotContain("24.0000","10.0000","0.0000","class=\"receive-state\"");

        var inventory=webContext();inventory.setVariables(base);
        inventory.setVariable("today",java.time.LocalDate.of(2026,8,27));inventory.setVariable("policy",new ShelfLifePolicy(10,30));
        inventory.setVariable("sellableEachUnits","24");inventory.setVariable("blockedEachUnits","0");inventory.setVariable("soonEachUnits","24");
        inventory.setVariable("blockedPositions",0);inventory.setVariable("soonPositions",1);inventory.setVariable("healthyPositions",0);
        inventory.setVariable("availablePositions",1);
        inventory.setVariable("inventory",List.of(new InventoryView(UUID.randomUUID(),"Ski Queen Gjetost",
            "IB-GJETOST","012345678905",java.time.LocalDate.of(2026,9,1),new BigDecimal("24"),BigDecimal.ZERO,
            new BigDecimal("5.85"),"USD","FEFO","DISCOUNT","Move to promotion","PROVISIONAL",new BigDecimal("24"),
            Instant.parse("2026-08-26T16:15:00Z"),Instant.parse("2026-08-28T16:15:00Z"))));
        assertThat(templateEngine().process("inventory",inventory)).contains("Available Inventory","Inventory on hand",
            "All inventory","Physical available","Marketplace sellable quantity: 0",
            "Inventory Ledger","Search product, brand, ASIN, Item Code, or UPC","Shelf-life rules","Cannot sell","Act soon",
            "Upload Physical Count","Recent files","Uploaded Files","Storage locations","/app/inventory",
            "5 days left","Action","Inventory actions","Plan sale pricing","Adjust inventory",
            "Plan physical removal","Marketplace publishing remains paused",
            "Receive an item not on the invoice",
            "Received at zero cost","24 each","Ski Queen Gjetost","FEFO").doesNotContain("inventory-action-dialog");

        var countReview=webContext();countReview.setVariables(base);
        countReview.setVariable("physicalCount",new com.nextaicommerce.platform.receiving.PhysicalCountImportService.ImportView(
            UUID.randomUUID(),"warehouse-count.xlsx","STAGED",List.of("Item Code","Count","Expiration","Location"),
            Map.of("itemCode","Item Code","quantity","Count","expiration","Expiration","location","Location"),
            List.of(Map.of("Item Code","53383","Count","24","Expiration","2026-09-01","Location","A-01")),1,0,vendor.id()));
        countReview.setVariable("vendors",List.of(vendor));
        assertThat(templateEngine().process("physical-count-import",countReview)).contains(
            "Review detected columns","warehouse-count.xlsx","Item code, UPC, or SKU","Counted quantity",
            "authoritative count","Validate and apply","never Amazon","physical-count-review-body",
            "Items absent from the file stay unchanged.","Open-order reservations are recalculated")
            .doesNotContain("Uploaded sample","physical-count-preview");

        countReview.setVariable("physicalCountError","Row 12 code “2409060” does not match an active catalogue product.");
        countReview.setVariable("physicalCountRepairType","PRODUCT");
        countReview.setVariable("physicalCountRepairValue","2409060");
        assertThat(templateEngine().process("physical-count-import",countReview)).contains(
            "Add this item to the account catalogue","value=\"2409060\"","Product name","Add and continue",
            "UPC / EAN","Current unit cost","Vendor","/app/catalog/product-options","physical-count-repair.js");

        countReview.setVariable("physicalCountError",null);countReview.setVariable("physicalCountRepairType",null);
        countReview.setVariable("physicalCountProgress",new com.nextaicommerce.platform.receiving.PhysicalCountProgress.View(
            "PROCESSING",68,"Reconciling expiration batches and quantities",null,0,"count.xlsx",240,0));
        assertThat(templateEngine().process("physical-count-import",countReview)).contains(
            "Applying your physical count","68%","Reconciling expiration batches and quantities",
            "Processing continues safely in the background").doesNotContain("Validate and apply");

        var ledger=webContext();ledger.setVariables(base);
        ledger.setVariable("movementCount",1);ledger.setVariable("receiptCount",1L);ledger.setVariable("adjustmentCount",0L);
        ledger.setVariable("query","");
        var ledgerRows=List.of(new LedgerView(UUID.randomUUID(),UUID.randomUUID(),Instant.parse("2026-08-28T16:15:00Z"),
            "Ski Queen Gjetost","IB-GJETOST","012345678905","RECEIPT",new BigDecimal("24"),
            java.time.LocalDate.of(2026,9,1),new BigDecimal("5.85"),"USD","RECEIVING","PO-12345678",
            "Received from vendor","barry.guze@gmail.com","PROVISIONAL"));
        ledger.setVariable("ledger",ledgerRows);ledger.setVariable("ledgerPage",new LedgerPage(ledgerRows,301,0,100));
        assertThat(templateEngine().process("inventory-ledger",ledger)).contains("Inventory Ledger","Movement history",
            "Search product, brand, ASIN, SKU, source, or reference","Received","+24 each","PO-12345678","barry.guze@gmail.com","Cost pending",
            "data-table-widget=\"inventory-ledger\"","data-column-control=\"inventory-ledger\"","Showing 1–100 of 301 movements",
            "Page","of 4","Next","Last");
    }

    @Test
    void marketplaceSkuTemplateShowsStoreScopedAmazonOperations() {
        var context=webContext();
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","Ibcore LLC"),Map.entry("signedInEmail","barry.guze@gmail.com"),
            Map.entry("roleLabel","Super Admin"),Map.entry("query",""),Map.entry("selectedStatus","ALL"),
            Map.entry("selectedSort","status"),Map.entry("sortDirection","asc"),
            Map.entry("selectedStoreName","Ibcore"),
            Map.entry("selectedStore",new ConnectionView(UUID.randomUUID(),UUID.randomUUID(),"Ibcore LLC","Ibcore",
                "AMAZON","seller","ATVPDKIKX0DER","Amazon US","🇺🇸","ACTIVE",Instant.now(),true)),
            Map.entry("insights",new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.SkuInsights(216,100,50,50,20,10)),
            Map.entry("summary",new SkuSummary(1710,216,0,1494,0,0,1710,647)),
            Map.entry("skuPage",new SkuPage(List.of(new SkuView("IB-TEST-SKU","B012345678","Test Product",
                "https://images.example.test/product.jpg","ACTIVE","New","AMAZON",new BigDecimal("29.99"),
                "USD",0,"X001TEST",12,3,2,1,1,2,3,4,8,new BigDecimal("239.92"),
                new BigDecimal("9.42"),"USD",null,null,null,null,"ATVPDKIKX0DER",
                Instant.parse("2026-08-29T12:00:00Z"),"Standard shipping",new BigDecimal("12.00"))),1,0,100))
        ));
        String rendered=templateEngine().process("marketplace-skus",context);
        assertThat(rendered).contains("Marketplace SKUs","Ibcore assortment","IB-TEST-SKU","B012345678",
            "4w sales","1 | 2 | 3 | 4","Profit","Pending","Catalogue","Unmapped","Price (+ shipping)","Buy Box",
            "Available","Shipping template","data-column-control=\"marketplace-skus\"",
            "Buy Box price match","/images/channels/amazon-seller.png","/images/platform/table/amazon.com-logo.png",
            "https://www.amazon.com/dp/B012345678","sellercentral.amazon.com")
            .doesNotContain(">Seller SKU <","Seller SKU &amp; links","SKU Mapping <small>Planned");
    }

    @Test
    void friendlyErrorTemplateReplacesWhitelabelFallback() {
        var context=webContext();context.setVariable("status",500);
        assertThat(templateEngine().process("error",context)).contains(
            "Nothing was changed","We couldn’t complete that request","Return to workspace")
            .doesNotContain("Whitelabel Error Page");
    }

    @Test
    void firstReceivingExplainsTheCurrentStepAndAdvancesAfterVendorSetup() {
        var noVendor=webContext();
        noVendor.setVariables(Map.ofEntries(
            Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","Ibcore LLC"),Map.entry("signedInEmail","barry.guze@gmail.com"),
            Map.entry("roleLabel","Super Admin"),Map.entry("vendors",List.of()),Map.entry("sessions",List.of())
        ));
        String firstStep=templateEngine().process("receiving",noVendor);
        assertThat(firstStep).contains("First receiving setup","Prepare your first supplier","Current step",
            "Add first vendor","Step 1 of 4 · Supplier setup","Save vendor and continue");

        var vendorReady=webContext();
        vendorReady.setVariables(Map.ofEntries(
            Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","Ibcore LLC"),Map.entry("signedInEmail","barry.guze@gmail.com"),
            Map.entry("roleLabel","Super Admin"),Map.entry("sessions",List.of()),
            Map.entry("vendors",List.of(new VendorView(UUID.randomUUID(),"KEHE","KEHE","USD",
                BigDecimal.ZERO,BigDecimal.ZERO,0,"ACTIVE"))),Map.entry("vendorCreated",true)
        ));
        String secondStep=templateEngine().process("receiving",vendorReady);
        assertThat(secondStep).contains("Your supplier is ready","Upload documents","Source documents",
            "document.getElementById('session-dialog').showModal()","Vendor <b class=\"required-label\">Required</b>");
    }

    @Test
    void usersTemplateParsesAndRenders() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(
            new MockHttpServletRequest(servletContext), new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("isSuperAdmin", true), Map.entry("selectedAccountName", "Ibcore"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("activeUsers", 1L), Map.entry("pendingInvitations", 0),
            Map.entry("connectedStores", 2),
            Map.entry("invitations", List.of()),
            Map.entry("members", List.of(new MemberView(UUID.randomUUID(), "Barry",
                "barry.guze@gmail.com", "OWNER", "ACTIVE", 1))),
            Map.entry("stores", List.of(
                new ConnectionView(UUID.randomUUID(), UUID.randomUUID(), "Ibcore", "Ibcore Amazon",
                    "AMAZON", "seller", "ATVPDKIKX0DER", "Amazon US", "🇺🇸", "ACTIVE", null, false),
                new ConnectionView(UUID.randomUUID(), UUID.randomUUID(), "Ibcore", "Ibcore Walmart",
                    "WALMART", "IBCORE-WALMART", "Walmart-US", "Walmart US", "🇺🇸", "PENDING", null, true)))
        ));

        String rendered = engine.process("users", context);
        assertThat(rendered).contains("Users &amp; Access", "Ibcore", "Amazon US", "Walmart US",
            "/images/channels/amazon-official.png", "/images/channels/walmart-official.png",
            "type=\"button\" aria-label=\"Close invitation\"");
    }

    @Test
    void overviewRendersAnimatedAmazonWorkflowAndCompletedChecks() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(
            new MockHttpServletRequest(servletContext), new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);
        UUID connectionId = UUID.randomUUID();
        var sync = new SyncStatusView(connectionId, "Ibcore", "RUNNING", 3,
            "LISTINGS_SNAPSHOT", "Downloading your product listings", Instant.now());
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("canManageUsers", true), Map.entry("isSuperAdmin", true),
            Map.entry("selectedAccountName", "Ibcore LLC"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("syncStatuses", List.of(sync)),
            Map.entry("syncJobsByConnection", Map.of(connectionId, List.of(
                new SyncJobView(connectionId, 1, "VERIFY_SELLER", "Verify Amazon store",
                    "COMPLETED", 1, null),
                new SyncJobView(connectionId, 2, "LISTINGS_SNAPSHOT", "Products and SKUs",
                    "RUNNING", 0, null))))
        ));

        String rendered = engine.process("app", context);
        assertThat(rendered).contains("sync-loader", "Step 2 of 12", "Products and SKUs",
            "aria-label=\"Complete\">✓", "Amazon is preparing the product file").doesNotContain("sync-panel");
    }

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML); resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext webContext() {
        MockServletContext servletContext = new MockServletContext();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        return new WebContext(application.buildExchange(
            new MockHttpServletRequest(servletContext), new MockHttpServletResponse()));
    }
}
