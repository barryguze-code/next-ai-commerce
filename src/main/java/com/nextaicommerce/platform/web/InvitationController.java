package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.invitation.InvitationRole;
import com.nextaicommerce.platform.invitation.InvitationException;
import com.nextaicommerce.platform.invitation.InvitationWorkflowService;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InvitationController {
    private static final Logger log = LoggerFactory.getLogger(InvitationController.class);
    private final InvitationWorkflowService workflow;
    private final WorkspaceAccessRepository accounts;

    InvitationController(InvitationWorkflowService workflow,WorkspaceAccessRepository accounts) {
        this.workflow = workflow; this.accounts=accounts;
    }

    @PostMapping("/app/users/invitations")
    String invite(@RequestParam String email, @RequestParam InvitationRole role,
            @RequestParam(required = false) List<UUID> storeIds,@RequestParam(defaultValue="ACCOUNT") String accessKind,
            @RequestParam(required=false) UUID account,Authentication authentication, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = account!=null?account:(UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        String tenantName = tenantId==null?null:accounts.listAccountOptions(authentication.getName(),accounts.isCurrentPlatformAdmin(authentication.getName())).stream().filter(a->a.id().equals(tenantId)).map(WorkspaceAccessRepository.AccountOption::name).findFirst().orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Account unavailable."));
        if (tenantId == null || tenantName == null) return "redirect:/app/select-account";
        try {
            workflow.invite(tenantId, tenantName, authentication.getName(), email, role,
                storeIds == null ? java.util.Set.of() : new LinkedHashSet<>(storeIds),accessKind);
            redirect.addFlashAttribute("invitationSuccess", "Invitation emailed to " + email.trim().toLowerCase() + ".");
        } catch (InvitationException exception) {
            log.warn("Invitation declined tenantId={} actor={} reason={}",
                tenantId, authentication.getName(), exception.getMessage());
            redirect.addFlashAttribute("invitationError", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Invitation failed tenantId={} actor={}", tenantId, authentication.getName(), exception);
            redirect.addFlashAttribute("invitationError",
                "We couldn't send the invitation. Please try again or contact support.");
        }
        return "redirect:/app/users?account="+tenantId;
    }

    @PostMapping("/app/users/invitations/{invitationId}/resend")
    String resend(@org.springframework.web.bind.annotation.PathVariable UUID invitationId,
            @RequestParam(required=false) UUID account,Authentication authentication, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = account!=null?account:(UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        String tenantName = tenantId==null?null:accounts.listAccountOptions(authentication.getName(),accounts.isCurrentPlatformAdmin(authentication.getName())).stream().filter(a->a.id().equals(tenantId)).map(WorkspaceAccessRepository.AccountOption::name).findFirst().orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Account unavailable."));
        if (tenantId == null || tenantName == null) return "redirect:/app/select-account";
        try {
            workflow.resend(tenantId, tenantName, authentication.getName(), invitationId);
            redirect.addFlashAttribute("invitationSuccess", "A new secure invitation link was emailed.");
        } catch (InvitationException exception) {
            log.warn("Invitation resend declined tenantId={} invitationId={} actor={} reason={}",
                tenantId, invitationId, authentication.getName(), exception.getMessage());
            redirect.addFlashAttribute("invitationError", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Invitation resend failed tenantId={} invitationId={} actor={}",
                tenantId, invitationId, authentication.getName(), exception);
            redirect.addFlashAttribute("invitationError", "We couldn't resend the invitation. Please try again.");
        }
        return "redirect:/app/users?account="+tenantId;
    }

    @PostMapping("/app/users/invitations/{invitationId}/revoke")
    String revoke(@org.springframework.web.bind.annotation.PathVariable UUID invitationId,
            @RequestParam(required=false) UUID account,Authentication authentication, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = account!=null?account:(UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        if (tenantId == null) return "redirect:/app/select-account";
        try {
            workflow.revoke(tenantId, authentication.getName(), invitationId);
            redirect.addFlashAttribute("invitationSuccess", "Invitation revoked.");
        } catch (InvitationException exception) {
            log.warn("Invitation revoke declined tenantId={} invitationId={} actor={} reason={}",
                tenantId, invitationId, authentication.getName(), exception.getMessage());
            redirect.addFlashAttribute("invitationError", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Invitation revoke failed tenantId={} invitationId={} actor={}",
                tenantId, invitationId, authentication.getName(), exception);
            redirect.addFlashAttribute("invitationError", "We couldn't revoke the invitation. Please try again.");
        }
        return "redirect:/app/users?account="+tenantId;
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
        } catch (InvitationException exception) {
            log.warn("Invitation acceptance declined tenantId={} actor={} reason={}",
                tenantId, authentication.getName(), exception.getMessage());
            redirect.addFlashAttribute("invitationError", exception.getMessage());
            return "redirect:/invitation/accept?tenantId=" + tenantId + "&token=" + token;
        } catch (RuntimeException exception) {
            log.error("Invitation acceptance failed tenantId={} actor={}",
                tenantId, authentication.getName(), exception);
            redirect.addFlashAttribute("invitationError",
                "We couldn't accept the invitation. Please try again or contact support.");
            return "redirect:/invitation/accept?tenantId=" + tenantId + "&token=" + token;
        }
    }
}
