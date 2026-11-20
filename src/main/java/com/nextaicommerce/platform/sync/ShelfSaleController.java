package com.nextaicommerce.platform.sync;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class ShelfSaleController {
 private final ShelfSaleRepository repo;private final boolean live;private final Set<UUID> connections;
 public ShelfSaleController(ShelfSaleRepository repo,Environment env,@Value("${app.local-development:false}") boolean local,
   @Value("${app.amazon.shelf-sale-enabled:false}") boolean enabled,@Value("${app.amazon.write-enabled:false}") boolean writes,
   @Value("${app.amazon.shelf-sale-connections:}") String allowed){
  this.repo=repo;live=env.acceptsProfiles(Profiles.of("prod & !local"))&&!local&&enabled&&writes;
  connections=Arrays.stream(allowed.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).collect(java.util.stream.Collectors.toSet());
 }
 @GetMapping("/app/inventory/sale-publishing") @ResponseBody
 Map<String,Object> status(HttpSession session){var tenant=tenant(session);return Map.of("live",live&&repo.allowed(tenant,connections),"enabled",repo.enabled(tenant),"statuses",repo.summary(tenant));}
 @PostMapping("/app/inventory/sale-publishing") @ResponseBody
 Map<String,Object> configure(HttpSession session,Authentication auth,@RequestParam boolean enabled){
  if(!live||!repo.allowed(tenant(session),connections))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Sale publishing can only be configured for the approved production account. Local never sends prices.");
  repo.configure(tenant(session),enabled,auth.getName());return status(session);
 }
 private static UUID tenant(HttpSession s){if(s.getAttribute("selectedTenantId") instanceof UUID t)return t;throw new IllegalStateException("Choose an account first");}
}
