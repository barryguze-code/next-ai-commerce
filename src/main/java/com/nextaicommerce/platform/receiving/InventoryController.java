package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import com.nextaicommerce.platform.orders.OrderRepository;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

@Controller
public class InventoryController {
    private static final Logger log=LoggerFactory.getLogger(InventoryController.class);
    private final InventoryRepository inventory;
    private final ObjectProvider<PhysicalCountImportService> physicalCounts;
    private final ObjectProvider<CatalogRepository> catalog;
    private final ObjectProvider<PhysicalCountWorker> physicalCountWorker;
    private final ObjectProvider<PhysicalCountProgress> physicalCountProgress;
    private final ObjectProvider<CollaborationRepository> collaboration;
    private final ObjectProvider<OrderRepository> orders;
    public InventoryController(InventoryRepository inventory,ObjectProvider<PhysicalCountImportService> physicalCounts,ObjectProvider<CatalogRepository> catalog,
            ObjectProvider<PhysicalCountWorker> physicalCountWorker,ObjectProvider<PhysicalCountProgress> physicalCountProgress,ObjectProvider<CollaborationRepository> collaboration,
            ObjectProvider<OrderRepository> orders){this.inventory=inventory;this.physicalCounts=physicalCounts;this.catalog=catalog;this.physicalCountWorker=physicalCountWorker;this.physicalCountProgress=physicalCountProgress;this.collaboration=collaboration;this.orders=orders;}
    public record OutOfStockMatch(String id,String name,String brand,String accountSku,String vendorItemCode,String identifier,boolean expirationRequired){}
    @PostMapping("/app/inventory/expiration")
    String changeExpiration(HttpSession session,Authentication auth,@RequestParam UUID itemId,@RequestParam UUID locationId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate oldDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate newDate,
            @RequestParam BigDecimal expectedQuantity,RedirectAttributes redirect){
        try{inventory.changeExpiration(tenant(session),auth.getName(),itemId,locationId,oldDate,newDate,expectedQuantity,orders.getObject());
            redirect.addFlashAttribute("inventorySuccess","Expiration corrected. Shelf quantity is unchanged and open-order reservations were recalculated.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Expiration correction failed",e);redirect.addFlashAttribute("inventoryError","The expiration could not be changed. Nothing was saved.");}
        return "redirect:/app/inventory";
    }
    public record InlineInventoryPosition(UUID itemId,String productName,String accountSku,String vendorItemCode,
            UUID locationId,String locationCode,String locationName,LocalDate expirationDate,String onHand,String reserved,
            String available,String imageUrl){}
    public record InlineAdjustmentResponse(String message){}
    public record AdjustmentOptions(List<InventoryRepository.AdjustmentItem> items,List<CatalogRepository.LocationView> locations){}
    @GetMapping("/app/inventory/adjustment-options") @ResponseBody
    ResponseEntity<AdjustmentOptions> adjustmentOptions(@RequestParam(name="itemId") List<UUID> itemIds,HttpSession session){
        if(itemIds.isEmpty()||itemIds.size()>20)return ResponseEntity.badRequest().build();
        UUID tenantId=tenant(session);
        var repository=catalog.getObject();var images=repository.pickerImages(tenantId,itemIds);
        var items=inventory.adjustmentItems(tenantId,itemIds).stream().map(item->new InventoryRepository.AdjustmentItem(
            item.id(),item.name(),item.itemCode(),item.expirationRequired(),item.defaultLocationId(),images.get(item.id()))).toList();
        return ResponseEntity.ok(new AdjustmentOptions(items,repository.listLocations(tenantId)));
    }
    @PostMapping("/app/inventory/receipts/inline") @ResponseBody
    ResponseEntity<InlineAdjustmentResponse> inlineReceipt(Authentication auth,HttpSession session,@RequestParam UUID itemId,
            @RequestParam BigDecimal quantity,@RequestParam(required=false) UUID locationId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam String notes){
        try{
            if(notes==null||notes.isBlank())throw new IllegalArgumentException("Explain why this item is being received without an invoice.");
            UUID tenantId=tenant(session);
            inventory.receiveAndReconcile(tenantId,auth.getName(),itemId,quantity,expirationDate,locationId,notes,orders.getIfAvailable());
            return ResponseEntity.ok(new InlineAdjustmentResponse("Item received at zero cost. Open-order reservations refreshed."));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(new InlineAdjustmentResponse(e.getMessage()));}
        catch(Exception e){log.error("Manual receipt failed itemId={}",itemId,e);return ResponseEntity.internalServerError()
            .body(new InlineAdjustmentResponse("The receipt could not be completed. Check the inventory ledger before retrying."));}
    }
    @GetMapping("/app/inventory") String inventory(Authentication auth,HttpSession session,Model model){
        Object value=session.getAttribute("selectedTenantId");if(!(value instanceof UUID tenantId))return "redirect:/app/select-account";
        PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
        var rows=inventory.inventory(tenantId);var policy=inventory.shelfLifePolicy(tenantId);LocalDate today=LocalDate.now();
        model.addAttribute("inventory",rows);model.addAttribute("today",today);model.addAttribute("policy",policy);
        var collaborationRepository=collaboration.getIfAvailable();
        model.addAttribute("openReviewsByPosition", collaborationRepository==null?Map.of():collaborationRepository.openSubjectSummaries(tenantId,"INVENTORY",rows.stream().map(row->row.itemId()+"|"+(row.expirationDate()==null?"":row.expirationDate())).toList(),auth.getName()));
        var catalogRepository=catalog.getIfAvailable();
        var countService=physicalCounts.getIfAvailable();
        model.addAttribute("recentPhysicalCounts",countService==null?List.of():countService.history(tenantId).stream().limit(3).toList());
        try { model.addAttribute("vendors",catalogRepository==null?List.of():catalogRepository.listVendorChoices(tenantId));
            model.addAttribute("locations",catalogRepository==null?List.of():catalogRepository.listLocations(tenantId)); }
        catch(Exception ignored) { model.addAttribute("vendors",List.of());model.addAttribute("locations",List.of()); }
        var blocked=rows.stream().filter(row->List.of("EXPIRED","CANNOT_SELL").contains(row.shelfStatus(today,policy.minimumSellableDays(),policy.warningDays()))).toList();
        var soon=rows.stream().filter(row->"ACT_SOON".equals(row.shelfStatus(today,policy.minimumSellableDays(),policy.warningDays()))).toList();
        model.addAttribute("blockedPositions",blocked.size());model.addAttribute("soonPositions",soon.size());
        model.addAttribute("healthyPositions",rows.size()-blocked.size()-soon.size());
        model.addAttribute("availablePositions",rows.size());
        var total=rows.stream().map(InventoryRepository.InventoryView::quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        var blockedUnits=blocked.stream().map(InventoryRepository.InventoryView::quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        var soonUnits=soon.stream().map(InventoryRepository.InventoryView::quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        var marketplaceEligible=rows.stream().filter(row->!row.marketplaceStoppedByPlan())
            .filter(row->!policy.autoZeroMarketplaceSellable()||!List.of("EXPIRED","CANNOT_SELL")
                .contains(row.shelfStatus(today,policy.minimumSellableDays(),policy.warningDays())))
            .map(row->row.quantity().subtract(row.reservedQuantity()).max(java.math.BigDecimal.ZERO))
            .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        model.addAttribute("totalEachUnits",units(total));model.addAttribute("sellableEachUnits",units(marketplaceEligible));
        model.addAttribute("blockedEachUnits",units(blockedUnits));model.addAttribute("soonEachUnits",units(soonUnits));
        return "inventory";
    }
    @GetMapping("/app/inventory/out-of-stock-match") @ResponseBody
    ResponseEntity<OutOfStockMatch> outOfStockMatch(@RequestParam(defaultValue="") String q,HttpSession session){
        String query=q.trim();
        if(query.length()<2)return ResponseEntity.noContent().build();
        var catalogRepository=catalog.getIfAvailable();
        if(catalogRepository==null)return ResponseEntity.noContent().build();
        UUID tenantId=tenant(session);
        return catalogRepository.searchAccountItems(tenantId,query,12).stream()
            .filter(item->!inventory.hasPositiveInventory(tenantId,item.id()))
            .findFirst()
            .map(item->ResponseEntity.ok(new OutOfStockMatch(item.id().toString(),item.name(),item.brand(),
                item.accountSku(),item.vendorItemCode(),item.identifier(),item.expirationRequired())))
            .orElseGet(()->ResponseEntity.noContent().build());
    }

    @GetMapping("/app/inventory/adjustment-positions") @ResponseBody
    ResponseEntity<List<InlineInventoryPosition>> adjustmentPositions(@RequestParam(name="itemId") List<UUID> itemIds,HttpSession session){
        if(itemIds==null||itemIds.isEmpty()||itemIds.size()>20)return ResponseEntity.badRequest().build();
        var requested=new java.util.LinkedHashSet<>(itemIds);
        return ResponseEntity.ok(inventory.inventory(tenant(session),itemIds).stream().filter(row->requested.contains(row.itemId()))
            .map(row->new InlineInventoryPosition(row.itemId(),row.productName(),row.accountSku(),row.vendorItemCode(),
                row.locationId(),row.locationCode(),row.locationName(),row.expirationDate(),row.quantityUnits(),
                row.reservedUnits(),row.availableUnits(),row.imageUrl())).toList());
    }

    @PostMapping("/app/inventory/adjustments/inline") @ResponseBody
    ResponseEntity<InlineAdjustmentResponse> inlineAdjustment(Authentication auth,HttpSession session,@RequestParam UUID itemId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required=false) UUID locationId,@RequestParam BigDecimal quantityChange,
            @RequestParam String reason,@RequestParam(required=false) String notes){
        try{
            UUID tenantId=tenant(session);
            inventory.adjustAndReconcile(tenantId,auth.getName(),itemId,expirationDate,locationId,quantityChange,reason,notes,orders.getIfAvailable());
            return ResponseEntity.ok(new InlineAdjustmentResponse("Inventory adjusted and open-order reservations refreshed."));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(new InlineAdjustmentResponse(e.getMessage()));}
        catch(Exception e){log.error("Inline inventory adjustment failed itemId={}",itemId,e);return ResponseEntity.internalServerError()
            .body(new InlineAdjustmentResponse("The inventory adjustment could not be saved. Nothing was changed."));}
    }
    @GetMapping("/app/inventory/policy/impact") @ResponseBody
    Object policyImpact(HttpSession session){return Map.of("skuCount",inventory.shelfPolicySkuCount(tenant(session)));}

    @PostMapping("/app/inventory/policy") String policy(Authentication auth,HttpSession session,
            @RequestParam int minimumSellableDays,@RequestParam int warningDays,
            @RequestParam(defaultValue="false") boolean autoZeroMarketplaceSellable,
            @RequestParam(defaultValue="false") boolean autoSaleEnabled,
            @RequestParam(defaultValue="10") BigDecimal defaultSaleDiscountPercent,
            @RequestParam(defaultValue="30") int saleStartDaysBeforeExpiration,
            @RequestParam(defaultValue="7") int saleDurationDays,RedirectAttributes redirect){
        try{
            inventory.saveShelfLifePolicy(tenant(session),auth.getName(),minimumSellableDays,warningDays,
                autoZeroMarketplaceSellable,autoSaleEnabled,defaultSaleDiscountPercent,
                saleStartDaysBeforeExpiration,saleDurationDays);
            redirect.addFlashAttribute("inventorySuccess","Shelf-life automation updated for this account.");
            log.info("Inventory shelf-life policy updated tenantId={} minimumSellableDays={} warningDays={}",tenant(session),minimumSellableDays,warningDays);
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Inventory shelf-life policy update failed",e);redirect.addFlashAttribute("inventoryError","The shelf-life rules could not be saved. Nothing was changed.");}
        return "redirect:/app/inventory";
    }
    @PostMapping("/app/inventory/actions") String action(Authentication auth,HttpSession session,@RequestParam UUID itemId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam String actionType,@RequestParam(required=false) String removalMethod,
            @RequestParam(required=false) String notes,RedirectAttributes redirect){
        try{
            inventory.planExpirationAction(tenant(session),auth.getName(),itemId,expirationDate,actionType,removalMethod,notes);
            String message="CLEAR".equals(actionType)?"Current plan cleared. Inventory quantities were not changed."
                :"Inventory plan saved locally. Physical units remain visible and Amazon inventory was not changed.";
            redirect.addFlashAttribute("inventorySuccess",message);
            log.info("Expiration action planned tenantId={} itemId={} expiration={} action={}",tenant(session),itemId,expirationDate,actionType);
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Expiration action failed itemId={}",itemId,e);redirect.addFlashAttribute("inventoryError","The inventory action could not be saved. Nothing was changed.");}
        return "redirect:/app/inventory";
    }
    @GetMapping("/app/inventory/sale-plan-defaults") @ResponseBody
    InventoryRepository.SalePlanDefaults salePlanDefaults(@RequestParam UUID itemId,HttpSession session){
        return inventory.salePlanDefaults(tenant(session),itemId);
    }

    @PostMapping("/app/inventory/promotions") String promotion(Authentication auth,HttpSession session,@RequestParam UUID itemId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam BigDecimal discountPercent,@RequestParam int startDays,
            @RequestParam int durationDays,@RequestParam(defaultValue="false") boolean rememberForProduct,
            @RequestParam(required=false) List<String> skuTarget,RedirectAttributes redirect){
        try{
            int planned=inventory.saveSalePlan(tenant(session),auth.getName(),itemId,expirationDate,
                discountPercent,startDays,durationDays,rememberForProduct,skuTarget);
            redirect.addFlashAttribute("inventorySuccess","Sale plan saved for "+planned+" Marketplace SKU"+
                (planned==1?".":"s.")+" Eligible plans are published only when production sale publishing is enabled for this account.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Sale plan failed",e);redirect.addFlashAttribute("inventoryError","The sale plan could not be saved. Nothing was sent to Amazon.");}
        return "redirect:/app/inventory";
    }
    @PostMapping("/app/inventory/adjustments") String adjustment(Authentication auth,HttpSession session,@RequestParam UUID itemId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required=false) UUID locationId,
            @RequestParam BigDecimal quantityChange,@RequestParam String reason,@RequestParam(required=false) String notes,RedirectAttributes redirect){
        try{
            inventory.adjustInventory(tenant(session),auth.getName(),itemId,expirationDate,locationId,quantityChange,reason,notes);
            redirect.addFlashAttribute("inventorySuccess","Inventory adjustment saved in the audit ledger.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Inventory adjustment failed itemId={}",itemId,e);redirect.addFlashAttribute("inventoryError","The inventory adjustment could not be saved. Nothing was changed.");}
        return "redirect:/app/inventory";
    }
    @PostMapping("/app/inventory/locations") String moveLocation(Authentication auth,HttpSession session,
            @RequestParam UUID itemId,@RequestParam UUID sourceLocationId,@RequestParam UUID destinationLocationId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam BigDecimal quantity,@RequestParam(defaultValue="false") boolean makeDefault,RedirectAttributes redirect){
        try{
            inventory.moveInventoryPosition(tenant(session),auth.getName(),itemId,sourceLocationId,destinationLocationId,expirationDate,quantity,makeDefault);
            redirect.addFlashAttribute("inventorySuccess",makeDefault
                ?quantity.stripTrailingZeros().toPlainString()+" each moved and the product default location was updated."
                :quantity.stripTrailingZeros().toPlainString()+" each moved. Its ledger history was preserved.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Inventory location move failed itemId={}",itemId,e);redirect.addFlashAttribute("inventoryError","The batch location could not be changed. Nothing was moved.");}
        return "redirect:/app/inventory";
    }
    @PostMapping("/app/inventory/receipts") String receiveUninvoiced(Authentication auth,HttpSession session,
            @RequestParam UUID itemId,@RequestParam BigDecimal quantity,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required=false) UUID locationId,
            @RequestParam(required=false) String notes,RedirectAttributes redirect){
        try{
            inventory.receiveUninvoicedItem(tenant(session),auth.getName(),itemId,quantity,expirationDate,locationId,notes);
            redirect.addFlashAttribute("inventorySuccess","Unexpected item received at zero cost. It is available in inventory now.");
            log.info("Uninvoiced inventory receipt saved tenantId={} itemId={} quantity={}",tenant(session),itemId,quantity);
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Uninvoiced inventory receipt failed itemId={}",itemId,e);
            redirect.addFlashAttribute("inventoryError","The unexpected item could not be received. Nothing was changed; please try again.");}
        return "redirect:/app/inventory";
    }
    @PostMapping("/app/inventory/physical-counts") String physicalCount(Authentication auth,HttpSession session,
            @RequestParam(required=false) UUID vendorId,@RequestPart MultipartFile file,RedirectAttributes redirect){
        try{var service=physicalCounts.getIfAvailable();if(service==null)throw new IllegalStateException("Physical count import is unavailable.");UUID importId=service.stage(tenant(session),auth.getName(),vendorId,file);return "redirect:/app/inventory/physical-counts/"+importId;}
        catch(IllegalArgumentException e){redirect.addFlashAttribute("inventoryError",e.getMessage());}
        catch(Exception e){log.error("Physical count staging failed",e);redirect.addFlashAttribute("inventoryError","The physical count could not be prepared. No changes were saved.");}
        return "redirect:/app/inventory";
    }
    @GetMapping("/app/inventory/physical-counts/{importId}") String physicalCountReview(@PathVariable UUID importId,
            Authentication auth,HttpSession session,Model model){
        Object selectedTenant=session.getAttribute(AccountSelectionController.TENANT_ID);
        if(!(selectedTenant instanceof UUID tenantId))return "redirect:/app/select-account";
        PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
        var service=physicalCounts.getIfAvailable();if(service==null)return "redirect:/app/inventory";
        var review=service.load(tenantId,importId);
        Object submitted=model.getAttribute("physicalCountMapping");
        if(submitted instanceof Map<?,?> values){
            Map<String,String> selected=new LinkedHashMap<>();
            for(String field:List.of("itemCode","quantity","expiration","location")){
                Object choice=values.get(field);selected.put(field,choice==null?"":String.valueOf(choice));
            }
            review=new PhysicalCountImportService.ImportView(review.id(),review.filename(),review.status(),
                review.headers(),selected,review.samples(),review.totalRows(),review.appliedRows(),review.vendorId());
        }
        model.addAttribute("physicalCount",review);
        var catalogRepository=catalog.getIfAvailable();model.addAttribute("vendors",catalogRepository==null?List.of():catalogRepository.listVendorChoices(tenantId));
        var tracker=physicalCountProgress.getIfAvailable();
        PhysicalCountProgress.View progress=tracker==null?null:tracker.view(tenantId,importId);
        model.addAttribute("physicalCountProgress",progress);
        if(progress!=null&&"FAILED".equals(progress.state())){
            model.addAttribute("physicalCountError",progress.error());
            addPhysicalCountRepair(model,progress.error());
        }
        return "physical-count-import";
    }
    @GetMapping("/app/inventory/physical-counts/history") String physicalCountHistory(
            @RequestParam(required=false) UUID importId,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(required=false) Integer goToPage,
            @RequestParam(defaultValue=com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size,
            Authentication auth,HttpSession session,Model model){
        UUID tenantId=tenant(session);PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
        var service=physicalCounts.getIfAvailable();if(service==null)return "redirect:/app/inventory";
        var history=service.history(tenantId);model.addAttribute("imports",history);
        UUID selected=importId!=null?importId:(history.isEmpty()?null:history.getFirst().id());
        model.addAttribute("selectedImportId",selected);model.addAttribute("query",q);
        if(selected!=null){
            var review=service.load(tenantId,selected);int requestedPage=goToPage==null?page:Math.max(0,goToPage-1);
            model.addAttribute("selectedImport",review);model.addAttribute("uploadedRows",service.uploadedRows(tenantId,selected,q,requestedPage,com.nextaicommerce.platform.web.TablePaging.size(size)));
        }
        return "physical-count-history";
    }
    @PostMapping("/app/inventory/physical-counts/{importId}/apply") String applyPhysicalCount(@PathVariable UUID importId,
            Authentication auth,HttpSession session,@RequestParam(required=false) UUID vendorId,
            @RequestParam Map<String,String> parameters,RedirectAttributes redirect){
        try{
            var service=physicalCounts.getIfAvailable();if(service==null)throw new IllegalStateException("Physical count import is unavailable.");
            Map<String,String> mapping=new LinkedHashMap<>();
            for(String field:List.of("itemCode","quantity","expiration"))mapping.put(field,parameters.getOrDefault(field,""));
            mapping.put("location",parameters.getOrDefault("locationColumn",""));
            mapping.put("scope","REPLACE_PRODUCTS".equals(parameters.get("scope"))?"REPLACE_PRODUCTS":"LISTED_BATCHES");
            var tracker=physicalCountProgress.getIfAvailable();var worker=physicalCountWorker.getIfAvailable();
            if(tracker==null||worker==null)throw new IllegalStateException("Physical count background processing is unavailable.");
            UUID tenantId=tenant(session);service.saveSelection(tenantId,importId,vendorId,mapping);
            if(tracker.queue(tenantId,importId))worker.start(tenantId,auth.getName(),importId,vendorId,Map.copyOf(mapping));
            return "redirect:/app/inventory?physicalCountJob="+importId;
        }catch(IllegalArgumentException e){
            redirect.addFlashAttribute("physicalCountError",e.getMessage());
            Map<String,String> submitted=new LinkedHashMap<>();
            for(String field:List.of("itemCode","quantity","expiration"))submitted.put(field,parameters.getOrDefault(field,""));
            submitted.put("location",parameters.getOrDefault("locationColumn",""));
            submitted.put("scope","REPLACE_PRODUCTS".equals(parameters.get("scope"))?"REPLACE_PRODUCTS":"LISTED_BATCHES");
            redirect.addFlashAttribute("physicalCountMapping",submitted);
            addPhysicalCountRepair(redirect,e.getMessage());
        }
        catch(Exception e){log.error("Physical count apply failed importId={}",importId,e);redirect.addFlashAttribute("physicalCountError","The physical count could not be applied. No changes were saved.");}
        return "redirect:/app/inventory/physical-counts/"+importId;
    }
    @GetMapping("/app/inventory/physical-counts/{importId}/progress") @ResponseBody
    ResponseEntity<PhysicalCountProgress.View> physicalCountProgress(@PathVariable UUID importId,HttpSession session){
        Object selected=session.getAttribute(AccountSelectionController.TENANT_ID);
        if(!(selected instanceof UUID tenantId))return ResponseEntity.status(409).build();
        var tracker=physicalCountProgress.getIfAvailable();if(tracker==null)return ResponseEntity.notFound().build();
        var view=tracker.view(tenantId,importId);return view==null?ResponseEntity.notFound().build():ResponseEntity.ok(view);
    }
    private static void addPhysicalCountRepair(Object target,String text){
        String message=text==null?"":text;
        var location=java.util.regex.Pattern.compile("uses location [“\"]([^”\"]+)[”\"]").matcher(message);
        var product=java.util.regex.Pattern.compile("code [“\"]([^”\"]+)[”\"] does not match").matcher(message);
        String type=null,value=null;if(location.find()){type="LOCATION";value=location.group(1);}else if(product.find()){type="PRODUCT";value=product.group(1);}
        if(type==null)return;
        if(target instanceof Model model){model.addAttribute("physicalCountRepairType",type);model.addAttribute("physicalCountRepairValue",value);}
        else if(target instanceof RedirectAttributes redirect){redirect.addFlashAttribute("physicalCountRepairType",type);redirect.addFlashAttribute("physicalCountRepairValue",value);}
    }
    @GetMapping("/app/inventory/ledger") String ledger(@RequestParam(defaultValue="") String q,
            @RequestParam(required=false) UUID itemId,
            @RequestParam(defaultValue="0") int page,@RequestParam(required=false) Integer goToPage,
            @RequestParam(defaultValue=com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size,
            Authentication auth,HttpSession session,Model model){
        Object value=session.getAttribute("selectedTenantId");if(!(value instanceof UUID tenantId))return "redirect:/app/select-account";
        PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
        int requestedPage=goToPage==null?page:Math.max(0,goToPage-1);
        var ledgerPage=itemId==null?inventory.ledgerPage(tenantId,q,requestedPage,com.nextaicommerce.platform.web.TablePaging.size(size)):inventory.ledgerPage(tenantId,q,requestedPage,com.nextaicommerce.platform.web.TablePaging.size(size),itemId);
        model.addAttribute("itemId",itemId);
        var summary=inventory.ledgerSummary(tenantId);
        model.addAttribute("ledger",ledgerPage.rows());model.addAttribute("ledgerPage",ledgerPage);
        model.addAttribute("query",q);model.addAttribute("movementCount",summary.movements());
        model.addAttribute("receiptCount",summary.receipts());model.addAttribute("adjustmentCount",summary.adjustments());
        return "inventory-ledger";
    }
    @GetMapping("/app/inventory/{itemId}/movements") @ResponseBody List<Map<String,Object>> movements(
            @PathVariable UUID itemId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required=false) UUID locationId,
            @RequestParam(defaultValue="item") String scope,
            @RequestParam(defaultValue="0") int page,HttpSession session){
        if(!java.util.Set.of("item","batch").contains(scope))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Choose item or batch history.");
        return inventory.movements(tenant(session),itemId,expirationDate,locationId,"item".equals(scope),page).stream().map(row->{
            Map<String,Object> view=new LinkedHashMap<>();
            view.put("occurredAt",row.occurredAt());view.put("quantity",row.quantity());
            view.put("currency",row.currency());view.put("unitCost",row.unitCost());
            view.put("sourceType",row.sourceType());view.put("sourceReference",row.sourceReference());
            view.put("movementLabel",row.movementLabel());view.put("sourceLabel",row.sourceLabel());
            view.put("description",row.description());view.put("expirationDate",row.expirationDate());return view;
        }).toList();
    }
    @GetMapping("/app/inventory/{itemId}/reservations") @ResponseBody List<InventoryRepository.ReservationView> reservations(
            @PathVariable UUID itemId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required=false) UUID locationId,HttpSession session){
        return inventory.reservations(tenant(session),itemId,expirationDate,locationId);
    }
    private static UUID tenant(HttpSession session){Object value=session.getAttribute("selectedTenantId");if(value instanceof UUID id)return id;throw new IllegalStateException("Choose an account first.");}
    private static String units(java.math.BigDecimal value){return value.setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
}
