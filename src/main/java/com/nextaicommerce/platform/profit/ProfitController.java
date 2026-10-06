package com.nextaicommerce.platform.profit;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/app/inventory/profit")
public class ProfitController {
    private final ProfitRepository profit;private final ProfitCostRepository costs;
    private final ProfitPricePublisher prices;
    public ProfitController(ProfitRepository profit,ProfitCostRepository costs,ProfitPricePublisher prices){this.profit=profit;this.costs=costs;this.prices=prices;}
    @GetMapping("/price-support") @ResponseBody
    Map<String,Boolean> priceSupport(HttpSession session,@RequestParam(required=false) UUID connection){tenant(session);return Map.of("enabled",prices.available(store(session,connection)));}
    public record PricePreview(String sku,UUID connection,BigDecimal amount){}
    @PostMapping("/price-preview") @ResponseBody
    ProfitPricePublisher.Confirmation pricePreview(HttpSession session,Authentication auth,Model model,@RequestBody PricePreview preview){
        edit(auth,model);return prices.prepare(tenant(session),store(session,preview.connection()),preview.sku(),preview.amount(),auth.getName());
    }
    public record PriceSubmission(UUID confirmation,UUID connection){}
    public record SalePreview(String sku,UUID connection,BigDecimal amount,LocalDate start,LocalDate end){}
    @PostMapping("/sale-preview") @ResponseBody
    ProfitPricePublisher.Confirmation salePreview(HttpSession session,Authentication auth,Model model,@RequestBody SalePreview preview){
        edit(auth,model);
        if(preview.start()==null||preview.end()==null)throw new IllegalArgumentException("Choose both sale dates.");
        return prices.prepare(tenant(session),store(session,preview.connection()),preview.sku(),preview.amount(),auth.getName(),preview.start(),preview.end());
    }
    @PostMapping("/price") @ResponseBody
    Map<String,String> price(HttpSession session,Authentication auth,Model model,@RequestBody PriceSubmission submission){
        edit(auth,model);return Map.of("message",prices.submit(tenant(session),store(session,submission.connection()),submission.confirmation(),auth.getName()));
    }
    @GetMapping @ResponseBody
    Map<String,ProfitRepository.View> summary(HttpSession session,@RequestParam String kind,@RequestParam List<String> key,
                                             @RequestParam(required=false) UUID connection){
        if(key.isEmpty()||key.size()>100||key.stream().anyMatch(k->k.length()>240))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose up to 100 records");
        UUID tenant=tenant(session),store=store(session,connection);
        if("SKU".equals(kind))return profit.skus(tenant,store,key);
        if("ORDER".equals(kind))return profit.orders(tenant,store,key);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown profit view");
    }
    @GetMapping("/rates") @ResponseBody
    List<ProfitShippingRates.Rate> rates(HttpSession session){return costs.rates(tenant(session));}
    public record RateChange(ProfitShippingRates.PackageType packageType,LocalDate effectiveFrom,BigDecimal amount){}
    @PostMapping("/rates") @ResponseBody
    Map<String,String> rate(HttpSession session,Authentication auth,Model model,@RequestBody RateChange change){
        edit(auth,model);costs.addRate(tenant(session),new ProfitShippingRates.Rate(change.packageType(),change.effectiveFrom(),change.amount()),auth.getName());
        return Map.of("message","Rate saved. Earlier dates retain their previous rate.");
    }
    public record PackagesChange(String key,UUID connection,List<ProfitRepository.PackageCost> packages){}
    @PostMapping("/packages") @ResponseBody
    Map<String,String> packages(HttpSession session,Authentication auth,Model model,@RequestBody PackagesChange change){
        edit(auth,model);profit.savePackages(tenant(session),store(session,change.connection()),change.key(),change.packages(),auth.getName());
        return Map.of("message","Shipping costs saved. No label purchased; Amazon is unchanged.");
    }
    public record SkuChange(String key,UUID connection,ProfitShippingRates.PackageType packageType,BigDecimal otherCost,List<ProfitRepository.PackageCost> packages){}
    @PostMapping("/sku") @ResponseBody
    Map<String,String> sku(HttpSession session,Authentication auth,Model model,@RequestBody SkuChange change){
        edit(auth,model);if(change.packages()==null)profit.saveSku(tenant(session),store(session,change.connection()),change.key(),change.packageType(),change.otherCost());
        else profit.saveSku(tenant(session),store(session,change.connection()),change.key(),change.packageType(),change.otherCost(),change.packages());
        return Map.of("message","Cost defaults saved. Amazon price is unchanged.");
    }
    @GetMapping("/defaults") @ResponseBody
    List<ProfitRepository.Defaults> defaults(HttpSession session,@RequestParam List<String> sku,@RequestParam(required=false) UUID connection){
        if(sku.isEmpty()||sku.size()>100||sku.stream().anyMatch(s->s.length()>240))throw new IllegalArgumentException("Choose up to 100 SKUs.");
        return profit.defaults(tenant(session),store(session,connection),sku);
    }
    public record DefaultsChange(String kind,String key,UUID connection,List<ProfitRepository.SkuCostChange> skuChanges,
        List<ProfitRepository.ItemCostChange> itemChanges,List<ProfitRepository.PackageCost> packages,BigDecimal otherCost,String packageType){}
    @PostMapping("/defaults") @ResponseBody
    Map<String,String> defaults(HttpSession session,Authentication auth,Model model,@RequestBody DefaultsChange change){
        edit(auth,model);profit.saveDefaults(tenant(session),store(session,change.connection()),change.kind(),change.key(),change.skuChanges(),change.itemChanges(),change.packages(),change.otherCost(),change.packageType(),auth.getName());
        return Map.of("message","Costs saved. Amazon prices and historical receipt costs are unchanged.");
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    org.springframework.http.ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));
    }
    private static UUID tenant(HttpSession session){Object t=session.getAttribute(AccountSelectionController.TENANT_ID);if(t instanceof UUID id)return id;throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    private static UUID store(HttpSession session,UUID requested){
        // Repository queries always require the selected tenant, including an explicitly
        // requested store from account-wide replenishment. Foreign stores yield no rows.
        if(requested!=null)return requested;
        Object c=session.getAttribute(AccountSelectionController.STORE_ID);if(c instanceof UUID id)return id;
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a store");
    }
    private static void edit(Authentication auth,Model model){PageController.addAccessModel(auth,model);if(!Boolean.TRUE.equals(model.getAttribute("canEditCatalog")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
}
