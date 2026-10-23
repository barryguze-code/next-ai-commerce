package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.shipping.CarrierConnectionRepository;
import com.nextaicommerce.platform.shipping.CarrierConnectionService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class CarrierConnectionsController {
    private final CarrierConnectionService connections;
    public CarrierConnectionsController(CarrierConnectionService connections){this.connections=connections;}
    @GetMapping("/app/connections/carriers")
    String page(HttpSession session,Authentication authentication,Model model) {
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(authentication,model);
        return "carrier-connections";
    }
    @GetMapping("/app/connections/carriers/data") @ResponseBody
    List<CarrierConnectionRepository.Connection> list(HttpSession session){return connections.list(tenant(session));}
    @PostMapping("/app/connections/carriers/save") @ResponseBody
    CarrierConnectionRepository.Connection save(@RequestBody CarrierConnectionService.Input input,HttpSession session){return connections.save(tenant(session),input);}
    @PostMapping("/app/connections/carriers/{id}/health") @ResponseBody
    CarrierConnectionRepository.Connection health(@PathVariable UUID id,HttpSession session){return connections.health(tenant(session),id);}
    @PostMapping("/app/connections/carriers/{id}/delete") @ResponseBody
    Map<String,Boolean> delete(@PathVariable UUID id,HttpSession session){connections.delete(tenant(session),id);return Map.of("deleted",true);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) @ResponseBody
    ResponseEntity<Map<String,String>> invalid(RuntimeException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) @ResponseBody
    ResponseEntity<Map<String,String>> duplicate(){return ResponseEntity.status(409).body(Map.of("message","This FedEx account is already connected in this environment."));}
    static UUID tenant(HttpSession session){if(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID id)return id;throw new IllegalArgumentException("Choose an account first.");}
}
