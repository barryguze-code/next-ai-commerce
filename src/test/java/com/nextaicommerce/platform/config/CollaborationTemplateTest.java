package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nextaicommerce.platform.collaboration.CollaborationRepository.Review;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class CollaborationTemplateTest {
    @Test
    void rendersActiveConversationsAsTheStandardOperationalTable() {
        var servletContext=new MockServletContext();
        var application=JakartaServletWebApplication.buildApplication(servletContext);
        var context=new WebContext(application.buildExchange(
            new MockHttpServletRequest(servletContext),new MockHttpServletResponse()));
        var review=new Review(UUID.randomUUID(),"INVENTORY","item|2026-10-01","Morning Round Apple · MAIN",
            "AMAZON","CONVERSATION","Check the cold-chain count","ACTIVE","barry@example.com",null,null,null,
            Instant.parse("2026-09-09T16:00:00Z"),Instant.parse("2026-09-09T16:08:00Z"),null,3,1,
            "Alex Smith, Barry Guze","{\"stock_level\":12}","/app/inventory",true);
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),
            Map.entry("canManageUsers",true),Map.entry("canEditCatalog",true),Map.entry("isSuperAdmin",true),
            Map.entry("selectedAccountName","Ibcore"),Map.entry("signedInEmail","barry@example.com"),
            Map.entry("roleLabel","Super Admin"),Map.entry("reviews",List.of(review)),
            Map.entry("selectedView","ACTIVE"),Map.entry("selectedEntity","INVENTORY"),
            Map.entry("subjectKey","item|2026-10-01"),
            Map.entry("currentReturnTo","/app/collaboration?view=ACTIVE&amp;entityType=INVENTORY")));

        String rendered=engine().process("collaboration",context);
        assertThat(rendered).contains("Team conversations","data-table-widget=\"collaboration-conversations\"",
            "People","Created","Last activity","Alex Smith, Barry Guze","Check the cold-chain count",
            "Showing this record’s history","collaboration-open-row","My private notes","Close conversation",
            "Search conversations","Finish / reopen","Quick huddle","General team huddle",
            "Temporary by default","45-minute idle expiry","Open source","Enter to send","huddle-dock",
            "Type @ to mention someone here");
        assertThat(rendered).doesNotContain("huddle-save-choice","Save &amp; complete");
        assertThat(rendered).doesNotContain("data-column=\"status\"");
    }

    @Test
    void rowChatUsesQuietAndActiveStatesWithDatedMultiConversationChoice() throws Exception {
        String inventory=Files.readString(Path.of("src/main/resources/templates/inventory.html"));
        String catalog=Files.readString(Path.of("src/main/resources/templates/account-catalog.html"));
        String skus=Files.readString(Path.of("src/main/resources/templates/marketplace-skus.html"));
        String orders=Files.readString(Path.of("src/main/resources/templates/orders.html"));
        String receiving=Files.readString(Path.of("src/main/resources/templates/receiving.html"));
        String shipping=Files.readString(Path.of("src/main/resources/static/js/shipping-desk.js"));
        String javascript=Files.readString(Path.of("src/main/resources/static/js/collaboration.js"));
        String styles=Files.readString(Path.of("src/main/resources/static/css/collaboration-thread.css"));
        String chatStyles=Files.readString(Path.of("src/main/resources/static/css/collaboration-chat.css"));
        assertThat(List.of(inventory,catalog,skus,orders,receiving)).allSatisfy(template->assertThat(template)
            .contains("conversation.totalCount > 0 ? 'has-conversation'","data-total-count=", "class=\"conversation-lines\"")
            .contains("conversation.totalCount > 0}\" th:text=\"${conversation.totalCount}")
            .doesNotContain("thread-overflow-button","openRecordThreadMenu","conversation.activeMessageCount"));
        assertThat(inventory).contains("positionKey=${row.itemId+'|'+(row.expirationDate == null ? '' : row.expirationDate.toString())}",
            "openReviewsByPosition.get(positionKey)", "data-entity-id=${positionKey}");
        assertThat(shipping).contains("window.prepareRecordCollaboration(chat)","totalCount:String(summary?.totalCount||0)","count.textContent=summary.totalCount")
            .doesNotContain("count.textContent=summary.activeMessageCount")
            .doesNotContain("thread-overflow-button","openRecordThreadMenu");
        assertThat(javascript).contains("showPicker","formatDate(review.createdAt)",
            "view=ALL&entityType=","PRIVATE_NOTE","connectHuddles","closeConversation",
            "wireEnterToSend","type:'JOIN'","filterCollaborationRows","showHuddleInvitation","liveHuddles.clear()",
            "huddle-person-avatar","Online · invite to huddle","showHuddleDock","closeTemporaryHuddle",
            "mountFloatingHuddle","field.id==='huddle-message-input'",
            "$('#huddle-finish-button')?.addEventListener","Number(button.dataset.totalCount||0)>1",
            ".sort((left,right)=>Date.parse(right.createdAt)-Date.parse(left.createdAt))",
            "window.prepareRecordCollaboration=decorateRecordConversationButton",
            "(forcePicker||threads.length>1)&&threads.length", "threads.length===1");
        assertThat(styles).contains(".record-collaboration-actions{display:inline-flex", ".collaboration-row-button.has-conversation", "border-color:#9dbdef", "background:#edf4ff", "#2169d6")
            .doesNotContain(".thread-overflow-button");
        assertThat(styles).contains(".huddle-person-avatar", ".huddle-online-people{display:flex", ".quick-huddle-panel{min-height:300px}",".huddle-dock");
        assertThat(chatStyles).contains(".mention-menu button.is-highlighted", ".message-mention", "#eef3f9");
        assertThat(styles).contains(".huddle-composer>button{", ".huddle-composer>button:hover{", ".huddle-composer>button>svg{");
        assertThat(styles).doesNotContain(".huddle-composer button{");
    }

    @Test
    void inventoryTableKeepsReadableColumnWidthsAtSplitScreenSizes() throws Exception {
        String styles=Files.readString(Path.of("src/main/resources/static/css/inventory.css"));
        assertThat(styles).contains("min-width: 1110px", ".available-inventory-table th:nth-child(1){width:350px}",
            "@media (max-width: 1180px)", "flex: 1 1 720px", "flex: 1 1 260px",
            ".available-inventory-table .product-cell>div { flex: 1 1 0; width: 0;",
            ".inventory-toolbar-actions{justify-content:flex-start;width:100%}");
    }

    @Test
    void skuMappingControlsAreSharedAssetFreeAndInventoryShortagesAreActionable() throws Exception {
        String orders=Files.readString(Path.of("src/main/resources/templates/orders.html"));
        String skus=Files.readString(Path.of("src/main/resources/templates/marketplace-skus.html"));
        String styles=Files.readString(Path.of("src/main/resources/static/css/table-widget.css"));
        String adjustmentJs=Files.readString(Path.of("src/main/resources/static/js/order-inventory-adjust.js"));
        assertThat(List.of(orders,skus)).allSatisfy(template->assertThat(template)
            .contains("table-mapping-control","table-mapping-icon","table-mapping-copy","Mapped SKU","Map SKU")
            .doesNotContain("mapping-summary-button\" th:attr=\"data-seller-sku=${item.sellerSku},data-product-title=${item.title ?: item.sellerSku}\" onclick=\"openOrderMapping(this)\" th:title="));
        assertThat(orders).contains("shortage-inventory-action","Adjust stock","openOrderInventoryAdjustment(this)",
            "order-inventory-adjust-dialog","order-inventory-adjust.js");
        assertThat(styles).contains(".table-mapping-control.needs-mapping",".table-mapping-icon svg",
            ".table-mapping-control:focus-visible",".shortage-inventory-action:hover")
            .doesNotContain(".table-mapping-control:hover{border-color:color-mix(in srgb,var(--mapping-color) 54%,#fff);background:color-mix(in srgb,var(--mapping-surface) 86%,var(--mapping-color));box-shadow:0 5px 14px");
        assertThat(adjustmentJs).contains("/app/marketplace-skus/mappings/components","/app/inventory/adjustment-positions",
            "/app/inventory/adjustments/inline","Promise.all","current.replaceWith(incoming)");
    }

    @Test
    void collaborationHubUsesWholeRowsAndIconOnlyCloseOrReopenActions() throws Exception {
        String template=Files.readString(Path.of("src/main/resources/templates/collaboration.html"));
        String styles=Files.readString(Path.of("src/main/resources/static/css/collaboration.css"));
        assertThat(template).contains("class=\"collaboration-open-row\"", "openCollaborationReview(this)",
            "conversation-state-button close", "conversation-state-button reopen", "Reopen conversation",
            "#strings.arraySplit(review.participants,',')");
        assertThat(styles).contains(".collaboration-open-row:hover", ".conversation-state-button.close:hover",
            ".conversation-state-button.reopen:hover");
    }

    @Test
    void sidebarCollaborationDockStartsAccountScopedHuddlesWithoutASecondMenuEntry() throws Exception {
        String navigation=Files.readString(Path.of("src/main/resources/templates/fragments/sidebar-nav.html"));
        String javascript=Files.readString(Path.of("src/main/resources/static/js/collaboration.js"));
        String styles=Files.readString(Path.of("src/main/resources/static/css/collaboration-thread.css"));
        assertThat(navigation).contains("data-sidebar-collaboration","sidebar-collaboration-row","data-sidebar-huddle-trigger",
            "Open collaboration and AI teammate","Next AI Commerce AI","Coming soon · Locked","chat-sound-wave inner","chat-sound-wave outer",
            "data-sidebar-huddle-people","selectedAccountName+' only'","Open full collaboration view")
            .doesNotContain("<svg viewBox=\"0 0 24 24\"><path d=\"M5 5h14v10H9l-4 4V5Zm4 4h6m-6 3h4\"/></svg>Collaboration");
        assertThat(javascript).contains("renderSidebarCollaboration","sidebarPeople()",
            "participantIds:[person.id]","data-sidebar-huddle-live-slot","closeSidebarHuddleMenu",
            "playHuddleChime","startHuddleRing","stopHuddleRing");
        assertThat(styles).contains(".sidebar-collaboration{",".sidebar-huddle-menu{",
            ".sidebar-person-avatar",".huddle-invitation.is-sidebar-mounted",
            "@keyframes huddle-chat-nudge","@keyframes huddle-sound-wave","z-index:3000",
            "prefers-reduced-motion","html[data-theme=dark] .sidebar-huddle-menu");
    }

    private static SpringTemplateEngine engine() {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);resolver.setCacheable(false);
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);return engine;
    }
}
