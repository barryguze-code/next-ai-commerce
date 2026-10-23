package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app/orders/{order}/buy-shipping/fedex")
public class FedExShippingController {
    private final FedExShippingService shipping;
    public FedExShippingController(FedExShippingService shipping){this.shipping=shipping;}
    @GetMapping FedExShippingService.Context context(@PathVariable String order,HttpSession session){return shipping.context(tenant(session),store(session),order);}
    @PostMapping("/rates") FedExShippingService.Quote quote(@PathVariable String order,@RequestBody FedExShippingService.QuoteInput input,HttpSession session,Principal principal){return shipping.quote(tenant(session),store(session),order,input,principal.getName());}
    public record Purchase(UUID quoteId,String serviceType,boolean confirmed) {}
    @PostMapping("/package") Map<String,Boolean> savePackage(@PathVariable String order,@RequestBody FedExPayloads.Parcel parcel,@RequestParam(defaultValue="false") boolean reuse,HttpSession session){shipping.savePackage(tenant(session),store(session),order,parcel,reuse);return Map.of("saved",true);}
    @PostMapping("/purchase") FedExShipmentRepository.Shipment purchase(@PathVariable String order,@RequestBody Purchase input,HttpSession session){if(!input.confirmed())throw new IllegalArgumentException("Confirm the selected service before generating a label.");return shipping.purchase(tenant(session),store(session),order,input.quoteId(),input.serviceType());}
    @GetMapping("/labels/{id}") ResponseEntity<byte[]> label(@PathVariable String order,@PathVariable UUID id,@RequestParam(required=false) CarrierLabelComposer.Layout layout,HttpSession session){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=fedex-"+id+".pdf").body(shipping.label(tenant(session),store(session),order,id,layout));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,FedExClient.CarrierException.class}) ResponseEntity<Map<String,String>> error(RuntimeException ex){return ResponseEntity.status(ex instanceof IllegalArgumentException?400:409).body(Map.of("message",ex.getMessage()));}
    private static UUID tenant(HttpSession session){return selected(session,AccountSelectionController.TENANT_ID);}
    private static UUID store(HttpSession session){return selected(session,AccountSelectionController.STORE_ID);}
    private static UUID selected(HttpSession session,String key){if(session.getAttribute(key) instanceof UUID id)return id;throw new IllegalArgumentException("Choose an account and Amazon store first.");}
}
