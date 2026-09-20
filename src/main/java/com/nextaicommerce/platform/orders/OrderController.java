package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.nextaicommerce.platform.sync.AmazonManualOrderSync;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import org.springframework.beans.factory.annotation.Autowired;

@Controller
public class OrderController {
    private final OrderRepository orders;private final WorkspaceAccessRepository workspace;private final AmazonManualOrderSync manualSync;private final PackingSlipRepository packingSlips;private CollaborationRepository collaboration;
    public OrderController(OrderRepository orders,WorkspaceAccessRepository workspace,AmazonManualOrderSync manualSync,PackingSlipRepository packingSlips){this.orders=orders;this.workspace=workspace;this.manualSync=manualSync;this.packingSlips=packingSlips;}
    @Autowired(required=false) void configureCollaboration(CollaborationRepository repository){this.collaboration=repository;}

    @GetMapping("/app/orders") String orders(@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) Integer goToPage,
            @RequestParam(defaultValue=com.nextaicommerce.platform.web.TablePaging.DEFAULT_PARAMETER) int size,
            Authentication auth,HttpSession session,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(auth,model);model.addAttribute("selectedStatus",status.toUpperCase());model.addAttribute("query",q);
        UUID tenant=tenant(session);Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection)){model.addAttribute("storeSelectionRequired",true);model.addAttribute("chooseStore",true);return "orders";}
        var identity=workspace.findConnection(tenant,connection);
        if(!"AMAZON".equalsIgnoreCase(identity.channel())){model.addAttribute("unsupportedChannel",true);return "orders";}
        if(java.util.List.of("PENDING","READY_TO_SHIP").contains(status.toUpperCase(java.util.Locale.ROOT)))status="UNSHIPPED";
        if(!OrderRepository.TAB_KEYS.contains(status.toUpperCase(java.util.Locale.ROOT)))status="ALL";
        model.addAttribute("selectedStatus",status.toUpperCase(java.util.Locale.ROOT));
        model.addAttribute("orderTabs",orders.tabs(tenant,connection));
        model.addAttribute("pickupOverrides",orders.pickupOverrides(tenant,connection));
        model.addAttribute("pickupEligibleOrders",orders.pickupEligibleOrders(tenant,connection));
        int requestedPage=goToPage==null?page:Math.max(0,goToPage-1);
        var orderPage=orders.orders(tenant,connection,status,q,requestedPage,com.nextaicommerce.platform.web.TablePaging.size(size));var rows=orderPage.rows();
        var items=orders.itemsForOrders(tenant,connection,rows.stream().map(OrderRepository.OrderView::amazonOrderId).toList());
        model.addAttribute("buyBoxLostItems",orders.buyBoxLostItems(tenant,connection,rows.stream().map(OrderRepository.OrderView::amazonOrderId).toList()));
        model.addAttribute("fourWeekSales",orders.fourWeekSales(tenant,connection,items.values().stream().flatMap(java.util.Collection::stream).map(OrderRepository.OrderItemView::sellerSku).toList()));
        model.addAttribute("orders",rows);model.addAttribute("orderPage",orderPage);model.addAttribute("itemsByOrder",items);var summary=orders.summary(tenant,connection);
        model.addAttribute("threadsByOrder",collaboration==null?java.util.Map.of():collaboration.openSubjectSummaries(
            tenant,"ORDER",rows.stream().map(OrderRepository.OrderView::amazonOrderId).toList(),auth.getName()));
        model.addAttribute("orderStreamKey",orders.streamVersion(tenant,connection));
        model.addAttribute("todayOrders",summary.todayOrders());model.addAttribute("todaySales",summary.todaySales());
        model.addAttribute("sales30Days",summary.sales30Days());model.addAttribute("liveCount",summary.live());
        model.addAttribute("historicalCount",summary.historical());
        var syncAvailability=manualSync.availability(tenant,connection);boolean fresh=summary.lastSyncedAt()!=null&&java.time.Duration.between(summary.lastSyncedAt(),java.time.Instant.now()).compareTo(java.time.Duration.ofMinutes(5))<0;
        model.addAttribute("orderSyncAvailable",syncAvailability.allowed());model.addAttribute("orderSyncActive",syncAvailability.activeRun()!=null);
        model.addAttribute("orderSyncFresh",fresh);model.addAttribute("orderSyncRun",syncAvailability.activeRun());
        model.addAttribute("lastOrderSync",summary.lastSyncedAt()==null?"Waiting for Amazon":
            "Last Amazon order check · "+orderTime(identity.marketplaceId()).format(summary.lastSyncedAt()));
        model.addAttribute("orderTime",orderTime(identity.marketplaceId()));
        return "orders";
    }

    static DateTimeFormatter orderTime(String marketplaceId){
        return DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a z",java.util.Locale.US)
            .withZone(com.nextaicommerce.platform.sync.AmazonMarketplaceTime.zone(marketplaceId));
    }

    @PostMapping("/app/orders/sync") String syncOrders(Authentication auth,HttpSession session,RedirectAttributes redirect){
        UUID tenant=tenant(session);Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection)){redirect.addFlashAttribute("orderError","Choose an Amazon store first.");return "redirect:/app/orders";}
        try{return "redirect:/app/orders?orderSyncJob="+manualSync.enqueue(tenant,connection);}
        catch(IllegalStateException cooldown){redirect.addFlashAttribute("orderError",cooldown.getMessage());return "redirect:/app/orders";}
    }

    @GetMapping("/app/orders/sync/{runId}/progress") @ResponseBody ResponseEntity<AmazonManualOrderSync.Progress> syncProgress(@PathVariable UUID runId,HttpSession session){
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);if(!(selected instanceof UUID connection))return ResponseEntity.status(409).build();
        var progress=manualSync.progress(tenant(session),connection,runId);return progress==null?ResponseEntity.notFound().build():ResponseEntity.ok(progress);
    }

    @GetMapping("/app/orders/stream-version") @ResponseBody ResponseEntity<String> streamVersion(HttpSession session){
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection))return ResponseEntity.status(409).build();
        return ResponseEntity.ok(orders.streamVersion(tenant(session),connection));
    }

    @GetMapping("/app/orders/{orderId}/packing-slip") String packingSlip(@PathVariable String orderId,Authentication auth,HttpSession session,Model model){
        PageController.addAccessModel(auth,model);
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an Amazon store first.");
        var slip=packingSlips.find(tenant(session),connection,orderId);
        if(slip==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"This order is not available in the selected store.");
        model.addAttribute("pickupEligible",orders.pickupEligibleOrders(tenant(session),connection).contains(orderId));
        model.addAttribute("pickupMarked",orders.pickupOverrides(tenant(session),connection).contains(orderId));
        model.addAttribute("slip",slip);return "packing-slip";
    }

    @PostMapping("/app/orders/{orderId}/packing-slip/package") @ResponseBody Map<String,String> savePackingPackage(
            @PathVariable String orderId,@RequestParam String packageName,HttpSession session){
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an Amazon store first.");
        return Map.of("packageName",packingSlips.savePackaging(tenant(session),connection,orderId,packageName));
    }

    @PostMapping("/app/orders/{orderId}/pickup-override") String pickupOverride(
            @PathVariable String orderId,@RequestParam boolean waiting,
            @RequestParam(defaultValue="ALL") String status,Authentication auth,HttpSession session,RedirectAttributes redirect){
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID connection))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a store first.");
        if(!orders.setPickupOverride(tenant(session),connection,orderId,waiting,auth.getName()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This order is no longer eligible. Refresh the page.");
        redirect.addFlashAttribute("orderSuccess",waiting?"Marked shipped — awaiting pickup in this platform only. Amazon is unchanged.":"Platform mark removed. Amazon status is unchanged.");
        String tab=OrderRepository.TAB_KEYS.contains(status)?status:"ALL";
        return "redirect:/app/orders?status="+tab;
    }

    private static UUID tenant(HttpSession session){Object id=session.getAttribute(AccountSelectionController.TENANT_ID);if(id instanceof UUID value)return value;throw new IllegalArgumentException("Choose an account first.");}
}
