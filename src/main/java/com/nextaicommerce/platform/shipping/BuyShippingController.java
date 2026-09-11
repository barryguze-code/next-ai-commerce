package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuyShippingController {
    private static final Logger log=LoggerFactory.getLogger(BuyShippingController.class);
    private final BuyShippingService shipping;private final BuyShippingRepository repository;private final ShippingLabelCrypto crypto;private final ShippingDeskRepository desk;
    public BuyShippingController(BuyShippingService shipping,BuyShippingRepository repository,ShippingLabelCrypto crypto,ShippingDeskRepository desk){
        this.shipping=shipping;this.repository=repository;this.crypto=crypto;this.desk=desk;
    }

    @GetMapping("/app/orders/{orderId}/buy-shipping")
    BuyShippingService.Context context(@PathVariable String orderId,HttpSession session){
        long started=System.nanoTime();log.info("Buy Shipping details started — order {}",orderId);
        var result=shipping.context(tenant(session),connection(session),orderId);
        log.info("Buy Shipping details ready — order {} — {} item(s), {} saved package(s), {} purchased or pending shipment(s) — {} ms",
            orderId,result.items().size(),result.profiles().size(),result.shipments().size(),elapsed(started));
        return result;
    }

    @PostMapping("/app/orders/{orderId}/buy-shipping/ship-from")
    BuyShippingRepository.Address saveAddress(@PathVariable String orderId,@RequestBody BuyShippingRepository.Address input,HttpSession session){
        UUID tenant=tenant(session),connection=connection(session);shipping.context(tenant,connection,orderId);
        return shipping.saveAddress(tenant,connection,input);
    }

    @PostMapping("/app/orders/{orderId}/buy-shipping/rates")
    BuyShippingService.QuoteResult rates(@PathVariable String orderId,@RequestBody BuyShippingService.QuoteInput input,
            HttpSession session,Principal principal){
        int packages=input.packages()==null?0:input.packages().size();long started=System.nanoTime();
        log.info("Buy Shipping rate check started — order {} — {} package(s) — asking Amazon for eligible services",orderId,packages);
        var result=shipping.quote(tenant(session),connection(session),orderId,input,principal.getName());
        int offers=result.packages().stream().mapToInt(value->value.offers().size()).sum();
        log.info("Buy Shipping rate check complete — order {} — {} eligible rate(s) across {} package(s) — {} ms",orderId,offers,result.packages().size(),elapsed(started));
        return result;
    }

    @PostMapping("/app/orders/{orderId}/buy-shipping/purchase")
    ResponseEntity<Map<String,String>> purchase(@PathVariable String orderId,@RequestParam UUID shipmentId,@RequestParam UUID offerId,
            HttpSession session,Principal principal){
        UUID tenant=tenant(session),connection=connection(session);
        shipping.queuePurchase(tenant,connection,orderId,shipmentId,offerId,principal.getName());
        log.info("Buy Shipping label purchase queued — order {} — shipment {} — waiting for Amazon label creation",orderId,shipmentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("state","PURCHASE_QUEUED"));
    }

    @GetMapping("/app/orders/{orderId}/buy-shipping/status")
    Object status(@PathVariable String orderId,HttpSession session){return shipping.status(tenant(session),connection(session),orderId);}

    @GetMapping(value="/app/orders/buy-shipping/shipments/{shipmentId}/label",produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> label(@PathVariable UUID shipmentId,HttpSession session){
        UUID tenant=tenant(session);byte[] label=shipping.label(tenant,connection(session),shipmentId,crypto);desk.recordAccess(tenant,shipmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            "inline; filename=amazon-buy-shipping-"+shipmentId+".pdf").body(label);
    }

    @PostMapping("/app/orders/buy-shipping/shipments/{shipmentId}/refund")
    ResponseEntity<Map<String,String>> refund(@PathVariable UUID shipmentId,HttpSession session,Principal principal){
        shipping.queueRefund(tenant(session),connection(session),shipmentId,principal.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("state","REFUND_QUEUED"));
    }

    @PostMapping("/app/connections/{connectionId}/buy-shipping")
    Map<String,String> settings(@PathVariable UUID connectionId,@RequestParam String mode,
            @RequestParam(defaultValue="true") boolean printPackingSlip,HttpSession session){
        if(!connection(session).equals(connectionId))throw new IllegalArgumentException("This is not the selected Amazon store.");
        if("PURCHASE_ENABLED".equals(mode)&&!crypto.configured())throw new IllegalStateException("Label encryption is not configured. Set APP_CREDENTIAL_ENCRYPTION_KEY before enabling purchases.");
        repository.setMode(tenant(session),connectionId,mode,printPackingSlip);return Map.of("mode",mode);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex,HttpServletRequest request){log.warn("Buy Shipping request needs attention — {} — {}",request.getRequestURI(),ex.getMessage());return error(HttpStatus.BAD_REQUEST,ex.getMessage());}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> conflict(IllegalStateException ex,HttpServletRequest request){log.warn("Buy Shipping request could not continue — {} — {}",request.getRequestURI(),ex.getMessage());return error(HttpStatus.CONFLICT,ex.getMessage());}
    @ExceptionHandler(AmazonSpApiClient.AmazonApiException.class)
    ResponseEntity<Map<String,String>> amazon(AmazonSpApiClient.AmazonApiException ex,HttpServletRequest request){log.error("Buy Shipping Amazon request failed — {} — Amazon HTTP {} — {}",request.getRequestURI(),ex.status(),ex.getMessage(),ex);return error(ex.status()==429?HttpStatus.TOO_MANY_REQUESTS:HttpStatus.BAD_GATEWAY,ex.getMessage());}
    @ExceptionHandler(AmazonSpApiClient.AmazonTransportException.class)
    ResponseEntity<Map<String,String>> transport(AmazonSpApiClient.AmazonTransportException ex,HttpServletRequest request){log.error("Buy Shipping could not reach Amazon — {} — {}",request.getRequestURI(),ex.getMessage(),ex);return error(HttpStatus.GATEWAY_TIMEOUT,ex.getMessage());}
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,String>> database(DataAccessException ex,HttpServletRequest request){String reference=reference();log.error("Buy Shipping database connection interrupted — {} — reference {}",request.getRequestURI(),reference,ex);return error(HttpStatus.SERVICE_UNAVAILABLE,"The database connection was interrupted. No label was purchased. Please try again. Reference "+reference+".");}
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> unexpected(Exception ex,HttpServletRequest request){String reference=reference();log.error("Buy Shipping request failed unexpectedly — {} — reference {}",request.getRequestURI(),reference,ex);return error(HttpStatus.INTERNAL_SERVER_ERROR,"Buy Shipping could not complete this request. No label was purchased. Please try again. Reference "+reference+".");}
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String message){return ResponseEntity.status(status)
        .contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8)).body(Map.of("message",message==null?"The request could not be completed.":message));}
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
    private static String reference(){return UUID.randomUUID().toString().substring(0,8).toUpperCase();}
    private static UUID tenant(HttpSession session){Object id=session.getAttribute(AccountSelectionController.TENANT_ID);if(id instanceof UUID value)return value;throw new IllegalArgumentException("Choose an account first.");}
    private static UUID connection(HttpSession session){Object id=session.getAttribute(AccountSelectionController.STORE_ID);if(id instanceof UUID value)return value;throw new IllegalArgumentException("Choose an Amazon store first.");}
}
