package com.nextaicommerce.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only explicitly versioned artwork/styles/scripts are immutable; never cache application data. */
@Component
public class VersionedAssetCacheFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI(),version=request.getParameter("v");
        if (("GET".equals(request.getMethod())||"HEAD".equals(request.getMethod()))
                && path.matches("/(css|js|images)/.+\\.(css|js|png|svg|webp|jpg|jpeg)")
                && version!=null&&version.matches("[A-Za-z0-9._-]{1,80}")) {
            response.setHeader("Cache-Control","public, max-age=31536000, immutable");
        }
        chain.doFilter(request,response);
    }
}
