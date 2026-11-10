package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;

@RestController
@RequestMapping("/app/inventory/replenishment/plans")
public class ReplenishmentPurchasePlanController {
 private final ReplenishmentPurchasePlans plans;private final WorkspaceAccessRepository access;private final CatalogRepository catalog;
 public ReplenishmentPurchasePlanController(ReplenishmentPurchasePlans plans,WorkspaceAccessRepository access,CatalogRepository catalog){this.plans=plans;this.access=access;this.catalog=catalog;}
 private UUID tenant(HttpSession session,Authentication auth,UUID workspace){
  if(auth==null||!(session.getAttribute("selectedTenantId") instanceof UUID id)||!access.canOperateAccount(id,auth.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(!id.equals(workspace))throw new ResponseStatusException(HttpStatus.CONFLICT,"Your selected account changed. Reopen Replenishment in the intended account.");
  return id;
 }
 public record CatalogueChoice(ReplenishmentPurchasePlans.Line item,String brand,String image){}
 @GetMapping("/catalogue") ResponseEntity<?> catalogue(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String vendor){
  UUID tenant=tenant(s,a,workspace);String query=q.trim();if(query.length()<2)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.of());
  var candidates=catalog.searchAccountItems(tenant,query,20);var ids=candidates.stream().map(CatalogRepository.AccountItemView::id).toList();
  var lines=plans.catalogueChoices(tenant,ids,vendor);var images=catalog.pickerImages(tenant,lines.stream().map(ReplenishmentPurchasePlans.Line::id).toList());
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lines.stream().map(line->new CatalogueChoice(line,candidates.stream().filter(c->c.id().equals(line.id())).findFirst().map(CatalogRepository.AccountItemView::brand).orElse(null),images.get(line.id()))).toList());
 }
 @GetMapping ResponseEntity<?> list(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@RequestParam(defaultValue="DRAFT") String status){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.list(tenant(s,a,workspace),status));}
 @GetMapping("/{id}") ResponseEntity<?> get(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.get(tenant(s,a,workspace),id));}
 @PostMapping("/{id}/delete") Map<String,Object> delete(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@PathVariable UUID id,@RequestBody ReplenishmentPurchasePlans.DeleteDraft c){plans.deleteDraft(tenant(s,a,workspace),id,c.revision());return Map.of("id",id,"deleted",true);}
 @PostMapping("/quote") ReplenishmentPurchasePlans.Quote quote(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@RequestBody List<ReplenishmentPurchasePlans.InputLine> lines){return plans.quote(tenant(s,a,workspace),lines);}
 @PostMapping ReplenishmentPurchasePlans.Plan save(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@RequestBody ReplenishmentPurchasePlans.Command c){return plans.save(tenant(s,a,workspace),a.getName(),c,false);}
 @PostMapping("/submit") ReplenishmentPurchasePlans.Plan submit(HttpSession s,Authentication a,@RequestHeader("X-Workspace-Account") UUID workspace,@RequestBody ReplenishmentPurchasePlans.Command c){return plans.save(tenant(s,a,workspace),a.getName(),c,true);}
}
