package com.nextaicommerce.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class NextAiCommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NextAiCommerceApplication.class, args);
    }
}
