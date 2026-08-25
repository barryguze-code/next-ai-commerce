package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.config.ActivationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ActivationController {
    private final ActivationService activationService;

    ActivationController(ActivationService activationService) {
        this.activationService = activationService;
    }

    @GetMapping("/activate")
    String activationPage(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("valid", activationService.isValid(token));
        return "activate";
    }

    @PostMapping("/activate")
    String activate(@RequestParam String token, @RequestParam String password,
            @RequestParam String confirmation, Model model) {
        try {
            activationService.activate(token, password, confirmation);
            return "redirect:/login?activated";
        } catch (IllegalArgumentException failure) {
            model.addAttribute("token", token);
            model.addAttribute("valid", activationService.isValid(token));
            model.addAttribute("error", failure.getMessage());
            return "activate";
        }
    }
}
