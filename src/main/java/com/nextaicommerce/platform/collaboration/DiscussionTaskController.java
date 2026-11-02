package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class DiscussionTaskController {
    private final DiscussionTaskConversion conversion;
    DiscussionTaskController(DiscussionTaskConversion conversion) { this.conversion = conversion; }

    @PostMapping("/app/collaboration/reviews/{id}/task")
    String convert(@PathVariable UUID id, HttpSession session, Authentication auth, RedirectAttributes flash) {
        if (!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID account))
            return "redirect:/app/collaboration";
        try {
            var link = conversion.convert(account, id, auth.getName());
            return "redirect:/app/collaboration/board?parent=" + link.parent() + "&task=" + link.task();
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("collaborationError", e.getMessage());
            return "redirect:/app/collaboration?threadId=" + id;
        }
    }
}
