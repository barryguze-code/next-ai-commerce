package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.config.ActivationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

@Controller
public class ActivationController {
    private final ActivationService activationService;

    ActivationController(ActivationService activationService) {
        this.activationService = activationService;
    }

    @GetMapping("/activate")
    String activationPage(@RequestParam UUID tenantId, @RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("valid", activationService.isValid(token));
        return "activate";
    }

    @PostMapping("/activate")
    String activate(@RequestParam UUID tenantId, @RequestParam String token, @RequestParam String password,
            @RequestParam String confirmation, Model model) {
        try {
            activationService.activate(tenantId, token, password, confirmation);
            return "redirect:/login?activated";
        } catch (RuntimeException failure) {
            model.addAttribute("token", token);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("valid", activationService.isValid(token));
            model.addAttribute("error", failure.getMessage());
            return "activate";
        }
    }
}
