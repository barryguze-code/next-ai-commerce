package com.nextaicommerce.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/activate", "/css/**", "/images/**", "/actuator/health").permitAll()
                .requestMatchers("/app/users/**", "/app/connections/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN")
                .requestMatchers("/app/platform/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers("/app/orders/**", "/app/inventory/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR", "VIEWER")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/app/select-account", false).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
