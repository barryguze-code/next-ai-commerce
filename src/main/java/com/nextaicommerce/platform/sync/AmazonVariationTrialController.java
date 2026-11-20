package com.nextaicommerce.platform.sync;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.MemberAccessService;

/** Guarded technical operator controls; no startup task or implicit activation. */
@RestController
@RequestMapping("/app/marketplace-skus/variation-trial")
@ConditionalOnProperty(name="app.amazon.variation.operator-enabled",havingValue="true")
public class AmazonVariationTrialController {
    private static final String OPERATOR_KEY=AmazonVariationTrialController.class.getName()+".idempotencyKey";
    private final AmazonVariationTrial trial;private final AmazonVariationRelationshipSync sync;private final MemberAccessService members;
    public AmazonVariationTrialController(AmazonVariationTrial trial,AmazonVariationRelationshipSync sync,MemberAccessService members){this.trial=trial;this.sync=sync;this.members=members;}
    private void authorize(Authentication auth,HttpSession session){
        if(auth==null||!auth.isAuthenticated()||!AmazonVariationTrial.TENANT.equals(session.getAttribute(AccountSelectionController.TENANT_ID))
            ||!AmazonVariationTrial.CONNECTION.equals(session.getAttribute(AccountSelectionController.STORE_ID)))throw new AccessDeniedException("Select the approved KARACA account and store");
        if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN")))return;
        var access=members.current(AmazonVariationTrial.TENANT,auth.getName());
        if(access==null||!Set.of("OWNER","ADMIN","OPERATOR").contains(access.role())||access.restricted()&&!access.stores().contains(AmazonVariationTrial.CONNECTION))
            throw new AccessDeniedException("Trial operator access is required");
    }
    @GetMapping public Map<String,Object> inspect(Authentication auth,HttpSession session){authorize(auth,session);return trial.inspect(false);}
    @GetMapping("/operator") public org.springframework.web.servlet.ModelAndView operator(Authentication auth,HttpSession session){
        authorize(auth,session);
        synchronized(session){
            if(!(session.getAttribute(OPERATOR_KEY) instanceof UUID))session.setAttribute(OPERATOR_KEY,UUID.randomUUID());
            return new org.springframework.web.servlet.ModelAndView("variation-trial-operator",Map.of(
                "trialKey",session.getAttribute(OPERATOR_KEY),"seedSku",AmazonVariationTrial.SEED,
                "trialMarketplace",AmazonVariationTrial.MARKET));
        }
    }
    @PostMapping("/start") public Map<String,Object> start(@RequestParam UUID idempotencyKey,Authentication auth,HttpSession session){
        authorize(auth,session);try{return Map.of("jobId",sync.startTrial(idempotencyKey));}
        catch(IllegalStateException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Trial is disabled or already exists");}
    }
    @PostMapping("/cancel") public Map<String,Object> cancel(Authentication auth,HttpSession session){authorize(auth,session);return trial.inspect(true);}
}
