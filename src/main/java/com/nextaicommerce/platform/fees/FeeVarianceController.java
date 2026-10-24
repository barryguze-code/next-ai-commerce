package com.nextaicommerce.platform.fees;

import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/app/fee-variance")
public class FeeVarianceController {
    private final AmazonFeeVarianceRepository fees;
    public FeeVarianceController(AmazonFeeVarianceRepository fees){this.fees=fees;}
    @GetMapping @ResponseBody
    public Map<String,Object> history(@RequestParam(defaultValue="") String order,@RequestParam(defaultValue="") String sku,@RequestParam(defaultValue="0") int page,@RequestParam(required=false) Long snapshot,
                                      HttpSession session,Authentication auth){
        if(!fees.visible())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(order.length()>80||sku.length()>240||order.isBlank()&&sku.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an order or SKU");
        AmazonFeeVarianceRepository.HistoryPage evidence;
        try{evidence=fees.historyPage(tenant(session),store(session),order,sku,page,snapshot);}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
        var scope=fees.context(tenant(session),store(session));
        return Map.of("rows",evidence.rows(),"hasMore",evidence.hasMore(),"page",evidence.page(),"snapshot",evidence.snapshot(),"canApprove",admin(auth)&&fees.approvalsEnabled(),"scope",scope,"caseUrl","ATVPDKIKX0DER".equals(scope.get("marketplace_identifier"))?"https://sellercentral.amazon.com/gp/case-dashboard/lobby.html":"");
    }
    @GetMapping("/visible") @ResponseBody
    public List<Map<String,Object>> visible(@RequestParam String kind,@RequestParam List<String> key,HttpSession session){
        if(!fees.visible())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try{return fees.previews(tenant(session),store(session),kind,key);}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    public record Approval(java.util.List<AmazonFeeVarianceRepository.Choice> items,UUID request){}
    @PostMapping("/approve") @ResponseBody
    public Map<String,Object> approve(@RequestBody Approval request,HttpSession session,Authentication auth){
        if(!admin(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try{return Map.of("id",fees.approveAll(tenant(session),store(session),request.items(),request.request(),auth.getName()),
                "message","Future fee basis updated. Past differences remain.");}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage());}
    }
    static boolean admin(Authentication auth){return auth!=null&&auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_OWNER","ROLE_ADMIN","ROLE_PLATFORM_ADMIN").contains(a.getAuthority()));}
    static UUID tenant(HttpSession session){if(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID id)return id;throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    static UUID store(HttpSession session){if(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID id)return id;throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a store");}
}
