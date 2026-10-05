package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ReplenishmentDraftController {
 private final ReplenishmentSuggestions suggestions;
 private final com.nextaicommerce.platform.catalog.CatalogRepository catalog;
 public ReplenishmentDraftController(ReplenishmentSuggestions suggestions,com.nextaicommerce.platform.catalog.CatalogRepository catalog){this.suggestions=suggestions;this.catalog=catalog;}
 @GetMapping("/app/inventory/replenishment")
 String draft(HttpSession session,Authentication auth,Model model,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="forecast") String status){
  if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
  PageController.addAccessModel(auth,model);UUID tenant=(UUID)session.getAttribute("selectedTenantId");
  model.addAttribute("basketScope",tenant+":"+auth.getName());
  var snapshot=suggestions.snapshot(tenant);String search=q.trim().toLowerCase(Locale.ROOT);
  var all=snapshot==null?List.<Map<String,Object>>of():snapshot.items();
  String selected=Set.of("forecast","all","oos","low","healthy","overstock").contains(status)?status:"forecast";
  var filtered=select(all,selected,search);
  model.addAttribute("status",selected);model.addAttribute("forecastCount",all.stream().filter(r->((Number)r.get("cases")).longValue()>0).count());model.addAttribute("allCount",all.size());
  int pageSize=Set.of(25,50,100).contains(size)?size:25;
  int current=Math.min(Math.max(0,page),Math.max(0,(filtered.size()-1)/pageSize)),start=current*pageSize;
  model.addAttribute("items",filtered.subList(start,Math.min(start+pageSize,filtered.size())));
  model.addAttribute("q",q);model.addAttribute("page",current);model.addAttribute("more",start+pageSize<filtered.size());
  model.addAttribute("pageSize",pageSize);model.addAttribute("pageMax",Math.max(1,(filtered.size()+pageSize-1)/pageSize));
  model.addAttribute("generatedAt",snapshot==null?null:snapshot.generatedAt());model.addAttribute("pending",suggestions.pending(tenant));
  model.addAttribute("forecastError",suggestions.error(tenant));
  model.addAttribute("configuration",suggestions.configuration(tenant));model.addAttribute("vendors",suggestions.vendors(tenant));
  model.addAttribute("locations",catalog.listLocations(tenant));
  var counts=new HashMap<String,Long>();for(String category:List.of("oos","low","healthy","overstock"))counts.put(category,all.stream().filter(r->category.equals(r.get("status"))).count());model.addAttribute("counts",counts);
  return "replenishment-draft";
 }
 static List<Map<String,Object>> select(List<Map<String,Object>> all,String selected,String search){
  return all.stream().filter(r->((Number)r.get("demand")).doubleValue()>0)
   .filter(r->selected.equals("all")||(selected.equals("forecast")?((Number)r.get("cases")).longValue()>0:selected.equals(r.get("status"))))
   .filter(r->(r.get("name")+" "+r.get("code")+" "+r.get("vendor_code")).toLowerCase(Locale.ROOT).contains(search))
   .sorted(Comparator.<Map<String,Object>>comparingDouble(r->((Number)r.get("demand")).doubleValue()).reversed().thenComparing(r->r.get("id").toString())).toList();
 }
 @GetMapping("/app/inventory/replenishment/download")
 @ResponseBody
 org.springframework.http.ResponseEntity<String> download(HttpSession session,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="forecast") String status){
  UUID tenant=(UUID)session.getAttribute("selectedTenantId");
  if(tenant==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  var snapshot=suggestions.snapshot(tenant);
  if(snapshot==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Forecast is still being prepared");
  String selected=Set.of("forecast","all","oos","low","healthy","overstock").contains(status)?status:"forecast";
  return org.springframework.http.ResponseEntity.ok().header("Content-Type","text/csv; charset=UTF-8")
   .header("Content-Disposition","attachment; filename=\"replenishment.csv\"")
   .body(csv(select(snapshot.items(),selected,q.trim().toLowerCase(Locale.ROOT))));
 }
 static String csv(List<Map<String,Object>> rows){
  StringBuilder result=new StringBuilder("Item code,Vendor,UPC / EAN,Case qty (suggested),Replenish to buy (eaches)\r\n");
  for(var row:rows){
   var fields=new ArrayList<String>();
   for(String key:List.of("code","vendor_code","upc","cases","suggested_each")){
    Object value=row.get(key);
    String text=value instanceof Number?new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString():Objects.toString(value,"");
    if(text.matches("(?s)^[\\s]*[=+@-].*")||text.startsWith("\t")||text.startsWith("\r"))text="'"+text;
    fields.add("\""+text.replace("\"","\"\"")+"\"");
   }
   result.append(String.join(",",fields)).append("\r\n");
  }
  return result.toString();
 }
 @PostMapping("/app/inventory/replenishment/case-size")
 String caseSize(HttpSession session,Authentication auth,Model model,@RequestParam UUID itemId,@RequestParam(required=false) Integer units,RedirectAttributes redirect){
  UUID tenant=(UUID)session.getAttribute("selectedTenantId");if(tenant==null)return "redirect:/app/select-account";
  PageController.addAccessModel(auth,model);if(!Boolean.TRUE.equals(model.getAttribute("canEditCatalog")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  try{suggestions.saveCaseSize(tenant,itemId,units);redirect.addFlashAttribute("planningMessage","Account case size saved. Forecast refreshes within a minute; master catalogue is unchanged.");}
  catch(IllegalArgumentException e){redirect.addFlashAttribute("planningMessage",e.getMessage());}
  return "redirect:/app/inventory/replenishment";
 }
 @PostMapping(value="/app/inventory/replenishment/case-size",params="inline=true")
 @ResponseBody
 Map<String,Object> inlineCaseSize(HttpSession session,Authentication auth,Model model,@RequestParam UUID itemId,@RequestParam(required=false) Integer units){
  UUID tenant=(UUID)session.getAttribute("selectedTenantId");
  if(tenant==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  PageController.addAccessModel(auth,model);
  if(!Boolean.TRUE.equals(model.getAttribute("canEditCatalog")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  try{suggestions.saveCaseSize(tenant,itemId,units);return suggestions.caseSizePreview(tenant,itemId);}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
 }
 @PostMapping("/app/inventory/replenishment/settings")
 String settings(HttpSession session,Authentication auth,Model model,@RequestParam int low,@RequestParam int target,@RequestParam int overstock,@RequestParam Map<String,String> form,RedirectAttributes redirect){
  UUID tenant=(UUID)session.getAttribute("selectedTenantId");if(tenant==null)return "redirect:/app/select-account";
  PageController.addAccessModel(auth,model);if(!Boolean.TRUE.equals(model.getAttribute("canEditCatalog")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  try{suggestions.save(tenant,auth.getName(),low,target,overstock,form);redirect.addFlashAttribute("planningMessage","Settings saved. Suggestions will refresh within one minute.");}
  catch(IllegalArgumentException e){redirect.addFlashAttribute("planningMessage",e.getMessage());}
  return "redirect:/app/inventory/replenishment";
 }
 @PostMapping("/app/inventory/replenishment/refresh")
 String refresh(HttpSession session,Authentication auth,Model model,RedirectAttributes redirect){
  UUID tenant=(UUID)session.getAttribute("selectedTenantId");if(tenant==null)return "redirect:/app/select-account";
  PageController.addAccessModel(auth,model);if(!Boolean.TRUE.equals(model.getAttribute("canEditCatalog")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  suggestions.requestRefresh(tenant);
  redirect.addFlashAttribute("planningMessage","Forecast queued. Preparation starts within one minute; your last forecast stays visible.");
  return "redirect:/app/inventory/replenishment";
 }
}
