package com.nextaicommerce.platform.shipping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Sensitive shipping responses must not enter browser/shared HTTP caches. Never reads or logs bodies. */
@Component
public class ShippingPrivacyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().contains("/buy-shipping")) {
            response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");response.setHeader("Referrer-Policy","no-referrer");
        }
        chain.doFilter(request,response);
    }
}
