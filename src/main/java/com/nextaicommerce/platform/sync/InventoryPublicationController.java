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
 private final String mode;private final boolean enabled;
 public InventoryPublicationController(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,
   @Value("${app.amazon.inventory-publication-mode:DRY_RUN}") String mode,@Value("${app.amazon.inventory-publication-enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.access=access;this.mode=mode;this.enabled=enabled;}
 @GetMapping("/app/inventory/publications")
 public String status(HttpSession session,Authentication auth,Model model){
  if(!(session.getAttribute("selectedTenantId") instanceof UUID tenant))return "redirect:/app/select-account";
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
  if(!access.canAccessAccount(tenant,auth.getName(),admin))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  PageController.addTenantModel(session,model);PageController.addAccessModel(auth,model);
  model.addAttribute("publicationMode",enabled?mode:"DISABLED");
  model.addAttribute("orderChecks",tx.execute(s->{
   jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
   return jdbc.queryForList("SELECT c.amazon_order_id,c.state,c.detail,c.checked_at,c.next_check_at FROM amazon_order_status_checks c JOIN amazon_orders o ON o.tenant_id=c.tenant_id AND o.marketplace_connection_id=c.connection_id AND o.amazon_order_id=c.amazon_order_id WHERE c.tenant_id=? AND c.state IN ('UNCONFIRMED','RETRY','OVERDUE') AND regexp_replace(upper(o.order_status),'[^A-Z]','','g') IN ('PENDING','PENDINGAVAILABILITY','UNSHIPPED','PARTIALLYSHIPPED') ORDER BY c.next_check_at LIMIT 100",tenant);
  }));
  model.addAttribute("publications",tx.execute(s->{
   jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
   return jdbc.queryForList("SELECT seller_sku,marketplace_id,desired_quantity,status,attempts,last_error,observed_quantity,updated_at FROM inventory_publications WHERE tenant_id=? ORDER BY (last_error IS NOT NULL AND status<>'DRY_RUN') DESC,updated_at DESC LIMIT 100",tenant);
  }));
  return "inventory-publications";
 }
}
