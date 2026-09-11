package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ShippingDeskController {
    private final ShippingDeskService service;
    public ShippingDeskController(ShippingDeskService service){this.service=service;}

    @GetMapping("/app/shipping") String page(Authentication authentication,HttpSession session,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(authentication,model);connection(session);return "shipping";
    }
    @GetMapping("/app/shipping/data") @ResponseBody ShippingDeskService.Workspace data(HttpSession session){return service.workspace(tenant(session),connection(session));}
    @PostMapping("/app/shipping/batches") @ResponseBody ResponseEntity<Map<String,Object>> create(@RequestBody ShippingDeskService.CreateBatch request,
            Principal principal,HttpSession session){UUID id=service.create(tenant(session),connection(session),request,principal.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("batchId",id,"state","RATING"));}
    @GetMapping("/app/shipping/batches/{batchId}") @ResponseBody ShippingDeskService.BatchDetail batch(@PathVariable UUID batchId,HttpSession session){
        return service.batch(tenant(session),connection(session),batchId);}
    @PostMapping("/app/shipping/batches/{batchId}/purchase") @ResponseBody ResponseEntity<Map<String,String>> purchase(@PathVariable UUID batchId,
            @RequestBody ShippingDeskService.ConfirmBatch request,Principal principal,HttpSession session){
        service.purchase(tenant(session),connection(session),batchId,request,principal.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("state","PURCHASE_QUEUED"));}
    @PostMapping("/app/shipping/batches/{batchId}/discard") @ResponseBody Map<String,String> discard(@PathVariable UUID batchId,HttpSession session){
        service.discard(tenant(session),connection(session),batchId);return Map.of("state","FAILED");}
    @GetMapping(value="/app/shipping/batches/{batchId}/labels",produces=MediaType.APPLICATION_PDF_VALUE) @ResponseBody
    ResponseEntity<byte[]> labels(@PathVariable UUID batchId,HttpSession session){byte[] pdf=service.batchPdf(tenant(session),connection(session),batchId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            "inline; filename=shipping-batch-"+batchId+".pdf").body(pdf);}
    @PostMapping("/app/shipping/policy") @ResponseBody ShippingDeskRepository.PolicySettings policy(@RequestBody ShippingDeskRepository.PolicySettings input,HttpSession session){
        return service.savePolicy(tenant(session),connection(session),input);}

    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex){return error(HttpStatus.BAD_REQUEST,ex.getMessage());}
    @ExceptionHandler(IllegalStateException.class) @ResponseBody
    ResponseEntity<Map<String,String>> conflict(IllegalStateException ex){return error(HttpStatus.CONFLICT,ex.getMessage());}
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String message){return ResponseEntity.status(status).body(Map.of("message",message==null?"Request failed.":message));}
    private static UUID tenant(HttpSession session){Object id=session.getAttribute(AccountSelectionController.TENANT_ID);if(id instanceof UUID value)return value;throw new IllegalArgumentException("Choose an account first.");}
    private static UUID connection(HttpSession session){Object id=session.getAttribute(AccountSelectionController.STORE_ID);if(id instanceof UUID value)return value;throw new IllegalArgumentException("Choose an Amazon store first.");}
}
