package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import com.nextaicommerce.platform.web.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;

/** Provider data adapter into the existing Orders shell, table runtime and mapping/collaboration controls. */
@Component
public class ShopifyOrderPage {
    @org.springframework.beans.factory.annotation.Value("${app.shopify.shipping-sandbox-enabled:false}") private boolean shippingSandbox;
    private final ShopifyOrderReadRepository reads;private final ShopifyOrderJobs jobs;private final ShopifyOrderWorker worker;
    private final ShopifyConnectionService connections;private final WorkspaceAccessRepository access;private final CollaborationRepository collaboration;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.catalog.CatalogRepository catalog;
    @org.springframework.beans.factory.annotation.Autowired private ShopifyOrderPacking packing;
    public ShopifyOrderPage(ShopifyOrderReadRepository reads,ShopifyOrderJobs jobs,ShopifyOrderWorker worker,
                            ShopifyConnectionService connections,WorkspaceAccessRepository access,CollaborationRepository collaboration){
        this.reads=reads;this.jobs=jobs;this.worker=worker;this.connections=connections;this.access=access;this.collaboration=collaboration;
    }
    private void requireView(UUID tenant,UUID connection,Authentication actor){
        if(actor==null||!actor.isAuthenticated())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Store access unavailable.");
        boolean admin=actor.getAuthorities().stream().anyMatch(a->"ROLE_PLATFORM_ADMIN".equals(a.getAuthority()))&&access.isCurrentPlatformAdmin(actor.getName());
        boolean allowed=access.listAccountOptions(actor.getName(),admin).stream().filter(a->tenant.equals(a.id()))
            .flatMap(a->a.stores().stream()).anyMatch(s->connection.equals(s.id())&&"SHOPIFY".equals(s.channel()));
        if(!allowed)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Store access unavailable.");
    }
    public String show(UUID tenant,UUID connection,Authentication actor,String status,String query,int page,int size,Model model){
        requireView(tenant,connection,actor);
        if(!jobs.enabled()){model.addAttribute("shopifyOrdersUnavailable",true);return "orders";}
        boolean mayWrite=access.canOperateAccount(tenant,actor.getName());
        model.addAttribute("canEditCatalog",mayWrite&&Boolean.TRUE.equals(model.asMap().get("canEditCatalog")));
        model.addAttribute("vendors",mayWrite&&Boolean.TRUE.equals(model.asMap().get("canEditCatalog"))&&catalog!=null?catalog.listVendorChoices(tenant):List.of());
        String selected=ShopifyOrderReadRepository.TABS.contains(status.toUpperCase(Locale.ROOT))?status.toUpperCase(Locale.ROOT):"ALL";
        var orders=reads.page(tenant,connection,selected,query,page,size);
        model.addAttribute("selectedStatus",selected);model.addAttribute("shopifyOrdersPage",orders);model.addAttribute("shopifyOrderTabs",reads.tabs(tenant,connection));
        model.addAttribute("shopifyOrderJob",jobs.status(tenant,connection));model.addAttribute("shopifyOrderSetup",connections.setupState(tenant,connection));
        model.addAttribute("canSyncShopifyOrders",mayWrite);model.addAttribute("shopifyShippingSandbox",shippingSandbox);
        model.addAttribute("orderTime",DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a z",Locale.US).withZone(ZoneId.of("America/Los_Angeles")));
        model.addAttribute("shopifyOrderThreads",collaboration.openSubjectSummaries(tenant,"ORDER",orders.rows().stream().map(ShopifyOrderReadRepository.Order::subjectKey).toList(),actor.getName()));
        model.addAttribute("orderStreamKey",versionUnchecked(tenant,connection));return "orders";
    }
    public void requireOperate(UUID tenant,UUID connection,Authentication actor){requireView(tenant,connection,actor);if(!jobs.enabled()||!access.canOperateAccount(tenant,actor.getName()))throw new org.springframework.security.access.AccessDeniedException("Store operations unavailable.");}
    public String packing(UUID tenant,UUID connection,UUID order,Authentication actor,Model model){
        requireView(tenant,connection,actor);var slip=packing.slip(tenant,connection,order);String blocked=packing.eligibility(tenant,connection,order);
        model.addAttribute("slip",slip);model.addAttribute("shopifyPacking",true);model.addAttribute("pickupEligible",blocked==null);model.addAttribute("packingBlockedReason",blocked);
        model.addAttribute("pickupMarked",packing.marked(tenant,connection,order));
        model.addAttribute("canEditPacking",access.canOperateAccount(tenant,actor.getName()));model.addAttribute("packageNames",packing.packageNames(tenant));model.addAttribute("packingLocations",catalog==null||!access.canOperateAccount(tenant,actor.getName())?List.of():catalog.listLocations(tenant));return "packing-slip";
    }
    public String savePackage(UUID tenant,UUID connection,UUID order,String name,Authentication actor){requireOperate(tenant,connection,actor);return packing.savePackage(tenant,connection,order,name);}
    public void pickup(UUID tenant,UUID connection,UUID order,boolean waiting,Authentication actor){requireOperate(tenant,connection,actor);packing.mark(tenant,connection,order,waiting,actor.getName());}
    public UUID sync(UUID tenant,UUID connection,String actor){connections.renewOrdersAuthorization(tenant,connection,()->jobs.authorizeActor(tenant,connection,actor));jobs.enablePolling(tenant,connection,actor);UUID id=jobs.enqueue(tenant,connection,actor);worker.start(tenant,connection,id);return id;}
    public boolean initialize(UUID tenant,UUID connection,String actor){if(!jobs.enabled()||!connections.setupState(tenant,connection).ordersPermitted()||!jobs.catalogueReady(tenant,connection))return false;sync(tenant,connection,actor);return true;}
    public void cancel(UUID tenant,UUID connection,String actor){if(!jobs.enabled())throw new IllegalStateException("Shopify Orders sync is disabled.");jobs.cancel(tenant,connection,actor);}
    public String version(UUID tenant,UUID connection,Authentication actor){requireView(tenant,connection,actor);return versionUnchecked(tenant,connection);}
    private String versionUnchecked(UUID tenant,UUID connection){var status=jobs.status(tenant,connection);return status==null?"none":status.id()+":"+status.state()+":"+status.checked()+":"+reads.localVersion(tenant,connection);}
}
