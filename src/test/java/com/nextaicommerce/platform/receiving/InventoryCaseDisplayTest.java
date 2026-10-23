package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryCaseDisplayTest {
    private InventoryRepository.InventoryView row(String quantity,String pack){
        var q=new BigDecimal(quantity);
        return new InventoryRepository.InventoryView(UUID.randomUUID(),"Item","0001","UPC",null,
            q,BigDecimal.ONE,BigDecimal.TEN,"USD","FIFO",null,null,"FINAL",q,null,null,
            null,"MAIN","Storage",null,null,q,BigDecimal.ONE,1,null,null,
            pack==null?null:new BigDecimal(pack));
    }
    @Test void displaysFullCasesAndLooseEachesWithoutRoundingIntoAnotherCase(){
        assertThat(row("24","12").onHandDisplay()).isEqualTo("2 cases");
        assertThat(row("25","12").onHandDisplay()).isEqualTo("2 cases + 1 each");
        assertThat(row("12","12").onHandDisplay()).isEqualTo("1 case");
        assertThat(row("7","12").onHandDisplay()).isEqualTo("0 cases + 7 each");
        assertThat(row("0","12").onHandDisplay()).isEqualTo("0 cases");
        assertThat(row("24.5000","12.0000").onHandDisplay()).isEqualTo("2 cases + 0.5 each");
    }
    @Test void unknownOrInvalidPackRemainsEaches(){
        for(String pack:new String[]{null,"0","-1","1","1.5"})
            assertThat(row("7",pack).onHandDisplay()).isEqualTo("7 each");
    }
    @Test void displayDoesNotChangeEachQuantitiesReservationsOrCosts(){
        var r=row("25","12");r.onHandDisplay();
        assertThat(r.quantity()).isEqualByComparingTo("25");
        assertThat(r.reservedUnits()).isEqualTo("1");
        assertThat(r.availableUnits()).isEqualTo("24");
        assertThat(r.unitCostDisplay()).isEqualTo("10.00");
        assertThat(r.onHandTitle()).contains("25 each","12 each per current catalogue case","not sealed-carton count");
    }
    @Test void realOnHandCellKeepsEachSortAndExportAndAccessibleCaseExplanation() throws Exception {
        String template=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/inventory.html"));
        int start=template.indexOf("<td class=\"inventory-on-hand\"");
        String fragment=template.substring(start,template.indexOf("</td>",start)+5);
        var engine=new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(new org.thymeleaf.templateresolver.StringTemplateResolver());
        var context=new org.thymeleaf.context.Context();context.setVariable("row",row("25","12"));
        String html=engine.process(fragment,context);
        assertThat(html).contains("2 cases + 1 each","data-sort-value=\"25\"","data-export-value=\"25 each\"",
            "tabindex=\"0\"","data-tooltip=\"25 each","aria-label=\"25 each","Use oldest stock first");
        assertThat(html).doesNotContain("<br");
        context.setVariable("row",row("24.5000","12"));
        assertThat(engine.process(fragment,context)).contains("2 cases + 0.5 each","data-export-value=\"24.5 each\"");
    }
}
