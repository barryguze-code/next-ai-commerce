package com.nextaicommerce.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/login") String login() { return "login"; }
    @GetMapping({"/", "/app"}) String app() { return "app"; }
}
