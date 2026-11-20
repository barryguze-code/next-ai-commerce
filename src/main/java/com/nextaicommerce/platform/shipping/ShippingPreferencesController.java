package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ShippingPreferencesController {
    private final ShippingPreferences preferences;
    public ShippingPreferencesController(ShippingPreferences preferences){this.preferences=preferences;}
    @GetMapping("/app/shipping/preferences") String page(HttpSession session,Authentication authentication,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(authentication,model);model.addAttribute("preferences",preferences.get(id(session,AccountSelectionController.TENANT_ID),id(session,AccountSelectionController.STORE_ID)));return "shipping-preferences";
    }
    @GetMapping("/app/shipping/preferences/data") @ResponseBody ShippingPreferences.Settings data(HttpSession session){return preferences.get(id(session,AccountSelectionController.TENANT_ID),id(session,AccountSelectionController.STORE_ID));}
    @PostMapping("/app/shipping/preferences") String save(@RequestParam String labelSize,@RequestParam CarrierLabelComposer.Layout layout,HttpSession session,Model model,Authentication authentication){
        try {preferences.save(id(session,AccountSelectionController.TENANT_ID),id(session,AccountSelectionController.STORE_ID),new ShippingPreferences.Settings(labelSize,layout));model.addAttribute("saved",true);}
        catch(IllegalArgumentException ex){model.addAttribute("error",ex.getMessage());}
        return page(session,authentication,model);
    }
    private UUID id(HttpSession session,String key){if(session.getAttribute(key) instanceof UUID value)return value;throw new IllegalArgumentException("Choose an account and store first.");}
}
