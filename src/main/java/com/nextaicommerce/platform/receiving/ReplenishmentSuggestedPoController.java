package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Projects the completed account forecast; never saves a PO or recalculates demand. */
@RestController
public class ReplenishmentSuggestedPoController {
 private final ReplenishmentSuggestions suggestions;private final WorkspaceAccessRepository access;
 public ReplenishmentSuggestedPoController(ReplenishmentSuggestions suggestions,WorkspaceAccessRepository access){this.suggestions=suggestions;this.access=access;}
 @GetMapping("/app/inventory/replenishment/suggested-po")
 ResponseEntity<?> suggested(HttpSession session,Authentication auth,@RequestHeader("X-Workspace-Account") UUID workspace){
  if(auth==null||!(session.getAttribute("selectedTenantId") instanceof UUID tenant)||!access.canOperateAccount(tenant,auth.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(!tenant.equals(workspace))throw new ResponseStatusException(HttpStatus.CONFLICT,"Your account changed. Reopen Replenishment in the intended account.");
  var snapshot=suggestions.snapshot(tenant);
  if(snapshot==null||suggestions.pending(tenant)||snapshot!=suggestions.snapshot(tenant)){suggestions.ensureFresh(tenant);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ready",false,"items",List.of()));}
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ready",true,"generatedAt",snapshot.generatedAt(),"items",items(snapshot.items())));
 }
 static List<Map<String,Object>> items(List<Map<String,Object>> source){
  var result=new ArrayList<Map<String,Object>>();
  for(var row:source){
   if(((Number)row.get("cases")).doubleValue()<=0||((Number)row.get("demand")).doubleValue()<=0)continue;
   double pack=((Number)row.get("pack")).doubleValue();long each=Math.round(((Number)row.get("suggested_each")).doubleValue());
   if(!Double.isFinite(pack)||pack<=0||each<1||each>100000000)throw new ResponseStatusException(HttpStatus.CONFLICT,"A suggested quantity needs review. Your current draft is unchanged.");
   var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("name",row.get("name"));item.put("code",row.get("code"));item.put("upc",row.getOrDefault("upc",""));item.put("vendor",row.get("vendor"));item.put("vendorId",row.get("vendor_id"));item.put("dc",row.getOrDefault("dc",""));item.put("pack",row.get("pack"));item.put("each",each);item.put("cost",row.get("unit_cost"));item.put("currency","USD");
   result.add(item);
   if(result.size()>500)throw new ResponseStatusException(HttpStatus.CONFLICT,"This forecast has more than 500 suggested items. Keep your current draft and select up to 500 items per plan.");
  }
  return result;
 }
}
