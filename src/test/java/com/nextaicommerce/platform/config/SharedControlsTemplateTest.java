package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedControlsTemplateTest {
    private String read(String path)throws Exception{return Files.readString(Path.of("src/main/resources/"+path));}
    @Test void inventoryDrawerUsesSharedLinksAndInlineLocationCreation()throws Exception{
        assertThat(read("templates/inventory.html")).contains("location-add-button","Add a storage location","window.loadInventoryMovements(row,\"item\")")
            .doesNotContain("Open full ledger","location-manage-link");
        assertThat(read("static/js/inventory-related-skus.js")).contains("data-marketplace-shortcuts","AbortController","Load more movements","Try again")
            .doesNotContain("Manage SKU</a>","The full ledger remains available");
    }
    @Test void narrowTablesKeepMinimumWidthAndReuseOneLocationPicker()throws Exception{
        assertThat(read("templates/account-catalog.html")).contains("data-table-min-width=\"1200\"","data-marketplace-shortcuts");
        assertThat(read("templates/marketplace-skus.html")).contains("data-table-min-width=\"1240\"");
        assertThat(read("static/css/table-widget.css")).contains("min-width:var(--table-min-width,0px)");
        assertThat(read("static/js/platform-controls.js")).contains("aria-selected","ArrowDown","Escape","new Event('change'","select.hidden=true");
    }
}
