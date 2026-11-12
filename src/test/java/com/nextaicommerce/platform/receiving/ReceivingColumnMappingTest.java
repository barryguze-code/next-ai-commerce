package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogImportService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReceivingColumnMappingTest {
    private final CatalogImportService parser=new CatalogImportService(null,null,null);
    private final byte[] csv=("1000015745001,\"KARACA EMIRGAN STEEL POT SET, COLORLES\",42\n"
        +"1000004690001,KARACA EMIRGAN XL 10 PIECE POT SET,108\n"
        +"1000267175001,Karaca Starlight,120\n1000267175002,Karaca Black,120\n"
        +"1000267175001,Karaca Starlight,120\n1000267175002,Karaca Black,120\n").getBytes(StandardCharsets.UTF_8);
    private Map<String,String> fields(){return Map.of("AccountSKU","0","Description","1","Quantity","2");}
    @Test void headerlessOwnerFileKeepsFirstRowDuplicatesAndIndividualUnits() throws Exception {
        var raw=parser.readFileWithoutHeaders("packing.csv",csv);
        var mapped=ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(false,"EACH",fields()));
        assertThat(mapped).hasSize(6);assertThat(mapped.getFirst()).containsEntry("AccountSKU","1000015745001").containsEntry("ShipQuantity","42");
        assertThat(mapped.stream().mapToInt(row->Integer.parseInt(row.get("ShipQuantity"))).sum()).isEqualTo(630);
        assertThat(mapped).allSatisfy(row->assertThat(row).containsKey("_originalColumns").doesNotContainKeys("UnitsPerCase","Quantity"));
        assertThat(mapped.get(2)).isEqualTo(mapped.get(4));
    }
    @Test void bothOwnerHeadingFormatsPreserveAllSixNumericQuantities() throws Exception {
        for(String header:List.of("Item code,Item name,Quantity", "AccountSKU,Description,ShipQuantity")){
            var sheet=parser.readFile("packing.csv",(header+"\r\n"+new String(csv,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            var mapped=ReceivingColumnMapping.apply(sheet,new ReceivingColumnMapping.Selection(true,"EACH",fields()));
            assertThat(mapped).hasSize(6);
            assertThat(mapped.stream().mapToInt(row->Integer.parseInt(row.get("ShipQuantity"))).sum()).isEqualTo(630);
        }
    }
    @Test void quantityAloneCannotBypassDescriptionAndIdentityMatching() {
        assertThat(ReceivingColumnMapping.hasRequiredHeaders(new CatalogImportService.ParsedSheet(List.of("Code","Name","Quantity"),List.of()))).isFalse();
        assertThat(ReceivingColumnMapping.hasRequiredHeaders(new CatalogImportService.ParsedSheet(List.of("AccountSKU","Name","Quantity"),List.of()))).isFalse();
        assertThat(ReceivingColumnMapping.hasRequiredHeaders(new CatalogImportService.ParsedSheet(List.of("AccountSKU","Description","Quantity"),List.of()))).isTrue();
    }
    @Test void emptyDescriptionDataRequiresMatchingEvenWithRecognizedHeaders() {
        assertThat(ReceivingColumnMapping.hasRequiredHeaders(new CatalogImportService.ParsedSheet(List.of("AccountSKU","Description","Name","Quantity"),List.of(Map.of("AccountSKU","123","Description","","Name","Pot","Quantity","42"))))).isFalse();
    }
    @Test void headedFileAndCaseMeaningRemainExplicit() throws Exception {
        var raw=parser.readFile("cases.csv",("AccountSKU,Description,Quantity,UnitsPerCase\n00012,Pot,2,6\n").getBytes(StandardCharsets.UTF_8));
        var fields=new HashMap<>(fields());fields.put("UnitsPerCase","3");
        var row=ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(true,"CASE",fields)).getFirst();
        assertThat(row).containsEntry("AccountSKU","00012").containsEntry("Quantity","2").containsEntry("UnitsPerCase","6").doesNotContainKey("ShipQuantity");
    }
    @Test void rejectsMissingUnitsOrAmbiguousIdentity() throws Exception {
        var raw=parser.readFileWithoutHeaders("packing.csv",csv);
        assertThatThrownBy(()->ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(false,"",fields()))).hasMessageContaining("individual units or cases");
        assertThatThrownBy(()->ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(false,"EACH",Map.of("Description","1","Quantity","2")))).hasMessageContaining("Account SKU");
    }
    @Test void rejectsDuplicateOrOutOfRangeColumns() throws Exception {
        var raw=parser.readFileWithoutHeaders("packing.csv",csv);
        assertThatThrownBy(()->ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(false,"EACH",Map.of("AccountSKU","0","Description","1","Quantity","1")))).hasMessageContaining("only once");
        assertThatThrownBy(()->ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(false,"EACH",Map.of("AccountSKU","0","Description","1","Quantity","99")))).hasMessageContaining("valid");
    }
    @Test void originalHeadersCannotSupplyUnmappedCosts() throws Exception {
        var raw=parser.readFile("packing.csv","AccountSKU,Description,Quantity,UnitCost\n001,Pot,3,999\n".getBytes(StandardCharsets.UTF_8));
        var row=ReceivingColumnMapping.apply(raw,new ReceivingColumnMapping.Selection(true,"EACH",fields())).getFirst();
        assertThat(row).doesNotContainKey("UnitCost");assertThat(row.get("_originalColumns")).contains("999");
    }
    @Test void batchSchemaRequiresIdenticalColumnsMappingsAndQuantityMeaning(){
        var a=new CatalogImportService.ParsedSheet(List.of("Code","Description","Quantity"),List.of());
        var b=new CatalogImportService.ParsedSheet(List.of("Quantity","Description","Code"),List.of());
        var each=new ReceivingColumnMapping.Selection(true,"EACH",fields());
        assertThat(ReceivingColumnMapping.schema(a,null)).isNotEqualTo(ReceivingColumnMapping.schema(b,null));
        assertThat(ReceivingColumnMapping.schema(a,each)).isNotEqualTo(ReceivingColumnMapping.schema(a,new ReceivingColumnMapping.Selection(true,"CASE",fields())));
    }
    @Test void explicitMappingAcceptsSameColumnsWithAndWithoutHeadingRow(){
        var headed=new CatalogImportService.ParsedSheet(List.of("Item code","Item name","Quantity"),List.of());
        var headerless=new CatalogImportService.ParsedSheet(List.of("Column 1","Column 2","Column 3"),List.of());
        assertThat(ReceivingColumnMapping.schema(headed,new ReceivingColumnMapping.Selection(true,"EACH",fields())))
            .isEqualTo(ReceivingColumnMapping.schema(headerless,new ReceivingColumnMapping.Selection(false,"EACH",fields())));
    }
    @Test void costsDistinguishUnknownZeroAndPrivacyPlaceholder(){
        assertThat(ReceivingRepository.receivingCost("")).isNull();
        assertThat(ReceivingRepository.receivingCost("0")).isEqualByComparingTo("0");
        assertThat(ReceivingRepository.receivingCost("0.01")).isEqualByComparingTo("0.01");
        assertThatThrownBy(()->ReceivingRepository.receivingCost("unknown")).hasMessageContaining("valid");
        assertThatThrownBy(()->ReceivingRepository.receivingCost("-1")).hasMessageContaining("non-negative");
    }
}
