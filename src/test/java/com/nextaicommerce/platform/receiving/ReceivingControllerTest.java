package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import com.nextaicommerce.platform.catalog.CatalogImportService;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.http.ResponseEntity;

class ReceivingControllerTest {
    @Test
    void missingVendorReturnsFriendlyMessageBeforeAnyDataIsSaved() {
        StubIntake intake=new StubIntake();
        ReceivingController controller=new ReceivingController(new ReceivingRepository(null,null),
            new CatalogRepository(null),new StubFiles(),intake);
        RedirectAttributesModelMap redirect=new RedirectAttributesModelMap();

        String result=controller.startWithDocument(authentication(),session(),null,"INVOICE","USD",null,
            "0","0","0",List.of(new MockMultipartFile("files","invoice.csv","text/csv","a,b\n1,2".getBytes())),redirect);

        assertThat(result).isEqualTo("redirect:/app/receiving");
        assertThat(redirect.getFlashAttributes().get("catalogError")).isEqualTo(
            "Choose the vendor before uploading invoices or packing lists.");
    }

    @Test
    void severalInvoicesCreateOneReceivingBatch() throws Exception {
        StubFiles files=new StubFiles();StubIntake intake=new StubIntake();
        ReceivingController controller=new ReceivingController(new ReceivingRepository(null,null),
            new CatalogRepository(null),files,intake);
        UUID vendorId=UUID.randomUUID();
        Map<String,String> row=new LinkedHashMap<>();row.put("Description","Product");row.put("ItemCode","123");row.put("Quantity","1");
        var parsed=new CatalogImportService.ParsedSheet(List.of("Description","ItemCode","Quantity"),List.of(row));
        files.result=parsed;
        RedirectAttributesModelMap redirect=new RedirectAttributesModelMap();

        String result=controller.startWithDocument(authentication(),session(),vendorId.toString(),"INVOICE","USD",null,
            "0","0","0",List.of(
                new MockMultipartFile("files","invoice-1.csv","text/csv","one".getBytes()),
                new MockMultipartFile("files","invoice-2.csv","text/csv","two".getBytes())),redirect);

        assertThat(result).isEqualTo("redirect:/app/receiving/"+intake.sessionId);
        assertThat(redirect.getFlashAttributes().get("catalogSuccess")).isEqualTo(
            "2 documents uploaded successfully. Review the receiving plan below.");
        assertThat(intake.vendorId).isEqualTo(vendorId);
        assertThat(intake.documents).hasSize(2);
    }

    @Test
    void receiveReturnsSmallJsonUpdateForFastInPlaceSave() {
        StubReceiving receiving=new StubReceiving();
        ReceivingController controller=new ReceivingController(receiving,new CatalogRepository(null),new StubFiles(),new StubIntake());
        Object result=controller.receive(UUID.randomUUID(),UUID.randomUUID(),authentication(),session(),
            BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.valueOf(12),null,"SELLABLE",null,
            BigDecimal.ZERO,BigDecimal.ZERO,false,"application/json",new RedirectAttributesModelMap());

        assertThat(result).isInstanceOf(ResponseEntity.class);
        var response=(ResponseEntity<?>)result;
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(ReceivingController.ReceiveResponse.class);
        var body=(ReceivingController.ReceiveResponse)response.getBody();
        assertThat(body.remainingEach()).isZero();
        assertThat(body.message()).contains("Inventory is available now");
    }

    private static MockHttpSession session(){
        MockHttpSession session=new MockHttpSession();session.setAttribute("selectedTenantId",UUID.randomUUID());return session;
    }
    private static UsernamePasswordAuthenticationToken authentication(){
        return new UsernamePasswordAuthenticationToken("operator@example.com","password");
    }
    private static class StubFiles extends CatalogImportService {
        CatalogImportService.ParsedSheet result;
        StubFiles(){super(null,null,null);}
        @Override public ParsedSheet readFile(String filename,byte[] bytes){return result;}
    }
    private static class StubReceiving extends ReceivingRepository {
        StubReceiving(){super(null,null);}
        @Override public ReceiveProgress receive(UUID tenantId,UUID sessionId,UUID itemId,String actorEmail,
                BigDecimal cases,BigDecimal eaches,BigDecimal unitsPerCase,java.time.LocalDate expiration,
                String disposition,String notes,BigDecimal depositFee,BigDecimal otherFee,boolean updateDefaultCost){
            return new ReceiveProgress(BigDecimal.valueOf(12),BigDecimal.ZERO,1,1,"READY");
        }
    }
    private static class StubIntake extends ReceivingIntakeService {
        final UUID sessionId=UUID.randomUUID();UUID vendorId;List<IntakeDocument> documents=List.of();
        StubIntake(){super(null);}
        @Override public UUID start(UUID tenantId,String actorEmail,UUID vendorId,String documentType,
                String currency,String reference,BigDecimal freight,BigDecimal duty,BigDecimal other,
                List<IntakeDocument> documents){this.vendorId=vendorId;this.documents=documents;return sessionId;}
    }
}
