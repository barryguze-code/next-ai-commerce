package com.nextaicommerce.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps the one-second interactive response budget visible during development and UAT. */
@Component
public class WebPerformanceBudgetFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(WebPerformanceBudgetFilter.class);
    private static final long BUDGET_MILLIS=1_000;

    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !"GET".equals(request.getMethod())||!request.getRequestURI().startsWith("/app");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        long started=System.nanoTime();
        try{chain.doFilter(request,response);}
        finally{
            long elapsed=(System.nanoTime()-started)/1_000_000;
            if(!response.isCommitted())response.setHeader("Server-Timing","app;dur="+elapsed);
            if(elapsed>BUDGET_MILLIS)log.warn("Slow page — {} took {} ms (target: under {} ms). "
                +"The request completed successfully.",pageName(request.getRequestURI()),elapsed,BUDGET_MILLIS);
            else log.debug("{} completed in {} ms.",pageName(request.getRequestURI()),elapsed);
        }
    }

    private static String pageName(String uri){
        if(uri.startsWith("/app/orders"))return "Orders";
        if(uri.startsWith("/app/inventory"))return "Inventory";
        if(uri.startsWith("/app/receiving"))return "Receiving";
        if(uri.startsWith("/app/catalog"))return "Catalogue";
        return uri;
    }
}
