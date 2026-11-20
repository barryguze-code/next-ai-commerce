package com.nextaicommerce.platform.sync;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.MemberAccessService;

/** Session/CSRF protected operator entry point; never part of marketplace page rendering. */
@Controller
@RequestMapping("/app/marketplace-skus/variation-coverage")
@ConditionalOnProperty(name={"app.amazon.variation.coverage-enabled","app.amazon.variation.operator-enabled"},havingValue="true")
public class AmazonVariationCoverageController {
    private final AmazonVariationCoverageGrant grant;private final AmazonVariationRelationshipSync sync;private final MemberAccessService members;
    public AmazonVariationCoverageController(AmazonVariationCoverageGrant grant,AmazonVariationRelationshipSync sync,MemberAccessService members){this.grant=grant;this.sync=sync;this.members=members;}
    private void authorize(Authentication auth,HttpSession session){
        var scope=grant.scope();
        if(auth==null||!auth.isAuthenticated()||!scope.tenant().equals(session.getAttribute(AccountSelectionController.TENANT_ID))||!scope.connection().equals(session.getAttribute(AccountSelectionController.STORE_ID)))throw new AccessDeniedException("Select the approved account and store");
        if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN")))return;
        var access=members.current(scope.tenant(),auth.getName());
        if(access==null||!Set.of("OWNER","ADMIN","OPERATOR").contains(access.role())||access.restricted()&&!access.stores().contains(scope.connection()))throw new AccessDeniedException("Operator access is required");
    }
    @GetMapping public ModelAndView inspect(Authentication auth,HttpSession session){authorize(auth,session);return page(null);}
    private ModelAndView page(String error){var state=grant.inspect(false);var model=new HashMap<String,Object>();model.put("scan",state);model.put("scope",grant.displayScope());model.put("approval",grant.approval());model.put("marketplace",grant.scope().marketplace());model.put("error",error);return new ModelAndView("variation-coverage-operator",model);}
    @PostMapping("/start") public ModelAndView start(@RequestParam UUID approval,Authentication auth,HttpSession session){
        authorize(auth,session);try{sync.startCoverage(approval);return new ModelAndView("redirect:/app/marketplace-skus/variation-coverage");}
        catch(IllegalStateException|IllegalArgumentException ex){return page("The scan could not start. Its approval, catalogue manifest or active-job prerequisite needs review. No additional scan was started.");}
    }
    @PostMapping("/cancel") public ModelAndView cancel(Authentication auth,HttpSession session){authorize(auth,session);grant.inspect(true);return new ModelAndView("redirect:/app/marketplace-skus/variation-coverage");}
}
