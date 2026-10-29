package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.shopify.*;
import com.nextaicommerce.platform.web.*;
import jakarta.servlet.http.HttpSession;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ShopifySkuController {
    private final ShopifyCatalogJobs jobs;private final ShopifyCatalogWorker worker;private final ShopifyConnectionService connections;
    private final ShopifySkuRepository skus;private final MarketplaceMappingDraft draft;private final WorkspaceAccessRepository access;private final ShopifySkuCsvExport csv;
    public ShopifySkuController(ShopifyCatalogJobs jobs,ShopifyCatalogWorker worker,ShopifyConnectionService connections,ShopifySkuRepository skus,MarketplaceMappingDraft draft,WorkspaceAccessRepository access,ShopifySkuCsvExport csv){this.jobs=jobs;this.worker=worker;this.connections=connections;this.skus=skus;this.draft=draft;this.access=access;this.csv=csv;}
    record Scope(UUID tenant,UUID connection){}
    private void publicationScope(Scope expected,HttpSession session,Authentication actor){if(!expected.equals(scope(session,actor,true)))throw new org.springframework.security.access.AccessDeniedException("The selected account or store changed. Refresh before saving.");}
    Scope scope(HttpSession session,Authentication actor,boolean write){
        if(!jobs.enabled())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(actor==null||!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant)||!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a Shopify store.");
        if(write)jobs.authorize(tenant,connection,actor.getName());
        else if(access.listAccountOptions(actor.getName(),access.isCurrentPlatformAdmin(actor.getName())).stream().filter(a->a.id().equals(tenant)).flatMap(a->a.stores().stream()).noneMatch(s->s.id().equals(connection)&&"SHOPIFY".equals(s.channel())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new Scope(tenant,connection);
    }
    @PostMapping("/app/marketplace-skus/shopify/sync")
    String start(HttpSession session,Authentication actor,RedirectAttributes redirect){
        var s=scope(session,actor,true);
        try{if(!connections.setupState(s.tenant(),s.connection()).productsPermitted())throw new IllegalArgumentException("Refresh installed product permission in Marketplace Connections before syncing.");UUID id=jobs.enqueue(s.tenant(),s.connection(),actor.getName());worker.start(s.tenant(),s.connection(),id);redirect.addFlashAttribute("mappingSuccess",connections.ordersEnabled()?"Shopify SKU sync requested.":"Shopify SKU sync requested. Orders and inventory movements are not enabled.");}
        catch(IllegalArgumentException|IllegalStateException e){redirect.addFlashAttribute("mappingError",e.getMessage());}return "redirect:/app/marketplace-skus";
    }
    @PostMapping("/app/marketplace-skus/shopify/cancel") String cancel(HttpSession session,Authentication actor,RedirectAttributes redirect){var s=scope(session,actor,true);jobs.cancel(s.tenant(),s.connection(),actor.getName());redirect.addFlashAttribute("mappingSuccess","Shopify sync cancelled; imported records and mappings were kept.");return "redirect:/app/marketplace-skus";}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyOrderReadRepository orderReads;
    @GetMapping("/app/marketplace-skus/shopify/components") @ResponseBody
    List<MarketplaceSkuRepository.MappingComponentView> components(@RequestParam UUID variantId,@RequestParam(required=false) UUID orderLineId,HttpSession session,Authentication actor){var s=scope(session,actor,false);if(orderLineId!=null&&orderReads!=null){var frozen=orderReads.frozenComponents(s.tenant(),s.connection(),variantId,orderLineId);if(!frozen.isEmpty())return frozen;}return skus.components(s.tenant(),s.connection(),variantId);}
    @GetMapping("/app/marketplace-skus/shopify/draft") @ResponseBody
    MarketplaceMappingDraft.Suggestion draft(@RequestParam UUID variantId,HttpSession session,Authentication actor){var s=scope(session,actor,true);var row=skus.find(s.tenant(),s.connection(),variantId);return draft.suggest(s.tenant(),row.sku());}
    @PostMapping("/app/marketplace-skus/shopify/mappings")
    String map(@RequestParam(defaultValue="") String returnTo,@RequestParam UUID variantId,@RequestParam String variantRevision,@RequestParam List<String> productId,@RequestParam List<String> quantity,HttpSession session,Authentication actor,RedirectAttributes redirect){var s=scope(session,actor,true);try{skus.save(s.tenant(),s.connection(),variantId,variantRevision,productId,quantity,()->publicationScope(s,session,actor));redirect.addFlashAttribute("mappingSuccess","Shopify variant mapping saved.");}catch(IllegalArgumentException e){redirect.addFlashAttribute("mappingError",e.getMessage());}return returnTo.matches("/app/(?:orders|marketplace-skus)(?:\\?[^\\r\\n]*)?")?"redirect:"+returnTo:"redirect:/app/marketplace-skus";}
    @PostMapping("/app/marketplace-skus/shopify/mappings/clear")
    String clear(@RequestParam(defaultValue="") String returnTo,@RequestParam UUID variantId,HttpSession session,Authentication actor,RedirectAttributes redirect){var s=scope(session,actor,true);try{skus.clear(s.tenant(),s.connection(),variantId,()->publicationScope(s,session,actor));redirect.addFlashAttribute("mappingSuccess","Mapping cleared. Automatic matching stays blocked for this variant.");}catch(IllegalArgumentException e){redirect.addFlashAttribute("mappingError",e.getMessage());}return returnTo.matches("/app/(?:orders|marketplace-skus)(?:\\?[^\\r\\n]*)?")?"redirect:"+returnTo:"redirect:/app/marketplace-skus";}
    @GetMapping("/app/marketplace-skus/shopify/export.csv") @ResponseBody
    ResponseEntity<byte[]> export(@RequestParam Map<String,String> params,HttpSession session,Authentication actor){var s=scope(session,actor,false);var artifact=csv.generate(s.tenant(),s.connection(),params);if(!s.equals(scope(session,actor,false)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Workspace changed. Retry export.");String date=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(artifact.asOf());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"marketplace-skus-"+date+".csv\"").header("X-CSV-Schema","shopify-skus-v1").header("X-CSV-Rows",Integer.toString(artifact.rows())).contentLength(artifact.bytes().length).body(artifact.bytes());}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,error.getMessage()));}
}
