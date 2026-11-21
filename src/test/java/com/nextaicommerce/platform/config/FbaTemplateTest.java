package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class FbaTemplateTest {
    @Test void shipmentPaginationUsesSharedServerPagerContract(){
        var context=context();context.setVariable("query","SKU");context.setVariable("amazonStore",true);
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),50,0,25));
        String html=engine().process("fba-shipments",context);
        // table-data-tools recognizes this class and these attributes; without them it
        // creates a second client-side pager counting only the currently loaded rows.
        assertThat(html).contains("table-pagination","data-page=\"0\"","data-page-max=\"2\"","data-page-size=\"25\"","Total: 50 shipments");
        assertThat(html).contains("data-column=\"fba-shipment-date\"","title=\"FBA shipment date\"").doesNotContain("Last imported");
    }
    @Test void warehouseFilteredEmptyStateDoesNotClaimNoImports(){
        var context=context();context.setVariable("query","");context.setVariable("amazonStore",true);
        context.setVariable("selectedWarehouse",UUID.randomUUID());
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),0,0,25));
        assertThat(engine().process("fba-shipments",context)).contains("No shipments match this warehouse").doesNotContain("No FBA shipments imported yet","Inventory snapshots alone");
    }
    @Test void stockRendersUnknownZeroAndFeesWithoutInventingTransfers()throws Exception{
        var context=context();context.setVariable("sku","KARACA <test>");
        context.setVariable("stock",new FbaReadRepository.Stock("KARACA",0,0,100,2,3,0,Instant.now(),new BigDecimal("12"),new BigDecimal("26.23"),"USD",Instant.now(),3));
        String html=engine().process("fragments/fba-stock",context);
        if(Boolean.getBoolean("fbaPreview")){
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local/fba-preview"));
            java.nio.file.Files.writeString(java.nio.file.Path.of(".local/fba-preview/stock.html"),html);
        }
        assertThat(html).contains("<dt>Available</dt>","26.23","Warehouse available","FC transfer","102","fba-shipments?q=").doesNotContain("<test>","Not imported");
        context.setVariable("stock",null);
        assertThat(engine().process("fragments/fba-stock",context)).contains("Pending","Waiting for an Amazon inventory snapshot");
    }
    @Test void shipmentPageRendersEmptyAndPopulatedStates()throws Exception{
        var context=context();context.setVariable("query","");context.setVariable("amazonStore",true);
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),0,0,25));
        assertThat(engine().process("fba-shipments",context)).contains("No FBA shipments imported yet","table-pagination","<h2>FBA shipments</h2>").doesNotContain("Replenishment shipments","<th>Ship from</th>");
        context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(new FbaReadRepository.Shipment("FBA123","<script>bad</script>","SHIPPED","SCK8",null,null,2,30,6)),1,0,25));
        context.setVariable("depletionStates",Map.of("FBA123",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null)));
        assertThat(engine().process("fba-shipments",context)).contains("FBA123","SCK8","&lt;script&gt;").doesNotContain("<script>bad</script>");
        if(Boolean.getBoolean("fbaPreview")){
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local/fba-preview"));
            java.nio.file.Files.writeString(java.nio.file.Path.of(".local/fba-preview/shipments.html"),engine().process("fba-shipments",context));
        }
    }
    @Test void warehouseFilterReusesPermissionAwareSharedManager(){
        var context=context();context.setVariable("query","");context.setVariable("amazonStore",true);context.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(),0,0,25));context.setVariable("fbaWarehouses",List.of());context.setVariable("canEditCatalog",true);
        String html=engine().process("fba-shipments",context);
        assertThat(html).contains("data-choice-manager=\"warehouse\"","id=\"warehouse-dialog\"","warehouse-manager.js","form=\"fba-search-form\"","name=\"warehouse\"");
        context.setVariable("canEditCatalog",false);html=engine().process("fba-shipments",context);
        assertThat(html).doesNotContain("data-choice-manager=\"warehouse\"","id=\"warehouse-dialog\"").contains("All warehouses");
    }
    @Test void decreasedShipmentKeepsDisabledDecreaseAndSeparateReview(){
        var c=context();c.setVariable("query","");c.setVariable("amazonStore",true);c.setVariable("manualDepletionEnabled",true);c.setVariable("canEditCatalog",true);c.setVariable("selectedStatus","");c.setVariable("depletableShipments",Set.of());
        c.setVariable("shipments",new FbaReadRepository.ShipmentPage(List.of(new FbaReadRepository.Shipment("FBA123","Shipment","SHIPPED","SCK8",null,null,2,30,6)),1,0,25));
        c.setVariable("depletionStates",Map.of("FBA123",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,true,false,null)));
        String html=engine().process("fba-shipments",c);assertThat(html).contains(">Decreased</span>","aria-label=\"Review stock decrease\"","data-fba-review-deduction","/images/platform/table/edit.png").contains("data-fba-already-decreased=\"true\"", "Stock already decreased");
        assertThat(java.util.regex.Pattern.compile("<button[^>]*data-fba-deplete[^>]*disabled[^>]*>").matcher(html).find()).isTrue();
    }
    @Test void verifiedParentHeaderAndStandaloneStateRenderWithoutMergingQuantities(){
        var c=context();c.setVariable("query","");c.setVariable("amazonStore",true);c.setVariable("shipmentHierarchy",true);c.setVariable("fbaReplenishmentEnabled",true);
        var rows=List.of(new FbaReadRepository.Shipment("A","Child A","SHIPPED","FC",null,null,1,4,0),new FbaReadRepository.Shipment("B","Child B","SHIPPED","FC",null,null,1,6,0),new FbaReadRepository.Shipment("X","Standalone","SHIPPED","FC",null,null,1,8,0));
        c.setVariable("shipments",new FbaReadRepository.ShipmentPage(rows,2,0,25));var parent=new com.nextaicommerce.platform.fba.FbaShipmentParents.Parent("verified-plan","Saved plan",UUID.randomUUID(),UUID.randomUUID(),"child");
        c.setVariable("shipmentParents",Map.of("A",parent,"B",parent));c.setVariable("firstParentChildren",Set.of("A"));c.setVariable("ambiguousShipmentParents",Set.of());c.setVariable("depletionStates",Map.of("A",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null),"B",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null),"X",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null)));
        var html=engine().process("fba-shipments",c);assertThat(html).contains("data-fba-parent-header=\"verified-plan\"","data-fba-parent-toggle=\"verified-plan\"","Parent unverified","Total: 2 plans / standalone shipments");assertThat(html.split("data-fba-parent-header=",-1)).hasSize(2);
    }
    @Test void parentActionsKeepDomainMetadataAndTruthfulCreationLabel()throws Exception{
        var c=context();c.setVariable("query","");c.setVariable("amazonStore",true);c.setVariable("shipmentHierarchy",true);c.setVariable("fbaReplenishmentEnabled",true);c.setVariable("manualDepletionEnabled",true);c.setVariable("canEditCatalog",true);
        var rows=List.of(new FbaReadRepository.Shipment("A","Child A","SHIPPED","FC",null,null,1,25,0),new FbaReadRepository.Shipment("B","Child B","SHIPPED","FC",null,null,1,0,0));c.setVariable("shipments",new FbaReadRepository.ShipmentPage(rows,1,0,25));
        var parent=new com.nextaicommerce.platform.fba.FbaShipmentParents.Parent("verified-plan","",null,null,"child-a",null,null,Instant.parse("2026-11-18T17:18:03Z"),Set.of("child-a","child-b"),"evidence");
        var summary=new com.nextaicommerce.platform.fba.FbaShipmentHierarchy.Summary(2,2,1L,25L,0L,"Origin","77377","City","FC","SHIPPED","—",new FbaReadRepository.ShipmentCosts(null,null,null,25),"TX");c.setVariable("parentSummaries",Map.of("verified-plan",summary));c.setVariable("shipmentParents",Map.of("A",parent,"B",parent));c.setVariable("firstParentChildren",Set.of("A"));c.setVariable("ambiguousShipmentParents",Set.of());c.setVariable("depletableShipments",Set.of("A","B"));c.setVariable("selectedStatus","");c.setVariable("depletionStates",Map.of("A",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null),"B",new com.nextaicommerce.platform.fba.FbaWorkspaceRepository.DepletionState(false,false,false,null)));
        String html=engine().process("fba-shipments",c);assertThat(html).contains("data-ship-from-state=\"TX\"","<nav data-fba-operations","data-column=\"actions\"","Actions</th>");assertThat(html).contains("Shipments · Created Nov 18, 2026","data-fba-parent-menu","data-action-key=\"document\"","data-action-key=\"decrease\"","data-action-key=\"hide\"","data-action-key=\"copy\"","View all 2 shipments","Decrease stock for all 2 shipments","Hide all 2 shipments from your view","aria-label=\"Copy plan ID\"");assertThat(summary.label(new com.nextaicommerce.platform.fba.FbaShipmentParents.Parent("P","Saved name",null,null,"c"))).isEqualTo("Saved name");
        if(Boolean.getBoolean("fbaParentPreview")){java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local/fba-preview"));java.nio.file.Files.writeString(java.nio.file.Path.of(".local/fba-preview/parent36.html"),html);}
    }
    private static SpringTemplateEngine engine(){var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);return engine;}
    private static WebContext context(){var servlet=new MockServletContext();var context=new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(new MockHttpServletRequest(servlet),new MockHttpServletResponse()));context.setVariable("fbaSync",new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null));context.setVariable("fbaConnectionId",UUID.randomUUID());return context;}
}
