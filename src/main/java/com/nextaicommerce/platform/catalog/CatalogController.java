package com.nextaicommerce.platform.catalog;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class CatalogController {
    private static final Logger log=LoggerFactory.getLogger(CatalogController.class);
    private final CatalogRepository catalog;
    private final CatalogImportService imports;
    private CatalogImportWorker importWorker;
    private CatalogImportProgress importProgress;
    private CollaborationRepository collaboration;
    @Autowired private CatalogReadService reads;

    public CatalogController(CatalogRepository catalog, CatalogImportService imports) {
        this.catalog = catalog;
        this.imports = imports;
    }
    @Autowired(required=false)
    void configureImportWorker(CatalogImportWorker worker,CatalogImportProgress progress){this.importWorker=worker;this.importProgress=progress;}
    @Autowired(required=false)
    void configureCollaboration(CollaborationRepository repository){this.collaboration=repository;}

    @GetMapping("/app/platform/catalog")
    String globalCatalog(Authentication authentication, HttpSession session, Model model) {
        PageController.addTenantModel(session, model);
        PageController.addAccessModel(authentication, model);
        model.addAttribute("products", catalog.listGlobalProducts());
        model.addAttribute("globalProductImageIds",catalog.globalProductImageIds());
        return "global-catalog";
    }

    @GetMapping("/app/catalog")
    String accountCatalog(Authentication authentication, HttpSession session, Model model,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue=com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size,@RequestParam(required=false) Integer goToPage,
            @RequestParam(required=false) String open) {
        UUID tenantId = tenantId(session);
        if (tenantId == null) return "redirect:/app/select-account";
        PageController.addTenantModel(session, model);
        PageController.addAccessModel(authentication, model);
        int requestedPage=goToPage==null?page:Math.max(0,goToPage-1);
        var snapshot=reads.page(tenantId,q,requestedPage,size);
        var catalogPage=snapshot.page();
        model.addAttribute("items",catalogPage.rows());
        model.addAttribute("catalogPage",catalogPage);
        model.addAttribute("query",q);
        model.addAttribute("openPanel",open);
        model.addAttribute("vendors",snapshot.vendors());
        model.addAttribute("locations",snapshot.locations());
        var visibleIds=catalogPage.rows().stream().map(CatalogRepository.AccountItemView::id).toList();
        model.addAttribute("threadsByCatalogItem",collaboration==null?Map.of():collaboration.openSubjectSummaries(
            tenantId,"CATALOG",visibleIds.stream().map(UUID::toString).toList(),authentication.getName()));
        model.addAttribute("offersByItem",snapshot.offers());
        model.addAttribute("skusByItem",snapshot.skus());
        return "account-catalog";
    }

    @PostMapping("/app/catalog/products/{itemId}/image")
    String uploadProductImage(@PathVariable UUID itemId,Authentication authentication,HttpSession session,
            @RequestParam("image") MultipartFile image,@RequestParam(defaultValue="/app/catalog") String returnTo,
            RedirectAttributes redirect){
        try{
            if(image==null||image.isEmpty())throw new IllegalArgumentException("Choose a PNG, JPEG, or WebP product image.");
            if(image.getSize()>4_000_000)throw new IllegalArgumentException("Use a product image smaller than 4 MB.");
            String type=image.getContentType()==null?"":image.getContentType();
            if(!java.util.Set.of("image/png","image/jpeg","image/webp").contains(type))throw new IllegalArgumentException("Use a PNG, JPEG, or WebP product image.");
            catalog.saveProductImage(requiredTenantId(session),authentication.getName(),itemId,type,image.getOriginalFilename(),image.getBytes());
            redirect.addFlashAttribute("/app/inventory".equals(returnTo)?"inventorySuccess":"catalogSuccess","Product picture updated for this account.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("/app/inventory".equals(returnTo)?"inventoryError":"catalogError",e.getMessage());}
        catch(Exception e){log.error("Product image upload failed itemId={}",itemId,e);redirect.addFlashAttribute("/app/inventory".equals(returnTo)?"inventoryError":"catalogError","The product picture could not be saved.");}
        return "redirect:"+("/app/inventory".equals(returnTo)?returnTo:"/app/catalog");
    }

    @PostMapping("/app/catalog/products/{itemId}/image/upload")
    @org.springframework.web.bind.annotation.ResponseBody
    ResponseEntity<?> uploadMappingProductImage(@PathVariable UUID itemId,Authentication authentication,
            HttpSession session,@RequestParam("image") MultipartFile image) throws java.io.IOException {
        try{
            if(image==null||image.isEmpty()||image.getSize()>5_000_000
                    ||!java.util.Set.of("image/png","image/jpeg").contains(image.getContentType()==null?"":image.getContentType()))
                return ResponseEntity.badRequest().body(java.util.Map.of("error","Choose a JPG or PNG up to 5 MB."));
            catalog.saveProductImage(requiredTenantId(session),authentication.getName(),itemId,com.nextaicommerce.platform.orders.OrderPictureController.imageType(image.getBytes()),image.getOriginalFilename(),image.getBytes());
            return ResponseEntity.ok(java.util.Map.of("imageUrl","/app/catalog/products/"+itemId+"/image"));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(java.util.Map.of("error","The picture could not be saved."));}
    }

    @GetMapping("/app/catalog/products/{itemId}/image") @org.springframework.web.bind.annotation.ResponseBody
    ResponseEntity<byte[]> productImage(@PathVariable UUID itemId,HttpSession session){
        var image=catalog.productImage(requiredTenantId(session),itemId);
        if(image==null){
            String fallback=catalog.pickerImages(requiredTenantId(session),java.util.List.of(itemId)).get(itemId);
            if(fallback!=null && (fallback.startsWith("https://")||fallback.startsWith("http://")))
                return ResponseEntity.status(302).location(java.net.URI.create(fallback)).cacheControl(org.springframework.http.CacheControl.noCache()).build();
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(org.springframework.http.CacheControl.noCache()).body(image.bytes());
    }

    @GetMapping("/app/catalog/products/{itemId}/marketplace-skus") @ResponseBody
    java.util.List<CatalogRepository.MarketplaceSkuRef> marketplaceSkus(@PathVariable UUID itemId,HttpSession session){
        return catalog.listMarketplaceSkus(requiredTenantId(session),java.util.List.of(itemId));
    }

    @PostMapping("/app/catalog/products")
    String addProduct(Authentication authentication, HttpSession session,
            @RequestParam String name, @RequestParam(required=false) String brand,
            @RequestParam(defaultValue="UPC") String identifierType,
            @RequestParam(required=false) String identifier,
            @RequestParam(required=false) String accountSku,
            @RequestParam(defaultValue="false") boolean expirationRequired,
            RedirectAttributes redirect) {
        catalog.addAccountProduct(requiredTenantId(session), authentication.getName(), name, brand,
            identifierType, identifier, accountSku, expirationRequired);
        redirect.addFlashAttribute("catalogSuccess", "Product added to this account’s catalogue.");
        return "redirect:/app/catalog";
    }

    @PostMapping("/app/catalog/product-options") @ResponseBody
    ResponseEntity<Map<String,Object>> addProductOption(Authentication authentication,HttpSession session,
            @RequestParam String name,@RequestParam String accountSku,
            @RequestParam(required=false) String brand,
            @RequestParam(required=false) UUID vendorId,@RequestParam(required=false) String identifier,
            @RequestParam(defaultValue="UPC") String identifierType,@RequestParam(required=false) BigDecimal unitCost,
            @RequestParam(defaultValue="false") boolean expirationRequired){
        try{
            if(name==null||name.isBlank())throw new IllegalArgumentException("Enter a product name.");
            if(accountSku==null||accountSku.isBlank())throw new IllegalArgumentException("Enter the item code from the count file.");
            UUID tenantId=requiredTenantId(session);UUID id;
            if(vendorId==null)id=catalog.addAccountProduct(tenantId,authentication.getName(),name,brand,
                identifierType,identifier,accountSku,expirationRequired);
            else{
                if(unitCost==null||unitCost.signum()<0)throw new IllegalArgumentException("Enter the current unit cost for this vendor.");
                id=catalog.addImportedVendorProduct(tenantId,authentication.getName(),vendorId,accountSku,name,brand,
                    identifierType,identifier,accountSku,expirationRequired);
                catalog.saveVendorOffer(tenantId,authentication.getName(),id,vendorId,accountSku,unitCost,BigDecimal.ZERO,"USD");
            }
            return ResponseEntity.ok(Map.of("id",id.toString(),"name",name.trim(),"code",accountSku.trim()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(Exception e){log.error("Inline catalogue product creation failed accountSku={}",accountSku,e);
            return ResponseEntity.internalServerError().body(Map.of("error","The product could not be added. Nothing was changed."));}
    }

    @PostMapping("/app/catalog/mapping-product-options") @ResponseBody
    ResponseEntity<Map<String,Object>> addMappingProductOption(Authentication authentication,HttpSession session,
            @RequestParam String name,@RequestParam String accountSku,@RequestParam(required=false) String brand,
            @RequestParam(required=false) UUID vendorId,@RequestParam(required=false) String newVendorName,
            @RequestParam(required=false) String newVendorCode,@RequestParam(defaultValue="USD") String newVendorCurrency,
            @RequestParam(required=false) String identifier,@RequestParam(defaultValue="UPC") String identifierType,
            @RequestParam(required=false) BigDecimal unitCost,@RequestParam(defaultValue="false") boolean expirationRequired){
        try{
            if(name==null||name.isBlank())throw new IllegalArgumentException("Enter a product name.");
            if(accountSku==null||accountSku.isBlank())throw new IllegalArgumentException("Enter an account item code.");
            var created=catalog.addMarketplaceMappingProduct(requiredTenantId(session),authentication.getName(),name,brand,
                identifierType,identifier,accountSku,expirationRequired,vendorId,newVendorName,newVendorCode,
                newVendorCurrency,unitCost);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",created.productId().toString());result.put("name",name.trim());result.put("accountSku",accountSku.trim());
            result.put("brand",brand==null?"":brand.trim());result.put("identifier",identifier==null?"":identifier.trim());
            result.put("vendorItemCode",accountSku.trim());result.put("imageUrl","");
            if(created.vendorId()!=null){result.put("vendorId",created.vendorId().toString());result.put("vendorName",created.vendorName());}
            return ResponseEntity.ok(result);
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(Exception e){log.error("Marketplace mapping product creation failed accountSku={}",accountSku,e);
            return ResponseEntity.internalServerError().body(Map.of("error","The catalogue item could not be added. Nothing was changed."));}
    }

    @PostMapping("/app/catalog/locations")
    String addLocation(HttpSession session,@RequestParam String code,@RequestParam String name,
            RedirectAttributes redirect){
        try{
            catalog.addLocation(requiredTenantId(session),code,name);
            redirect.addFlashAttribute("catalogSuccess","Inventory location added.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Inventory location creation failed",e);redirect.addFlashAttribute("catalogError","The location could not be added. Nothing was changed.");}
        return "redirect:/app/catalog";
    }

    @PostMapping("/app/catalog/location-options") @ResponseBody
    ResponseEntity<Map<String,Object>> addLocationOption(HttpSession session,@RequestParam String code,@RequestParam String name){
        try{
            UUID id=catalog.addLocation(requiredTenantId(session),code,name);
            return ResponseEntity.ok(Map.of("id",id.toString(),"code",code.trim().toUpperCase(java.util.Locale.ROOT),"name",name.trim()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(Exception e){log.error("Inventory location creation failed",e);return ResponseEntity.internalServerError().body(Map.of("error","The location could not be added. Nothing was changed."));}
    }

    @PostMapping("/app/catalog/location-options/{id}/change") @ResponseBody
    ResponseEntity<Map<String,Object>> changeLocationOption(HttpSession session,@PathVariable UUID id,
            @RequestParam(defaultValue="") String code,@RequestParam(defaultValue="") String name,
            @RequestParam(defaultValue="false") boolean delete){
        try{
            catalog.changeLocation(requiredTenantId(session),id,code,name,delete);
            return ResponseEntity.ok(Map.of("id",id.toString(),"deleted",delete,"code",code.trim().toUpperCase(java.util.Locale.ROOT),"name",name.trim()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(org.springframework.dao.DataIntegrityViolationException e){return ResponseEntity.badRequest().body(Map.of("error","The location is now referenced by another record, or its code is already in use. Nothing was changed. Refresh and try again."));}
        catch(Exception e){log.error("Location change failed id={}",id,e);return ResponseEntity.internalServerError().body(Map.of("error","The location could not be changed. Nothing was changed."));}
    }

    @PostMapping("/app/catalog/products/{itemId}/default-location")
    String setDefaultLocation(@PathVariable UUID itemId,HttpSession session,@RequestParam UUID locationId,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,RedirectAttributes redirect){
        try{
            catalog.setDefaultLocation(requiredTenantId(session),itemId,locationId);
            redirect.addFlashAttribute("catalogSuccess","Default inventory location updated. Existing batches kept their current locations.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Default location update failed itemId={}",itemId,e);redirect.addFlashAttribute("catalogError","The default location could not be changed.");}
        if(q!=null&&!q.isBlank())redirect.addAttribute("q",q);
        if(page>0)redirect.addAttribute("page",page);
        return "redirect:/app/catalog";
    }

    @PostMapping("/app/catalog/vendors")
    String addVendor(HttpSession session, @RequestParam String name,
            @RequestParam(required=false) String code, @RequestParam(defaultValue="USD") String currency,
            @RequestParam(defaultValue="0") BigDecimal discountRate,
            @RequestParam(defaultValue="0") BigDecimal defaultFreightAmount,
            @RequestParam(defaultValue="/app/catalog") String returnTo, RedirectAttributes redirect) {
        try{
            catalog.addVendor(requiredTenantId(session),name,code,currency,discountRate,defaultFreightAmount);
            boolean receivingFlow=returnTo!=null&&returnTo.startsWith("/app/receiving");
            redirect.addFlashAttribute("catalogSuccess",receivingFlow
                ?"Vendor saved. You are now on Step 2—select this vendor and upload the supplier documents."
                :"Vendor added. You can now upload its invoice or catalogue.");
            if(receivingFlow)redirect.addFlashAttribute("vendorCreated",true);
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Vendor creation failed",e);redirect.addFlashAttribute("catalogError","The vendor could not be added. Please try again.");}
        return redirectTo(returnTo);
    }

    @GetMapping("/app/vendors")
    String vendors(Authentication authentication, HttpSession session, Model model) {
        UUID tenantId = requiredTenantId(session);
        PageController.addTenantModel(session, model);
        PageController.addAccessModel(authentication, model);
        model.addAttribute("vendors", catalog.listVendors(tenantId));
        return "vendors";
    }

    @PostMapping("/app/vendors/{vendorId}")
    String updateVendor(@PathVariable UUID vendorId, HttpSession session, @RequestParam String name,
            @RequestParam(required=false) String code, @RequestParam(defaultValue="USD") String currency,
            @RequestParam(defaultValue="0") BigDecimal discountRate,
            @RequestParam(defaultValue="0") BigDecimal defaultFreightAmount,
            @RequestParam(defaultValue="ACTIVE") String status, RedirectAttributes redirect) {
        try{catalog.updateVendor(requiredTenantId(session),vendorId,name,code,currency,discountRate,defaultFreightAmount,status);
            redirect.addFlashAttribute("catalogSuccess","Vendor details updated.");}
        catch(IllegalArgumentException e){redirect.addFlashAttribute("catalogError",e.getMessage());}
        catch(Exception e){log.error("Vendor update failed vendorId={}",vendorId,e);redirect.addFlashAttribute("catalogError","The vendor could not be updated. Please try again.");}
        return "redirect:/app/vendors";
    }

    @PostMapping("/app/catalog/offers")
    String saveOffer(Authentication authentication, HttpSession session,
            @RequestParam UUID itemId, @RequestParam UUID vendorId,
            @RequestParam(required=false) String vendorItemCode, @RequestParam BigDecimal listCost,
            @RequestParam(defaultValue="0") BigDecimal discountRate,
            @RequestParam(defaultValue="USD") String currency, RedirectAttributes redirect) {
        catalog.saveVendorOffer(requiredTenantId(session), authentication.getName(), itemId, vendorId,
            vendorItemCode, listCost, discountRate, currency);
        redirect.addFlashAttribute("catalogSuccess", "Vendor price and discount were saved.");
        return "redirect:/app/catalog";
    }

    @PostMapping("/app/catalog/imports")
    String uploadImport(Authentication authentication, HttpSession session,
            @RequestParam UUID vendorId, @RequestParam("file") MultipartFile file,
            RedirectAttributes redirect) {
        try {
            UUID id = imports.stage(requiredTenantId(session), authentication.getName(), vendorId, file);
            return "redirect:/app/catalog/imports/" + id;
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("catalogError", e.getMessage());
            return "redirect:/app/catalog";
        }
    }

    @GetMapping("/app/catalog/imports/{importId}")
    String mapImport(@PathVariable UUID importId, Authentication authentication,
            HttpSession session, Model model) {
        UUID tenantId = tenantId(session);
        if (tenantId == null) return "redirect:/app/select-account";
        PageController.addTenantModel(session, model);
        PageController.addAccessModel(authentication, model);
        var catalogImport=imports.load(tenantId,importId);
        if("QUEUED".equals(catalogImport.processState())||"PROCESSING".equals(catalogImport.processState())
                ||"MAPPING".equals(catalogImport.processState())||"COMPLETED".equals(catalogImport.processState()))
            return "redirect:/app/catalog?catalogImportJob="+importId;
        model.addAttribute("catalogImport",catalogImport);
        return "catalog-import";
    }

    @GetMapping("/app/catalog/imports/{importId}/progress") @ResponseBody
    ResponseEntity<CatalogImportJobView> importProgress(@PathVariable UUID importId,HttpSession session){
        UUID tenantId=tenantId(session);if(tenantId==null)return ResponseEntity.status(409).build();
        try{
            var job=imports.load(tenantId,importId);
            return ResponseEntity.ok(new CatalogImportJobView(job.processState(),catalogImportPercent(job.processState(),job.progressPercent()),
                catalogImportPhase(job.processState(),job.processedRows(),job.totalRows()),job.processError(),
                job.filename(),job.vendorName(),job.totalRows(),job.mappedSkus(),job.unmappedSkus()));
        }catch(IllegalArgumentException missing){return ResponseEntity.notFound().build();}
    }

    @PostMapping("/app/catalog/imports/{importId}/approve")
    String approveImport(@PathVariable UUID importId, @RequestParam Map<String,String> parameters,
            Authentication authentication, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId=tenantId(session);
        if(tenantId==null)return "redirect:/app/select-account";
        Map<String,String> mapping=importMapping(parameters);
        try {
            imports.validate(tenantId,importId,mapping);
            if(!"VALIDATED".equals(imports.load(tenantId,importId).status())){
                redirect.addFlashAttribute("catalogError","Some rows need attention. Correct the mapping or source file shown below.");
                return "redirect:/app/catalog/imports/"+importId;
            }
            var view=imports.load(tenantId,importId);
            importProgress.queue(tenantId,importId,view.totalRows());
            importWorker.start(tenantId,authentication.getName(),importId,Map.copyOf(mapping));
            return "redirect:/app/catalog?catalogImportJob="+importId;
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("catalogError", e.getMessage());
            return "redirect:/app/catalog/imports/" + importId;
        }
    }

    private static Map<String,String> importMapping(Map<String,String> parameters){
        Map<String,String> mapping=new LinkedHashMap<>();
        for(String key:java.util.List.of("productName","brand","identifier","accountSku","vendorItemCode","listCost","discountRate","expirationRequired","size","unitOfMeasure","casePack","unitOfSale","suggestedRetail","category","effectiveDate","minimumQuantity")){
            String value=parameters.get(key);if(value!=null&&!value.isBlank())mapping.put(key,value);
        }
        mapping.put("identifierType",parameters.getOrDefault("identifierType","UPC"));return mapping;
    }

    record CatalogImportJobView(String state,int percent,String phase,String error,String filename,
            String vendorName,int totalRows,int mappedSkus,int unmappedSkus){}
    private static String catalogImportPhase(String state,int processed,int total){
        if("MAPPING".equals(state))return "Matching marketplace SKUs to catalogue products";
        if("COMPLETED".equals(state))return "Catalogue import complete";
        if("FAILED".equals(state))return "Catalogue import needs attention";
        if("QUEUED".equals(state))return "Preparing catalogue rows";
        return "Importing "+Math.max(0,processed)+" of "+Math.max(0,total)+" products";
    }
    private static int catalogImportPercent(String state,int rowPercent){
        if("COMPLETED".equals(state))return 100;
        if("MAPPING".equals(state))return 96;
        return Math.min(92,Math.max(3,rowPercent));
    }

    private static UUID tenantId(HttpSession session) {
        Object value = session.getAttribute("selectedTenantId");
        return value instanceof UUID id ? id : null;
    }
    private static UUID requiredTenantId(HttpSession session) {
        UUID id = tenantId(session);
        if (id == null) throw new IllegalStateException("Choose an account first.");
        return id;
    }
    private static String redirectTo(String value) {
        return "redirect:" + (value != null && (value.equals("/app/catalog") || value.equals("/app/vendors")
            || value.equals("/app/inventory") || value.equals("/app/receiving") || value.matches("/app/receiving/[0-9a-fA-F-]{36}")) ? value : "/app/catalog");
    }
}
