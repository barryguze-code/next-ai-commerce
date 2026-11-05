package com.nextaicommerce.platform.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.context.request.ServletWebRequest;

/** Revalidate protected pictures only after the controller has resolved current account access. */
@ControllerAdvice
public class ProtectedImageCacheAdvice implements ResponseBodyAdvice<byte[]> {
    @Value("${app.build-version:development}") private String version="development";
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return org.springframework.http.converter.ByteArrayHttpMessageConverter.class.isAssignableFrom(converter);}
    public byte[] beforeBodyWrite(byte[] body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
        if(body==null||!"GET".equals(request.getMethod().name())||!(request.getURI().getPath().endsWith("/image")||request.getURI().getPath().endsWith("/picture"))||!"image".equals(type.getType())||!(request instanceof ServletServerHttpRequest servlet)||!(response instanceof ServletServerHttpResponse out))return body;
        HttpServletRequest req=servlet.getServletRequest();HttpServletResponse res=out.getServletResponse();
        try{var digest=MessageDigest.getInstance("SHA-256");digest.update(version.getBytes(StandardCharsets.UTF_8));digest.update(String.valueOf(req.getSession(false)==null?null:req.getSession(false).getAttribute("selectedTenantId")).getBytes(StandardCharsets.UTF_8));digest.update(String.valueOf(req.getSession(false)==null?null:req.getSession(false).getAttribute("selectedStoreId")).getBytes(StandardCharsets.UTF_8));digest.update(body);String etag='"'+HexFormat.of().formatHex(digest.digest())+'"';
            response.getHeaders().setCacheControl(CacheControl.noCache().cachePrivate());
            response.getHeaders().setETag(etag);
            if(new ServletWebRequest(req,res).checkNotModified(etag))return null;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return body;
    }
}
