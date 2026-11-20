package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app/orders/{order}/buy-shipping/fedex")
public class FedExShippingController {
    private final FedExShippingService shipping;private final WorkspaceAccessRepository access;
    public FedExShippingController(FedExShippingService shipping,WorkspaceAccessRepository access){this.shipping=shipping;this.access=access;}
    @GetMapping FedExShippingService.Context context(@PathVariable String order,HttpSession session,Authentication principal){authorize(session,principal);return shipping.context(tenant(session),store(session),order);}
    @PostMapping("/rates") FedExShippingService.Quote quote(@PathVariable String order,@RequestBody FedExShippingService.QuoteInput input,HttpSession session,Authentication principal){authorize(session,principal);return shipping.quote(tenant(session),store(session),order,input,principal.getName());}
    @PostMapping("/sandbox-rates") FedExSandboxRates.Quote sandboxRates(@PathVariable String order,@RequestBody FedExShippingService.RateOnlyInput input,HttpSession session,Authentication principal){authorize(session,principal);return shipping.sandboxRates(tenant(session),store(session),order,input,principal.getName());}
    @PostMapping("/ship-from") BuyShippingRepository.Address saveSender(@PathVariable String order,@RequestBody BuyShippingRepository.Address input,HttpSession session,Authentication principal){authorize(session,principal);return shipping.saveSender(tenant(session),store(session),order,input);}
    public record Purchase(UUID quoteId,String serviceType,boolean confirmed) {}
    @PostMapping("/recipient") Map<String,Boolean> saveRecipient(@PathVariable String order,@RequestBody FedExPayloads.Address recipient,HttpSession session,Authentication principal){authorize(session,principal);shipping.saveRecipient(tenant(session),store(session),order,recipient);return Map.of("saved",true);}
    @PostMapping("/package") Map<String,Boolean> savePackage(@PathVariable String order,@RequestBody FedExPayloads.Parcel parcel,@RequestParam(defaultValue="false") boolean reuse,HttpSession session,Authentication principal){authorize(session,principal);shipping.savePackage(tenant(session),store(session),order,parcel,reuse);return Map.of("saved",true);}
    @PostMapping("/purchase") FedExShipmentRepository.Shipment purchase(@PathVariable String order,@RequestBody Purchase input,HttpSession session,Authentication principal){authorize(session,principal);if(!input.confirmed())throw new IllegalArgumentException("Confirm the selected service before generating a label.");return shipping.purchase(tenant(session),store(session),order,input.quoteId(),input.serviceType(),principal.getName());}
    @GetMapping("/labels/{id}") ResponseEntity<byte[]> label(@PathVariable String order,@PathVariable UUID id,@RequestParam(required=false) CarrierLabelComposer.Layout layout,HttpSession session,Authentication principal){authorize(session,principal);return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=fedex-"+id+".pdf").body(shipping.label(tenant(session),store(session),order,id,layout));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,FedExClient.CarrierException.class}) ResponseEntity<Map<String,String>> error(RuntimeException ex){return ResponseEntity.status(ex instanceof IllegalArgumentException?400:409).body(Map.of("message",ex.getMessage()));}
    private void authorize(HttpSession session,Authentication actor){
        UUID tenant=tenant(session),store=store(session);
        if(actor==null||!actor.isAuthenticated())throw new org.springframework.security.access.AccessDeniedException("Store operations unavailable.");
        boolean admin=actor.getAuthorities().stream().anyMatch(a->"ROLE_PLATFORM_ADMIN".equals(a.getAuthority()))&&access.isCurrentPlatformAdmin(actor.getName());
        boolean visible=access.listAccountOptions(actor.getName(),admin).stream().filter(a->tenant.equals(a.id())).flatMap(a->a.stores().stream()).anyMatch(s->store.equals(s.id())&&java.util.Set.of("AMAZON","SHOPIFY").contains(s.channel()));
        if(!visible||!access.canOperateAccount(tenant,actor.getName()))throw new org.springframework.security.access.AccessDeniedException("Store operations unavailable.");
    }
    private static UUID tenant(HttpSession session){return selected(session,AccountSelectionController.TENANT_ID);}
    private static UUID store(HttpSession session){return selected(session,AccountSelectionController.STORE_ID);}
    private static UUID selected(HttpSession session,String key){if(session.getAttribute(key) instanceof UUID id)return id;throw new IllegalArgumentException("Choose an account and store first.");}
}
