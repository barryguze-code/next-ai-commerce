package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One workspace for independently governed source documents; never merges their receipts or costs. */
@Controller
public class ReceivingWorkflowController {
    private static final Logger log=LoggerFactory.getLogger(ReceivingWorkflowController.class);
    private final ReceivingWorkflowRepository workflow;
    private final CatalogRepository catalog;
    public ReceivingWorkflowController(ReceivingWorkflowRepository workflow,CatalogRepository catalog){
        this.workflow=workflow;this.catalog=catalog;
    }
    @GetMapping("/app/receiving/work")
    String work(@RequestParam List<UUID> documents,Authentication auth,HttpSession session,Model model,RedirectAttributes redirect){
        if(!(session.getAttribute("selectedTenantId") instanceof UUID))return "redirect:/app/select-account";
        try{
            var ids=selection(documents);var docs=workflow.documents(tenant(session),ids);
            if(docs.size()!=ids.size())throw new IllegalArgumentException("One of these documents is unavailable in this account. Choose the documents again.");
            if(ids.size()>1 && docs.stream().anyMatch(d -> d.closed() || d.outstanding().signum()<=0))
                throw new IllegalArgumentException("A selected document is closed or fully received. Select only documents with outstanding quantities to receive together; review completed documents individually.");
            PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
            model.addAttribute("workDocuments",docs);
            model.addAttribute("workLines",workflow.lines(tenant(session),ids));
            model.addAttribute("locations",catalog.listLocations(tenant(session)));
            return "receiving-work";
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());return "redirect:/app/receiving";}
    }
    @GetMapping("/app/receiving/work/state")
    @ResponseBody
    Object state(@RequestParam List<UUID> documents,HttpSession session){
        var ids=selection(documents);
        return Map.of("documents",workflow.documents(tenant(session),ids),"lines",workflow.lines(tenant(session),ids));
    }
    @GetMapping("/app/receiving/work/lines/{line}/receipts")
    @ResponseBody
    Object receipts(@PathVariable UUID line,HttpSession session){return workflow.receipts(tenant(session),line);}

    public record Command(UUID requestId,BigDecimal quantity,LocalDate expiration,String disposition,UUID location,
            UUID receipt,String direction,String reason,String notes,Boolean creditExpected,
            BigDecimal previewReceived,BigDecimal previewOutstanding,
            BigDecimal unitsPerCase,BigDecimal depositFee,BigDecimal otherFee){
        public Command(UUID requestId,BigDecimal quantity,LocalDate expiration,String disposition,UUID location,
                UUID receipt,String direction,String reason,String notes,Boolean creditExpected,BigDecimal previewReceived,BigDecimal previewOutstanding){
            this(requestId,quantity,expiration,disposition,location,receipt,direction,reason,notes,creditExpected,previewReceived,previewOutstanding,null,null,null);
        }
    }

    @PostMapping("/app/receiving/work/{operation}/{target}")
    @ResponseBody
    ResponseEntity<?> change(@PathVariable String operation,@PathVariable UUID target,@RequestBody Command command,
            Authentication auth,HttpSession session){
        try{
            if(command.notes()!=null&&command.notes().length()>500)throw new IllegalArgumentException("Keep notes within 500 characters.");
            UUID tenant=tenant(session);String actor=auth.getName();
            Runnable mutation=switch(operation){
                case "prepare" -> ()->workflow.prepare(tenant,actor,target,command.unitsPerCase(),command.depositFee(),command.otherFee());
                case "receive" -> ()->workflow.receive(tenant,actor,target,command.quantity(),command.expiration(),
                    command.disposition(),command.location(),command.notes());
                case "undo" -> ()->workflow.undo(tenant,actor,target,command.receipt(),command.reason());
                case "adjust" -> ()->workflow.adjust(tenant,actor,target,command.receipt(),command.quantity(),
                    command.direction(),command.reason(),command.notes());
                case "close" -> ()->workflow.closeDocument(tenant,actor,target,command.reason(),Boolean.TRUE.equals(command.creditExpected()),
                    command.previewReceived(),command.previewOutstanding());
                case "remove" -> ()->workflow.removeDocument(tenant,actor,target);
                default -> throw new IllegalArgumentException("Choose a supported receiving action.");
            };
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(command.toString().getBytes(StandardCharsets.UTF_8)));
            boolean changed=workflow.execute(tenant,command.requestId(),operation,target,fingerprint,mutation);
            String message=changed?switch(operation){
                case "prepare" -> "Pack and item fees saved. No stock has been added.";
                case "receive" -> "Receipt saved. This document remains open until you close it.";
                case "undo" -> "Receipt undone. The original receipt and reversal remain in history.";
                case "adjust" -> "Inventory adjusted. The original receipt is unchanged.";
                case "close" -> "Document closed. Received stock remains in inventory; its receipts are now locked.";
                default -> "Unused document removed from the queue. Its audit record is retained.";
            }:"This action was already saved. No duplicate inventory movement was made.";
            return ResponseEntity.ok(Map.of("message",message));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
        catch(Exception e){
            log.error("Receiving workflow action failed operation={} target={}",operation,target,e);
            return ResponseEntity.internalServerError().body(Map.of("message","This action could not be confirmed. Retry the same action safely; its reference prevents duplicate stock changes."));
        }
    }
    static List<UUID> selection(List<UUID> ids){
        if(ids==null||ids.isEmpty()||ids.size()>12||ids.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Choose between 1 and 12 invoices or packing lists.");
        return ids.stream().distinct().toList();
    }
    private static UUID tenant(HttpSession session){
        if(session.getAttribute("selectedTenantId") instanceof UUID id)return id;
        throw new IllegalArgumentException("Choose an account first.");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    ResponseEntity<?> validation(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));}
}
