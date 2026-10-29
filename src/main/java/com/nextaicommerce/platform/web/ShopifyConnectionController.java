package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ShopifyConnectionController {
    private final WorkspaceAccessRepository repository;
    private final ShopifyConnectionService service;
    private final ShopifyConnectionAccess access;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyCatalogJobs catalogueJobs;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyOrderPage orderPage;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyCatalogWorker catalogueWorker;
    public ShopifyConnectionController(WorkspaceAccessRepository repository,ShopifyConnectionService service,ShopifyConnectionAccess access){
        this.repository=repository;this.service=service;this.access=access;
    }
    @PostMapping("/app/connections/shopify")
    String add(@RequestParam String displayName,@RequestParam String marketplaceIdentifier,HttpSession session,Authentication actor,RedirectAttributes redirect){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);access.require(tenant,null,actor);
        try{
            if(!service.enabled())throw new IllegalStateException("Shopify setup is not enabled in this environment yet.");
            repository.addShopifyConnection(tenant,displayName,marketplaceIdentifier);
            redirect.addFlashAttribute("connectionSuccess","Shopify store added. Set up its merchant-owned app next; no orders have been imported.");
        }catch(IllegalArgumentException|IllegalStateException ex){redirect.addFlashAttribute("connectionError",ex.getMessage());}
        return "redirect:/app/connections";
    }
    @PostMapping("/app/connections/{id}/shopify/credentials")
    String connect(@PathVariable UUID id,@RequestParam String clientId,@RequestParam String clientSecret,HttpSession session,Authentication actor,RedirectAttributes redirect){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);access.require(tenant,id,actor);
        try{service.connect(tenant,id,clientId,clientSecret,()->authorizeCurrentSession(session,tenant,id,actor));redirect.addFlashAttribute("connectionSuccess","Shopify store identity verified and authorization saved. "+initializeCatalogue(tenant,id,actor.getName()));}
        catch(IllegalArgumentException|IllegalStateException ex){
            redirect.addFlashAttribute("connectionError",ex.getMessage());redirect.addFlashAttribute("shopifyRetryId",id);
            // Keep the non-secret app identifier only; never retain the submitted secret in flash/session.
            redirect.addFlashAttribute("shopifyRetryClientId",clientId);
        }
        return "redirect:/app/connections";
    }
    @PostMapping("/app/connections/{id}/shopify/verify")
    String verify(@PathVariable UUID id,HttpSession session,Authentication actor,RedirectAttributes redirect){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);access.require(tenant,id,actor);
        try{service.verify(tenant,id,()->authorizeCurrentSession(session,tenant,id,actor));redirect.addFlashAttribute("connectionSuccess","Shopify authorization verified. "+initializeCatalogue(tenant,id,actor.getName()));}
        catch(IllegalArgumentException|IllegalStateException ex){redirect.addFlashAttribute("connectionError",ex.getMessage());}
        return "redirect:/app/connections";
    }
    @PostMapping("/app/connections/{id}/shopify/permissions")
    String permissions(@PathVariable UUID id,HttpSession session,Authentication actor,RedirectAttributes redirect){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);access.require(tenant,id,actor);
        try{service.refreshPermissions(tenant,id,()->authorizeCurrentSession(session,tenant,id,actor));redirect.addFlashAttribute("connectionSuccess","Installed Shopify permissions refreshed. "+initializeCatalogue(tenant,id,actor.getName()));}
        catch(IllegalArgumentException|IllegalStateException ex){redirect.addFlashAttribute("connectionError",ex.getMessage());}
        return "redirect:/app/connections";
    }
    private String initializeCatalogue(UUID tenant,UUID connection,String actor){
        if(orderPage!=null)try{orderPage.initialize(tenant,connection,actor);}catch(RuntimeException ignored){/* Catalogue and saved authorization remain intact. */}
        String orders=service.ordersEnabled()?(service.setupState(tenant,connection).ordersPermitted()?"Orders sync continues after product import.":"Approve Orders read access in Shopify, then refresh installed permissions."):"Orders are not enabled.";
        if(catalogueJobs==null||catalogueWorker==null||!catalogueJobs.enabled())return orders;
        if(!service.setupState(tenant,connection).productsPermitted())return "Approve product access in Shopify, then refresh installed permissions. "+orders;
        try{UUID job=catalogueJobs.enqueue(tenant,connection,actor);catalogueWorker.start(tenant,connection,job);return "Product and variant sync continues in the background. "+orders;}
        catch(RuntimeException ex){return "Authorization is saved; product sync could not start. Review the connection and retry. "+orders;}
    }
    private void authorizeCurrentSession(HttpSession session,UUID tenant,UUID id,Authentication actor){
        if(!tenant.equals(session.getAttribute(AccountSelectionController.TENANT_ID)))throw new org.springframework.security.access.AccessDeniedException("The selected account changed. Refresh before connecting this store.");
        access.require(tenant,id,actor);
    }

}
