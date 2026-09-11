package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice
public class PlatformRequestExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(PlatformRequestExceptionHandler.class);
    private static final Pattern RECEIVING_WORKSPACE=Pattern.compile("^/app/receiving/([0-9a-fA-F-]{36})(?:/.*)?$");

    @ExceptionHandler({MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class,
        BindException.class,MultipartException.class,MaxUploadSizeExceededException.class})
    String invalidRequest(Exception exception,HttpServletRequest request,RedirectAttributes redirect) {
        String path=request.getRequestURI();
        log.warn("Invalid platform request method={} path={} reason={}",request.getMethod(),path,exception.getMessage());
        String message=exception instanceof MaxUploadSizeExceededException
            ?"The selected upload is too large. Choose files up to 20 MB each."
            :path.startsWith("/app/receiving")
                ?"Some receiving information was missing or invalid. Choose the vendor, check the files and amounts, then try again."
                :"Some information was missing or invalid. Review the form and try again.";
        redirect.addFlashAttribute("catalogError",message);
        Matcher workspace=RECEIVING_WORKSPACE.matcher(path);
        if(workspace.matches())return "redirect:/app/receiving/"+workspace.group(1);
        if(path.startsWith("/app/receiving"))return "redirect:/app/receiving";
        return "redirect:/app";
    }
}
