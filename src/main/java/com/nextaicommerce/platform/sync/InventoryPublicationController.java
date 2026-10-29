package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class InventoryPublicationController {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final WorkspaceAccessRepository access;
 @Value("${app.shopify.inventory.publication-enabled:false}") private boolean shopifyEnabled;
 @org.springframework.beans.factory.annotation.Autowired private com.nextaicommerce.platform.shopify.ShopifyInventoryPublications shopifyPublications;
 private final String mode;private final boolean enabled;
 @Value("${app.inventory.publication-simulation-enabled:false}") private boolean sharedSimulation;
 public InventoryPublicationController(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,
   @Value("${app.amazon.inventory-publication-mode:DRY_RUN}") String mode,@Value("${app.amazon.inventory-publication-enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.access=access;this.mode=mode;this.enabled=enabled;}
 @GetMapping("/app/inventory/publications")
 public String status(HttpSession session,Authentication auth,Model model){
  if(!(session.getAttribute("selectedTenantId") instanceof UUID tenant))return "redirect:/app/select-account";
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
  if(!access.canAccessAccount(tenant,auth.getName(),admin))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
  model.addAttribute("shopifyPublicationEnabled",shopifyEnabled);
  model.addAttribute("publicationMode",shopifyEnabled?"LIVE":sharedSimulation?"DRY_RUN":enabled?mode:"DISABLED");
  model.addAttribute("orderChecks",tx.execute(s->{
   jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
   return jdbc.queryForList("SELECT c.amazon_order_id,c.state,c.detail,c.checked_at,c.next_check_at FROM amazon_order_status_checks c JOIN amazon_orders o ON o.tenant_id=c.tenant_id AND o.marketplace_connection_id=c.connection_id AND o.amazon_order_id=c.amazon_order_id WHERE c.tenant_id=? AND c.state IN ('UNCONFIRMED','RETRY','OVERDUE') AND regexp_replace(upper(o.order_status),'[^A-Z]','','g') IN ('PENDING','PENDINGAVAILABILITY','UNSHIPPED','PARTIALLYSHIPPED') ORDER BY c.next_check_at LIMIT 100",tenant);
  }));
  model.addAttribute("publications",tx.execute(s->{
   jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
   if(shopifyEnabled)return jdbc.queryForList("""
    SELECT * FROM (
      SELECT NULL::uuid operation_id,seller_sku,'Amazon · '||marketplace_id marketplace_id,desired_quantity,status,attempts,last_error,observed_quantity,updated_at FROM inventory_publications WHERE tenant_id=?
      UNION ALL SELECT p.id,coalesce(nullif(v.seller_sku,''),'No SKU'),'Shopify · '||c.display_name,p.desired_quantity,p.state,p.attempts,p.detail,p.observed_quantity,p.updated_at
      FROM shopify_inventory_publications p JOIN shopify_variants v ON v.tenant_id=p.tenant_id AND v.marketplace_connection_id=p.marketplace_connection_id AND v.id=p.variant_id
      JOIN marketplace_connections c ON c.tenant_id=p.tenant_id AND c.id=p.marketplace_connection_id WHERE p.tenant_id=?
    ) p ORDER BY (status='ATTENTION') DESC,updated_at DESC LIMIT 100
    """,tenant,tenant);
   if(!sharedSimulation)return jdbc.queryForList("SELECT seller_sku,marketplace_id,desired_quantity,status,attempts,last_error,observed_quantity,updated_at FROM inventory_publications WHERE tenant_id=? ORDER BY (last_error IS NOT NULL AND status<>'DRY_RUN') DESC,updated_at DESC LIMIT 100",tenant);
   return jdbc.queryForList("""
    SELECT * FROM (
      SELECT seller_sku,'Amazon · '||marketplace_id marketplace_id,desired_quantity,status,attempts,last_error,observed_quantity,updated_at FROM inventory_publications WHERE tenant_id=?
      UNION ALL SELECT coalesce(nullif(v.seller_sku,''),'No SKU')||' · '||v.variant_gid,'Shopify · '||c.display_name,s.desired_quantity,s.state,0,s.detail,NULL::integer,s.updated_at
      FROM shopify_inventory_simulations s JOIN shopify_variants v ON v.tenant_id=s.tenant_id AND v.marketplace_connection_id=s.marketplace_connection_id AND v.id=s.variant_id
      JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id WHERE s.tenant_id=?
    ) p ORDER BY (status='ATTENTION') DESC,updated_at DESC LIMIT 100
    """,tenant,tenant);
  }));
  return "inventory-publications";
 }
 @org.springframework.web.bind.annotation.PostMapping("/app/inventory/publications/shopify-reconcile")
 public String reconcile(HttpSession session,Authentication auth,Model model,@org.springframework.web.bind.annotation.RequestParam UUID operation,
    org.springframework.web.servlet.mvc.support.RedirectAttributes redirect){
  if(!(session.getAttribute("selectedTenantId") instanceof UUID tenant))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  PageController.addAccessModel(auth,model);
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
  if(!shopifyEnabled||!Boolean.TRUE.equals(model.getAttribute("canManageConnections"))||!access.canAccessAccount(tenant,auth.getName(),admin))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  boolean ready=shopifyPublications.requestReconciliation(tenant,operation,auth.getName());
  redirect.addFlashAttribute("publicationMessage",ready?"Fresh reconciliation requested. Prior operation retained; the worker will recalculate before any new submission.":"Refresh all account marketplace orders after this failure before requesting reconciliation.");
  return "redirect:/app/inventory/publications";
 }
}
