package com.nextaicommerce.platform.fba;

import com.nextaicommerce.platform.web.*;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Session-derived scope only. Local planning never invokes marketplace adapters. */
@Controller
@RequestMapping("/app/inventory/fba-replenishment")
public class FbaReplenishmentController {
 private final WorkspaceAccessRepository workspace;
 private final FbaReplenishmentForecasts forecasts;
 private final FbaReplenishmentPlans plans;
 private final FbaReplenishmentSettings settings;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaInboundCheckpoints inboundState;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaInboundOrchestration inbound;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaInboundAdapter adapter;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaInboundDocuments documents;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaChildDispatches children;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaChildObservations observations;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.catalog.CatalogRepository catalog;
 @Value("${app.fba.replenishment-enabled:false}") private boolean enabled;
 public FbaReplenishmentController(WorkspaceAccessRepository workspace,FbaReplenishmentForecasts forecasts,FbaReplenishmentPlans plans,FbaReplenishmentSettings settings){this.workspace=workspace;this.forecasts=forecasts;this.plans=plans;this.settings=settings;}
 record Scope(UUID tenant,UUID store){}
 Scope selection(HttpSession session){
  if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant)||!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID store))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an Amazon store first.");
  if(!"AMAZON".equalsIgnoreCase(workspace.findConnection(tenant,store).channel()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an Amazon store first.");
  return new Scope(tenant,store);
 }
 static boolean manage(Authentication auth){return auth!=null&&auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_PLATFORM_ADMIN","ROLE_OWNER","ROLE_ADMIN").contains(a.getAuthority()));}
 static void requireManage(Authentication auth){if(!manage(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Shipment management permission is required.");}
 @GetMapping String page(HttpSession session,Authentication auth,Model model){if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";if(!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID store)||!"AMAZON".equalsIgnoreCase(workspace.findConnection((UUID)session.getAttribute(AccountSelectionController.TENANT_ID),store).channel()))return "redirect:/app/select-account";var s=selection(session);PageController.addAccessModel(auth,model);model.addAttribute("fbaConnection",s.store());model.addAttribute("fbaCountry",catalog==null?"":catalog.warehouseCountryDefault(s.tenant(),s.store()));model.addAttribute("fbaReportingTimeZone",forecasts.reportingTimeZone(s.tenant(),s.store()));model.addAttribute("fbaAmazonDomain",com.nextaicommerce.platform.marketplace.MarketplaceLinks.amazonDomain(workspace.findConnection(s.tenant(),s.store()).marketplaceId()));model.addAttribute("conditionOptions",com.nextaicommerce.platform.marketplace.SkuCondition.orderedOptions());model.addAttribute("locations",java.util.List.of());model.addAttribute("canManageFbaPlan",manage(auth));return "fba-replenishment";}
 @GetMapping("/forecast") @ResponseBody Object forecast(HttpSession session){var s=selection(session);return forecasts.read(s.tenant(),s.store());}
 @PostMapping("/forecast") @ResponseBody Object refresh(HttpSession session){var s=selection(session);forecasts.refresh(s.tenant(),s.store());return Map.of("refreshing",true);}
 @GetMapping("/plans") @ResponseBody Object list(HttpSession session){var s=selection(session);return plans.list(s.tenant(),s.store());}
 @GetMapping("/plans/{id}") @ResponseBody Object get(@PathVariable UUID id,HttpSession session){var s=selection(session);return plans.get(s.tenant(),s.store(),id);}
 @PostMapping("/plans") @ResponseBody Object save(@RequestBody FbaReplenishmentPlans.Draft draft,HttpSession session,Authentication auth){var s=selection(session);return plans.save(s.tenant(),s.store(),auth.getName(),draft);}
 @GetMapping("/plans/{id}/sources") @ResponseBody Object sources(@PathVariable UUID id,HttpSession session){var s=selection(session);var p=plans.get(s.tenant(),s.store(),id);return plans.sources(s.tenant(),s.store(),p.id());}
 @PostMapping("/plans/{id}/allocate") @ResponseBody Object allocate(@PathVariable UUID id,@RequestBody FbaReplenishmentPlans.Allocate command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return plans.allocate(s.tenant(),s.store(),id,auth.getName(),command);}
 @PostMapping("/plans/{id}/checkpoint") @ResponseBody Object checkpoint(@PathVariable UUID id,@RequestBody FbaReplenishmentPlans.Checkpoint command,HttpSession session,Authentication auth){var s=selection(session);return plans.checkpoint(s.tenant(),s.store(),id,auth.getName(),command);}
 @PostMapping("/plans/{id}/cancel") @ResponseBody Object cancel(@PathVariable UUID id,@RequestBody FbaReplenishmentPlans.Cancel command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return plans.cancel(s.tenant(),s.store(),id,auth.getName(),command);}
 @PostMapping("/plans/{id}/release") @ResponseBody Object release(@PathVariable UUID id,@RequestBody FbaReplenishmentPlans.Cancel command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return plans.release(s.tenant(),s.store(),id,auth.getName(),command);}
 FbaInboundWorkflow.Scope inboundScope(Scope scope,UUID plan){return new FbaInboundWorkflow.Scope(scope.tenant(),scope.tenant(),scope.store(),plan);}
 record OriginView(UUID warehouse,String name,FbaInboundOrchestration.Snapshot state){}
 @GetMapping("/plans/{id}/inbound") @ResponseBody Object inbound(@PathVariable UUID id,HttpSession session){var s=selection(session);var p=plans.get(s.tenant(),s.store(),id);var names=catalog.listWarehouses(s.tenant());var origins=p.allocations().stream().filter(a->!"RELEASED".equals(a.get("reservation_status"))).map(a->(UUID)a.get("warehouse_id")).distinct().map(w->new OriginView(w,names.stream().filter(n->n.id().equals(w)).map(n->n.name()).findFirst().orElse("Warehouse"),inboundState.read(inboundScope(s,id),w))).toList();var progress=observations.read(s.tenant(),s.store(),origins.stream().flatMap(o->o.state().checkpoint().origin().children().values().stream()).toList());return Map.of("enabled",adapter.enabled(),"origins",origins,"progress",progress,"revision",p.revision(),"message",adapter.enabled()?"Saved Amazon workflow":adapter.unavailable());}
 @PostMapping("/plans/{id}/origins/{warehouse}/execute") @ResponseBody Object execute(@PathVariable UUID id,@PathVariable UUID warehouse,@RequestBody FbaInboundOrchestration.Command command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return inbound.execute(inboundScope(s,id),warehouse,auth.getName(),command);}
 record Reconcile(UUID requestId){}
 @PostMapping("/plans/{id}/origins/{warehouse}/reconcile") @ResponseBody Object reconcile(@PathVariable UUID id,@PathVariable UUID warehouse,@RequestBody Reconcile command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return inbound.reconcileOperation(inboundScope(s,id),warehouse,auth.getName(),command.requestId());}
 record Options(long revision,FbaInboundAdapter.Read kind){}
 @PostMapping("/plans/{id}/origins/{warehouse}/options") @ResponseBody Object options(@PathVariable UUID id,@PathVariable UUID warehouse,@RequestBody Options command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return inbound.refreshOptions(inboundScope(s,id),warehouse,auth.getName(),command.revision(),command.kind());}
 record Labels(long revision,String child,FbaInboundAdapter.LabelPage page,FbaInboundWorkflow.DocumentKind kind){}
 @PostMapping("/plans/{id}/origins/{warehouse}/labels") @ResponseBody Object labels(@PathVariable UUID id,@PathVariable UUID warehouse,@RequestBody Labels command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return Map.of("document",inbound.saveLabels(inboundScope(s,id),warehouse,auth.getName(),command.revision(),command.child(),command.page(),command.kind()==null?FbaInboundWorkflow.DocumentKind.CARTON_LABEL:command.kind()));}
 @GetMapping("/plans/{id}/origins/{warehouse}/documents/{artifact}") @ResponseBody ResponseEntity<byte[]> document(@PathVariable UUID id,@PathVariable UUID warehouse,@PathVariable UUID artifact,HttpSession session){var s=selection(session);var pdf=documents.read(inboundScope(s,id),warehouse,artifact);return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=\"fba-shipment.pdf\"").header("X-FBA-Document-State",pdf.stale()?"STALE":"CURRENT").body(pdf.payload());}
 @PostMapping("/plans/{id}/dispatch") @ResponseBody Object dispatch(@PathVariable UUID id,@RequestBody FbaChildDispatches.Command command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return children.dispatch(s.tenant(),s.store(),id,auth.getName(),command);}
 @PostMapping("/plans/{id}/dispatch-correction") @ResponseBody Object correction(@PathVariable UUID id,@RequestBody FbaChildDispatches.Command command,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);return children.correct(s.tenant(),s.store(),id,auth.getName(),command);}
 @GetMapping("/settings") @ResponseBody Object settings(HttpSession session){var s=selection(session);return Map.of("policies",settings.policies(s.tenant()),"events",settings.events(s.tenant()));}
 @PostMapping("/policy") @ResponseBody Object policy(@RequestBody FbaReplenishmentSettings.PolicyChange change,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);settings.savePolicy(s.tenant(),s.store(),auth.getName(),change);return Map.of("saved",true);}
 @PostMapping("/event") @ResponseBody Object event(@RequestBody FbaReplenishmentSettings.EventChange change,HttpSession session,Authentication auth){requireManage(auth);var s=selection(session);settings.saveEvent(s.tenant(),s.store(),auth.getName(),change);return Map.of("saved",true);}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseBody ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"Check the entered values.":e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) @ResponseBody ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",e.getMessage()==null?"Review the current saved plan before retrying.":e.getMessage()));}
}
