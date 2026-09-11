package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;

@Controller
public class MarketplaceSkuController {
    private static final Logger log=LoggerFactory.getLogger(MarketplaceSkuController.class);
    private final MarketplaceSkuRepository skus;
    private final WorkspaceAccessRepository workspace;
    private final CatalogRepository catalog;
    private CollaborationRepository collaboration;

    MarketplaceSkuController(MarketplaceSkuRepository skus, WorkspaceAccessRepository workspace,
            CatalogRepository catalog) {
        this.skus = skus;
        this.workspace = workspace;
        this.catalog = catalog;
    }
    @Autowired(required=false)
    void configureCollaboration(CollaborationRepository repository){this.collaboration=repository;}

    @GetMapping("/app/marketplace-skus")
    String marketplaceSkus(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "status") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer goToPage,
            @RequestParam(defaultValue = com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size,
            Authentication authentication, HttpSession session, Model model) {
        if (!PageController.addTenantModel(session, model)) return "redirect:/app/select-account";
        PageController.addAccessModel(authentication, model);
        model.addAttribute("query", q == null ? "" : q.trim());
        model.addAttribute("selectedStatus", normalizeStatus(status));
        model.addAttribute("selectedSort", normalizeSort(sort));
        model.addAttribute("sortDirection", "desc".equalsIgnoreCase(direction)?"desc":"asc");
        model.addAttribute("selectedStoreName", session.getAttribute(AccountSelectionController.STORE_NAME));

        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        Object selectedStore = session.getAttribute(AccountSelectionController.STORE_ID);
        if (!(selectedStore instanceof UUID connectionId)) {
            model.addAttribute("storeSelectionRequired", true);
            model.addAttribute("chooseStore", true);
            return "marketplace-skus";
        }

        var connection = workspace.findConnection(tenantId, connectionId);
        if (!"AMAZON".equalsIgnoreCase(connection.channel())) {
            model.addAttribute("unsupportedChannel", true);
            model.addAttribute("chooseStore", true);
            return "marketplace-skus";
        }

        String normalizedStatus = normalizeStatus(status);
        model.addAttribute("selectedStatus", normalizedStatus);
        model.addAttribute("summary", skus.summary(tenantId, connectionId));
        int requestedPage=goToPage==null?page:Math.max(0,goToPage-1);
        var skuPage=skus.list(tenantId, connectionId, q, normalizedStatus,
            normalizeSort(sort),"desc".equalsIgnoreCase(direction)?"desc":"asc",requestedPage, com.nextaicommerce.platform.web.TablePaging.size(size));
        model.addAttribute("skuPage",skuPage);
        model.addAttribute("salesAsOf",java.time.Instant.now());
        model.addAttribute("threadsByMarketplaceSku",collaboration==null?java.util.Map.of():collaboration.openSubjectSummaries(
            tenantId,"MARKETPLACE_SKU",skuPage.rows().stream().map(MarketplaceSkuRepository.SkuView::sellerSku).toList(),authentication.getName()));
        model.addAttribute("mappingComponents",skus.mappingComponents(tenantId,connectionId,
            skuPage.rows().stream().map(MarketplaceSkuRepository.SkuView::sellerSku).toList()));
        model.addAttribute("vendors",Boolean.TRUE.equals(model.asMap().get("canEditCatalog"))
            ?catalog.listVendorChoices(tenantId):List.of());
        return "marketplace-skus";
    }

