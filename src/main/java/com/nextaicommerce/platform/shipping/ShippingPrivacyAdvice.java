package com.nextaicommerce.platform.shipping;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Do not echo JSON parser fragments or rejected customer input from shipping forms. */
@RestControllerAdvice(assignableTypes=FedExShippingController.class)
public class ShippingPrivacyAdvice {
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,String>> invalidInput(Exception ignored){return ResponseEntity.badRequest().body(Map.of("message","Check the shipping form fields and try again."));}
}
