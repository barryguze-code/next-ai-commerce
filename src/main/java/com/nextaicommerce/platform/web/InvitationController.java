package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.invitation.InvitationRole;
import com.nextaicommerce.platform.invitation.InvitationWorkflowService;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InvitationController {
    private final InvitationWorkflowService workflow;

    InvitationController(InvitationWorkflowService workflow) {
        this.workflow = workflow;
    }

    @PostMapping("/app/users/invitations")
    String invite(@RequestParam String email, @RequestParam InvitationRole role,
            @RequestParam(required = false) List<UUID> storeIds,
            Authentication authentication, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        String tenantName = (String) session.getAttribute(AccountSelectionController.TENANT_NAME);
        if (tenantId == null || tenantName == null) return "redirect:/app/select-account";
        try {
            workflow.invite(tenantId, tenantName, authentication.getName(), email, role,
                storeIds == null ? java.util.Set.of() : new LinkedHashSet<>(storeIds));
            redirect.addFlashAttribute("invitationSuccess", "Invitation emailed to " + email.trim().toLowerCase() + ".");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("invitationError", exception.getMessage());
        }
        return "redirect:/app/users";
    }

    @GetMapping("/invitation/accept")
    String acceptPage(@RequestParam UUID tenantId, @RequestParam String token, Model model) {
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("token", token);
        model.addAttribute("accountName", workflow.accountName(tenantId));
        return "accept-invitation";
    }

    @PostMapping("/invitation/accept")
    String accept(@RequestParam UUID tenantId, @RequestParam String token,
            Authentication authentication, RedirectAttributes redirect) {
        try {
            workflow.acceptExisting(tenantId, token, authentication.getName());
            redirect.addFlashAttribute("accountSuccess", "Invitation accepted. Choose the account to continue.");
            return "redirect:/app/select-account";
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("invitationError", exception.getMessage());
            return "redirect:/invitation/accept?tenantId=" + tenantId + "&token=" + token;
        }
    }
}