    @PostMapping("/app/marketplace-skus/mappings")
    String saveMapping(@RequestParam String sellerSku,
            @RequestParam(name="productId") List<String> productIds,
            @RequestParam(name="quantity") List<String> quantities,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="status") String sort,@RequestParam(defaultValue="asc") String direction,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String returnTo,
            HttpSession session,RedirectAttributes redirect){
        try{
            UUID tenantId=requiredTenant(session);UUID connectionId=requiredAmazonConnection(tenantId,session);
            skus.saveMapping(tenantId,connectionId,sellerSku,productIds,quantities);
            redirect.addFlashAttribute("mappingSuccess","Marketplace SKU mapping saved.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("mappingError",e.getMessage());}
        catch(Exception e){log.error("Marketplace SKU mapping failed sellerSku={}",sellerSku,e);
            redirect.addFlashAttribute("mappingError","The mapping could not be saved. Nothing was changed; please try again.");}
        return safeReturn(returnTo,redirectView(q,status,sort,direction,page));
    }

    @GetMapping("/app/catalog/product-search")
    @ResponseBody
    List<CatalogProductOption> productSearch(@RequestParam(defaultValue="") String q,HttpSession session){
        UUID tenantId=requiredTenant(session);
        return catalog.searchAccountItems(tenantId,q,20).stream()
            .map(item->new CatalogProductOption(item.id(),item.name(),item.brand(),item.vendorItemCode(),
                item.identifier(),item.accountSku(),item.imageUrl(),item.expirationRequired()))
            .toList();
    }

    @GetMapping("/app/marketplace-skus/mappings/components")
    @ResponseBody
    List<MarketplaceSkuRepository.MappingComponentView> mappingComponents(@RequestParam String sku,HttpSession session){
        UUID tenantId=requiredTenant(session);UUID connectionId=requiredAmazonConnection(tenantId,session);
        return skus.mappingComponents(tenantId,connectionId,List.of(sku)).getOrDefault(sku,List.of());
    }

    record CatalogProductOption(UUID id,String name,String brand,String vendorItemCode,String identifier,
            String accountSku,String imageUrl,boolean expirationRequired){}

    @PostMapping("/app/marketplace-skus/mappings/clear")
    String clearMapping(@RequestParam String sellerSku,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="status") String sort,@RequestParam(defaultValue="asc") String direction,
            @RequestParam(defaultValue="0") int page,HttpSession session,RedirectAttributes redirect){
        try{
            UUID tenantId=requiredTenant(session);UUID connectionId=requiredAmazonConnection(tenantId,session);
            skus.clearMapping(tenantId,connectionId,sellerSku);
            redirect.addFlashAttribute("mappingSuccess","Mapping cleared. Automatic matching is paused for this SKU.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("mappingError",e.getMessage());}
        catch(Exception e){log.error("Marketplace SKU mapping clear failed sellerSku={}",sellerSku,e);
            redirect.addFlashAttribute("mappingError","The mapping could not be cleared. Nothing was changed.");}
        return redirectView(q,status,sort,direction,page);
    }

    private static String normalizeStatus(String status) {
        if (status == null) return "ALL";
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        return java.util.Set.of("ALL", "ACTIVE", "OOS", "INACTIVE", "AMAZON_PROBLEM", "DELETED", "UNMAPPED").contains(normalized)
            ? normalized : "ALL";
    }

    private static String normalizeSort(String sort){
        return java.util.Set.of("listing","sku","status","price","buyBox","fulfillment","available",
            "fourWeek","sales30","fees","profit","catalog").contains(sort)?sort:"status";
    }

    private static UUID requiredTenant(HttpSession session){
        Object value=session.getAttribute(AccountSelectionController.TENANT_ID);
        if(value instanceof UUID tenantId)return tenantId;
        throw new IllegalArgumentException("Choose an account before mapping a marketplace SKU.");
    }
    private UUID requiredAmazonConnection(UUID tenantId,HttpSession session){
        Object value=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(value instanceof UUID connectionId))throw new IllegalArgumentException("Choose an Amazon store before mapping a marketplace SKU.");
        var connection=workspace.findConnection(tenantId,connectionId);
        if(!"AMAZON".equalsIgnoreCase(connection.channel()))throw new IllegalArgumentException("Choose an Amazon store before mapping a marketplace SKU.");
        return connectionId;
    }
    private static String redirectView(String q,String status,String sort,String direction,int page){
        String target=UriComponentsBuilder.fromPath("/app/marketplace-skus")
            .queryParam("q",q==null?"":q).queryParam("status",normalizeStatus(status))
            .queryParam("sort",normalizeSort(sort)).queryParam("direction","desc".equalsIgnoreCase(direction)?"desc":"asc")
            .queryParam("page",Math.max(0,page)).build().encode().toUriString();
        return "redirect:"+target;
    }
    private static String safeReturn(String returnTo,String fallback){
        return returnTo!=null && returnTo.matches("/app/orders(?:\\?[^\\r\\n]*)?") ? "redirect:"+returnTo : fallback;
    }
}
