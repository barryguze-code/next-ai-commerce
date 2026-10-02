package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlatformUiStandardTest {
    private String read(String path) throws Exception {
        return Files.readString(Path.of("src/main/resources/" + path));
    }

    @Test void everyWorkspaceLoadsTheStandardBeforeFirstPaint() throws Exception {
        try (var pages = Files.list(Path.of("src/main/resources/templates"))) {
            for (var file : pages.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                if (!html.contains("fragments/sidebar-nav")) continue;
                String head = html.substring(0, html.indexOf("</head>"));
                assertThat(head).as(file.getFileName().toString())
                    .contains("/css/platform-ui.css?v=", "defer src=\"/js/platform-ui.js?v=");
            }
        }
    }

    @Test void approvedDensityAndThemeTokensAreCentralized() throws Exception {
        assertThat(read("static/css/platform-ui.css")).contains(
            "--ui-page-title:27px", "--ui-section-title:18px", "--ui-search-text:14px",
            "--ui-control-height:38px", "html[data-theme=dark]", "prefers-reduced-motion",
            "focus-visible", "--ui-action-soft");
    }

    @Test void labelChangesPreserveColumnPreferenceIdentity() throws Exception {
        assertThat(read("templates/inventory.html")).contains(
            "data-column=\"physical-available\"", ">Available</th>", ">Record</th>")
            .doesNotContain(">Physical available</th>");
        assertThat(read("static/js/table-widget.js")).contains("heading.dataset.title='Record'", "id==='record-context'");
    }

    @Test void productDetailDoesNotReplaceInventoryOrCopyActions() throws Exception {
        assertThat(read("static/js/platform-ui.js")).contains(
            "window.openInventoryHistory(row)", "copy.dataset.uiCopy", "navigator.clipboard.writeText",
            "dialog.showModal()", "event.stopPropagation()", "root.isConnected");
        assertThat(read("static/js/order-design.js")).doesNotContain("title.dataset.orderCopy=");
    }

    @Test void contributionFooterCoversMarketplaceAndOrdersWithoutFullRescans() throws Exception {
        assertThat(read("static/js/action-suggestions.js")).contains(
            ".sku-actions-dialog", ".order-picture-menu", "[data-action-suggestion-menu]",
            "Help R&D improve this menu", "pending=new Set()", "enhance(node)");
        assertThat(read("static/js/platform-controls.js")).contains("activePicker={trigger,popup,close}");
    }

    @Test void shippingPollingPreservesUnchangedRowsAndSelection() throws Exception {
        assertThat(read("static/js/shipping-desk.js")).contains(
            "signature===candidateSignature", "selected.has(item.orderId)", "entityType:'ORDER'",
            "menu.dataset.actionSuggestionMenu", "root.dataset.canCollaborate");
        assertThat(read("templates/shipping.html")).contains("data-can-collaborate=${canEditCatalog}",
            "data-column=\"record-context\"");
    }

    @Test void readOnlyRecordsStillOfferContributionWithoutWriteActions() throws Exception {
        assertThat(read("static/js/table-widget.js")).contains(
            "Actions and suggestions", "Have an idea for this record? Share it with R&D.")
            .doesNotContain("button.title='No actions available'");
    }

    @Test void tableSearchSkipsDefaultViewExtractionAndInvalidatesChangedRows() throws Exception {
        assertThat(read("static/js/table-data-tools.js")).contains("searchValues=new WeakMap()",
            "if(!saved.query&&!Object.values(saved.filters).some(Boolean))return true",
            "searchValues.delete(row)", "characterData:true", "['input','change']");
    }
}
