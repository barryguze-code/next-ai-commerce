package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class WorkspaceStatusAdvice {
    private final WorkspaceAccessRepository repository;

    WorkspaceStatusAdvice(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    @ModelAttribute
    void addWorkspaceStatus(HttpSession session, Model model) {
        Object selectedTenant=session.getAttribute(AccountSelectionController.TENANT_ID);
        boolean processing=selectedTenant instanceof UUID tenantId
            && repository.historicalSyncInProgress(tenantId);
        model.addAttribute("historicalSyncInProgress",processing);
    }
}
