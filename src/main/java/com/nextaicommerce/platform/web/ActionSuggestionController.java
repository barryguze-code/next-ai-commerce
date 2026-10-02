package com.nextaicommerce.platform.web;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class ActionSuggestionController {
    private final JdbcTemplate jdbc;
    public ActionSuggestionController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    static String checked(String value,int limit){
        String text=value==null?"":value.trim();
        if(text.isEmpty()||text.length()>limit)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please complete the suggestion within the field limits.");
        return text;
    }
    @PostMapping("/app/action-suggestions") @ResponseBody
    public Map<String,String> submit(@RequestParam String title,@RequestParam String explanation,
            @RequestParam String pagePath,@RequestParam String menuContext,HttpSession session,Authentication auth){
        if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an account first.");
        title=checked(title,160);explanation=checked(explanation,2000);pagePath=checked(pagePath,250);menuContext=checked(menuContext,250);
        if(!pagePath.startsWith("/app")||pagePath.contains("?")||pagePath.contains("#"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page context.");
        jdbc.update("INSERT INTO action_suggestions(tenant_id,submitted_by,page_path,menu_context,title,explanation) VALUES (?,?,?,?,?,?)",tenant,auth.getName(),pagePath,menuContext,title,explanation);
        return Map.of("message","Suggestion saved for R&D review. Thank you for helping improve the platform.");
    }
    @GetMapping("/app/platform/action-suggestions")
    public String review(Model model){
        model.addAttribute("suggestions",jdbc.queryForList("SELECT * FROM action_suggestions ORDER BY created_at DESC LIMIT 500"));
        return "action-suggestions";
    }
    @PostMapping("/app/platform/action-suggestions/{id}")
    public String update(@PathVariable UUID id,@RequestParam String status,@RequestParam(defaultValue="") String reviewNote,Authentication auth){
        if(!Set.of("NEW","UNDER_REVIEW","PLANNED","DEPLOYED","DECLINED").contains(status)||reviewNote.length()>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE action_suggestions SET status=?,review_note=?,reviewed_by=?,updated_at=now() WHERE id=?",status,reviewNote.trim(),auth.getName(),id);
        return "redirect:/app/platform/action-suggestions";
    }
}
