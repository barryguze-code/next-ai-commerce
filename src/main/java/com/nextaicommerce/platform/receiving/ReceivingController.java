package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogImportService;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.beans.factory.annotation.Autowired;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;

@Controller
public class ReceivingController {
    private static final Logger log=LoggerFactory.getLogger(ReceivingController.class);
    private static final int MAX_DOCUMENTS_PER_UPLOAD=12;
    private static final long MAX_DOCUMENT_BYTES=20L*1024*1024;
    private static final long MAX_BATCH_BYTES=80L*1024*1024;
    private final ReceivingRepository receiving; private final CatalogRepository catalog;
    private final CatalogImportService files; private final ReceivingIntakeService intake;
    private CollaborationRepository collaboration;
    private ReceivingWorkflowRepository workflow;
    public ReceivingController(ReceivingRepository receiving,CatalogRepository catalog,CatalogImportService files,
            ReceivingIntakeService intake){this.receiving=receiving;this.catalog=catalog;this.files=files;this.intake=intake;}
    @Autowired(required=false) void configureCollaboration(CollaborationRepository repository){this.collaboration=repository;}
    @Autowired void configureWorkflow(ReceivingWorkflowRepository repository){this.workflow=repository;}

    String index(Authentication auth,HttpSession session,Model model){return index(auth,session,model,"",0,25);}
    @GetMapping("/app/receiving") String index(Authentication auth,HttpSession session,Model model,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue=com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size){
        if(!(session.getAttribute("selectedTenantId") instanceof UUID))return "redirect:/app/select-account";
        UUID tenant=tenant(session); base(auth,session,model);
        try {
            var documents=workflow==null?new ReceivingWorkflowRepository.DocumentPage(List.of(),0,0,25):workflow.documentPage(tenant,q,page,com.nextaicommerce.platform.web.TablePaging.size(size));
            model.addAttribute("sessions",workflow==null?receiving.sessions(tenant):documents.items());
            model.addAttribute("workDocuments",documents.items());model.addAttribute("documentPage",documents);
            model.addAttribute("query",q);model.addAttribute("receivingTenantId",tenant);
            model.addAttribute("threadsByShipment",collaboration==null?java.util.Map.of():collaboration.openSubjectSummaries(
                tenant,"SHIPMENT",documents.items().stream().map(document->document.sessionId().toString()).distinct().toList(),auth.getName()));
            model.addAttribute("vendors",catalog.listVendorChoices(tenant));
        } catch(Exception e) {
            log.error("Receiving page failed tenantId={}",tenant,e);
            model.addAttribute("sessions",List.of());model.addAttribute("vendors",List.of());model.addAttribute("workDocuments",List.of());
            model.addAttribute("catalogError","Receiving could not be loaded. Nothing was changed; please refresh or contact support if this continues.");
        }
        return "receiving";
    }
    @PostMapping("/app/receiving") String create(Authentication auth,HttpSession session,
            @RequestParam(required=false) String reference,@RequestParam(defaultValue="USD") String currency,
            @RequestParam(defaultValue="0") String freightAmount,@RequestParam(defaultValue="0") String dutyImportAmount,
            @RequestParam(defaultValue="0") String otherSharedCost,@RequestParam(defaultValue="VALUE") String allocationMethod,
            RedirectAttributes redirect){
        try{
            UUID id=receiving.createSession(tenant(session),auth.getName(),reference,currency,money(freightAmount,"freight"),
                money(dutyImportAmount,"duty / import"),money(otherSharedCost,"other landed costs"),allocationMethod);
            return "redirect:/app/receiving/"+id;
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Receiving draft creation failed",e);redirect.addFlashAttribute("catalogError","The receiving draft could not be created. Nothing was saved; please try again.");}
        return "redirect:/app/receiving";
    }
    @PostMapping("/app/receiving/start") String startWithDocument(Authentication auth,HttpSession session,
            @RequestParam(required=false) String vendorId,@RequestParam(defaultValue="INVOICE") String documentType,
            @RequestParam(defaultValue="USD") String currency,@RequestParam(required=false) String reference,
            @RequestParam(defaultValue="0") String freightAmount,@RequestParam(defaultValue="0") String dutyImportAmount,
            @RequestParam(defaultValue="0") String otherSharedCost,@RequestParam(value="files",required=false) List<MultipartFile> uploadFiles,
            RedirectAttributes redirect) {
        try {
            UUID tenant=tenant(session); UUID selectedVendor=vendor(vendorId);
            List<ReceivingIntakeService.IntakeDocument> documents=parseDocuments(uploadFiles);
            UUID id=intake.start(tenant,auth.getName(),selectedVendor,documentType,currency,reference,
                money(freightAmount,"freight"),money(dutyImportAmount,"duty / import"),
                money(otherSharedCost,"other landed costs"),documents);
            redirect.addFlashAttribute("catalogSuccess",documentMessage(documents.size(),"uploaded")+" Review the receiving plan below.");
            log.info("Receiving session {} created tenantId={} vendorId={} documents={}",id,tenant,selectedVendor,documents.size());
            return "redirect:/app/receiving/"+id;
        } catch(IllegalArgumentException e) {
            log.warn("Invoice intake rejected: {}",e.getMessage());
            redirect.addFlashAttribute("catalogError",e.getMessage());
        } catch(Exception e) {
            log.error("Invoice intake failed",e);
            redirect.addFlashAttribute("catalogError","The documents could not be uploaded. Nothing was saved; please check the files and try again.");
        }
        return "redirect:/app/receiving";
    }
    @GetMapping("/app/receiving/{id}") String workspace(@PathVariable UUID id,Authentication auth,HttpSession session,
            Model model,RedirectAttributes redirect){
        if(!(session.getAttribute("selectedTenantId") instanceof UUID))return "redirect:/app/select-account";
        try {
            if(workflow!=null){
                var documentIds=workflow.documentsForSession(tenant(session),id);
                if(!documentIds.isEmpty())return "redirect:/app/receiving/work?documents="+
                    documentIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
            }
            UUID tenant=tenant(session);base(auth,session,model);model.addAttribute("receivingSession",receiving.session(tenant,id));
            var lines=receiving.lines(tenant,id);
            model.addAttribute("documents",receiving.documents(tenant,id));model.addAttribute("vendors",catalog.listVendorChoices(tenant));
            model.addAttribute("locations",catalog.listLocations(tenant));
            model.addAttribute("receiveLines",lines);model.addAttribute("credits",receiving.credits(tenant,id));
            var recentExpirations=receiving.recentExpirationDates(tenant,id);
            model.addAttribute("recentExpirationDates",recentExpirations);
            model.addAttribute("suggestedExpirationDate",recentExpirations.isEmpty()?null:recentExpirations.getFirst());
            model.addAttribute("completedLines",lines.stream().filter(line->line.remainingEach().signum()==0).count());
            return "receiving-workspace";
        } catch(IllegalArgumentException e) {
            redirect.addFlashAttribute("catalogError",e.getMessage());
        } catch(Exception e) {
            log.error("Receiving workspace failed sessionId={}",id,e);
            redirect.addFlashAttribute("catalogError","This receiving could not be opened. Nothing was changed; please try again.");
        }
        return "redirect:/app/receiving";
    }
    @PostMapping("/app/receiving/{id}/documents") String upload(@PathVariable UUID id,Authentication auth,HttpSession session,
            @RequestParam(required=false) String vendorId,@RequestParam(defaultValue="INVOICE") String documentType,
            @RequestParam(defaultValue="USD") String currency,@RequestParam(value="files",required=false) List<MultipartFile> uploadFiles,
            RedirectAttributes redirect){
        try{
            UUID selectedVendor=vendor(vendorId);List<ReceivingIntakeService.IntakeDocument> documents=parseDocuments(uploadFiles);
            intake.addDocuments(tenant(session),id,auth.getName(),selectedVendor,documentType,currency,documents);
            redirect.addFlashAttribute("catalogSuccess",documentMessage(documents.size(),"added")+" Review the PO items before receiving.");
            log.info("Receiving documents added sessionId={} vendorId={} documents={}",id,selectedVendor,documents.size());
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Receiving document upload failed sessionId={}",id,e);redirect.addFlashAttribute("catalogError","The documents could not be uploaded. Nothing from this batch was saved; please try again.");}
        return "redirect:/app/receiving/"+id;
    }

    @PostMapping("/app/receiving/{id}/lines/{lineId}/receive") Object receive(@PathVariable UUID id,
            @PathVariable UUID lineId,Authentication auth,HttpSession session,
            @RequestParam(defaultValue="0") BigDecimal caseQuantity,@RequestParam(defaultValue="0") BigDecimal eachQuantity,
            @RequestParam(defaultValue="1") BigDecimal unitsPerCase,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,@RequestParam(defaultValue="SELLABLE") String disposition,
            @RequestParam(required=false) String notes,@RequestParam(defaultValue="0") BigDecimal depositFeePerUnit,
            @RequestParam(defaultValue="0") BigDecimal otherFeePerUnit,
            @RequestParam(required=false) UUID locationId,
            @RequestParam(defaultValue="false") boolean updateDefaultCost,
            @RequestHeader(name="Accept",required=false) String accept,RedirectAttributes redirect){
        boolean json=accept!=null&&accept.contains("application/json");
        try{
            var progress=locationId==null
                ? receiving.receive(tenant(session),id,lineId,auth.getName(),caseQuantity,eachQuantity,
                    unitsPerCase,expirationDate,disposition,notes,depositFeePerUnit,otherFeePerUnit,updateDefaultCost)
                : receiving.receive(tenant(session),id,lineId,auth.getName(),caseQuantity,eachQuantity,
                    unitsPerCase,expirationDate,disposition,notes,depositFeePerUnit,otherFeePerUnit,locationId,updateDefaultCost);
            String message=switch(disposition){
                case "DAMAGED" -> "Damaged quantity recorded for vendor follow-up. It was not added to sellable inventory.";
                case "MISPICKED" -> "Mispicked quantity recorded for vendor follow-up. It was not added to sellable inventory.";
                case "SHORT_SHIPPED" -> "Short-shipped quantity recorded. No inventory was added.";
                case "SOON_EXPIRED" -> "Soon-to-expire stock received into its expiration batch.";
                case "EXPIRED" -> "Expired stock received and marked as cannot sell.";
                case "OVER_SHIPPED" -> "Over-shipped stock received at zero cost.";
                default -> progress.remainingEach().signum()==0?"Received. Inventory is available now.":"Quantity received. Inventory is available now.";
            };
            if(json)return ResponseEntity.ok(new ReceiveResponse(progress.receivedEach(),progress.remainingEach(),
                progress.completedLines(),progress.totalLines(),progress.sessionStatus(),message));
            redirect.addFlashAttribute("catalogSuccess",message);
        }catch(IllegalArgumentException e){
            if(json)return ResponseEntity.badRequest().body(new ReceiveError(e.getMessage()));
            redirect.addFlashAttribute("catalogError",e.getMessage());
        }catch(Exception e){
            log.error("Receiving quantity failed sessionId={} lineId={}",id,lineId,e);
            String message="The quantity could not be saved. Nothing was changed; please try again.";
            if(json)return ResponseEntity.internalServerError().body(new ReceiveError(message));
            redirect.addFlashAttribute("catalogError",message);
        }
        return "redirect:/app/receiving/"+id;
    }

    Object receive(UUID id,UUID lineId,Authentication auth,HttpSession session,BigDecimal caseQuantity,
            BigDecimal eachQuantity,BigDecimal unitsPerCase,LocalDate expirationDate,String disposition,String notes,
            BigDecimal depositFeePerUnit,BigDecimal otherFeePerUnit,boolean updateDefaultCost,String accept,
            RedirectAttributes redirect){
        return receive(id,lineId,auth,session,caseQuantity,eachQuantity,unitsPerCase,expirationDate,disposition,notes,
            depositFeePerUnit,otherFeePerUnit,null,updateDefaultCost,accept,redirect);
    }

    record ReceiveResponse(BigDecimal receivedEach,BigDecimal remainingEach,int completedLines,
            int totalLines,String sessionStatus,String message){}
    record ReceiveError(String message){}

    @PostMapping("/app/receiving/{id}/lines/{lineId}/discrepancy") String discrepancy(@PathVariable UUID id,
            @PathVariable UUID lineId,Authentication auth,HttpSession session,@RequestParam String reason,
            @RequestParam(required=false) BigDecimal quantity,@RequestParam(required=false) String notes,RedirectAttributes redirect){
        try{receiving.markDiscrepancy(tenant(session),id,lineId,auth.getName(),reason,quantity,notes);
            redirect.addFlashAttribute("catalogSuccess","Discrepancy recorded for the vendor credit report.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Receiving discrepancy failed sessionId={} lineId={}",id,lineId,e);redirect.addFlashAttribute("catalogError","The discrepancy could not be saved. Please try again.");}
        return "redirect:/app/receiving/"+id;
    }

    @PostMapping({"/app/receiving/{id}/post","/app/receiving/{id}/close"}) String post(@PathVariable UUID id,Authentication auth,HttpSession session,RedirectAttributes redirect){
        try{receiving.post(tenant(session),id,auth.getName());redirect.addFlashAttribute("catalogSuccess","Receiving closed. Final landed costs are saved; inventory was already available as each item was received.");}
        catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Inventory posting failed sessionId={}",id,e);redirect.addFlashAttribute("catalogError","Inventory was not posted. Your receiving entries remain saved; please try again.");}
        return "redirect:/app/receiving/"+id;
    }
    @PostMapping("/app/receiving/{id}/lines/{lineId}/reopen") String reopen(@PathVariable UUID id,
            @PathVariable UUID lineId,Authentication auth,HttpSession session,RedirectAttributes redirect){
        try{receiving.reopenLine(tenant(session),id,lineId,auth.getName());
            redirect.addFlashAttribute("catalogSuccess","Invoice line reopened. Its prior inventory was reversed safely; enter the corrected receipt.");}
        catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Receiving correction failed sessionId={} lineId={}",id,lineId,e);
            redirect.addFlashAttribute("catalogError","The invoice line could not be reopened. Nothing was changed; please try again.");}
        return "redirect:/app/receiving/"+id;
    }
    private static void base(Authentication a,HttpSession s,Model m){PageController.addTenantModel(s,m);PageController.addAccessModel(a,m);}
    private static UUID tenant(HttpSession s){Object v=s.getAttribute("selectedTenantId");if(v instanceof UUID id)return id;throw new IllegalStateException("Choose an account first.");}
    private static String safe(String s){return s==null?"document":s.replaceAll("[\\\\/]","_");}
    private static UUID vendor(String value){
        if(value==null||value.isBlank())throw new IllegalArgumentException("Choose the vendor before uploading invoices or packing lists.");
        try{return UUID.fromString(value);}catch(IllegalArgumentException e){throw new IllegalArgumentException("The selected vendor is not valid. Choose the vendor again.");}
    }
    private static BigDecimal money(String value,String label){
        try{BigDecimal amount=new BigDecimal(value==null||value.isBlank()?"0":value.trim());if(amount.signum()<0)throw new NumberFormatException();return amount;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Enter a valid non-negative "+label+" amount.");}
    }
    private List<ReceivingIntakeService.IntakeDocument> parseDocuments(List<MultipartFile> uploadFiles) throws Exception {
        List<MultipartFile> selected=uploadFiles==null?List.of():uploadFiles.stream().filter(file->file!=null&&!file.isEmpty()).toList();
        if(selected.isEmpty())throw new IllegalArgumentException("Choose at least one invoice or packing-list file.");
        if(selected.size()>MAX_DOCUMENTS_PER_UPLOAD)throw new IllegalArgumentException("Upload up to "+MAX_DOCUMENTS_PER_UPLOAD+" documents at a time.");
        long batchBytes=selected.stream().mapToLong(MultipartFile::getSize).sum();
        if(batchBytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("This document batch is larger than 80 MB. Upload it in two smaller batches to the same receiving.");
        List<ReceivingIntakeService.IntakeDocument> parsed=new ArrayList<>();
        for(MultipartFile file:selected){
            String filename=safe(file.getOriginalFilename());
            if(file.getSize()>MAX_DOCUMENT_BYTES)throw new IllegalArgumentException(filename+" is larger than the 20 MB document limit.");
            try{
                byte[] bytes=file.getBytes();var sheet=files.readFile(filename,bytes);
                if(sheet.headers().isEmpty()||sheet.rows().isEmpty())throw new IllegalArgumentException("No invoice product rows were detected.");
                parsed.add(new ReceivingIntakeService.IntakeDocument(filename,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),sheet.rows()));
            }catch(IllegalArgumentException e){throw new IllegalArgumentException(filename+": "+e.getMessage(),e);}
            catch(Exception e){throw new IllegalArgumentException(filename+": we could not identify invoice or packing-list rows. Confirm the file is CSV, TSV, XLS, XLSX, or a text-based PDF.",e);}
        }
        return parsed;
    }
    private static String documentMessage(int count,String verb){return count==1?"Document "+verb+" successfully.":count+" documents "+verb+" successfully.";}
}
