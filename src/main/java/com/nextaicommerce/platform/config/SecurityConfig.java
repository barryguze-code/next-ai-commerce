package com.nextaicommerce.platform.config;

import org.springframework.http.HttpMethod;
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
                .requestMatchers("/login", "/activate", "/css/**", "/js/**", "/images/**", "/actuator/health").permitAll()
                // Workspace members need the selected account's logo in the header.  The controller
                // checks that the signed-in user can access the requested account.
                .requestMatchers(HttpMethod.GET, "/app/platform/accounts/*/logo").authenticated()
                .requestMatchers("/app/users/**", "/app/connections/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN")
                .requestMatchers("/app/platform/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/app/collaboration/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers("/app/collaboration/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/app/shipping/policy")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN")
                .requestMatchers("/app/shipping/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers("/app/orders/*/buy-shipping/**", "/app/orders/buy-shipping/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/app/catalog/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/app/vendors/**", "/app/receiving/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/app/inventory/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/app/orders/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/app/marketplace-skus/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR")
                .requestMatchers("/app/catalog/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR", "VIEWER")
                .requestMatchers("/app/marketplace-skus/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR", "VIEWER")
                .requestMatchers("/app/vendors/**", "/app/receiving/**", "/app/orders/**", "/app/inventory/**")
                    .hasAnyRole("PLATFORM_ADMIN", "OWNER", "ADMIN", "OPERATOR", "VIEWER")
                .anyRequest().authenticated())
            // Only browser document navigation should become a post-login destination.
            .requestCache(cache -> cache.requestCache(documentRequestCache()))
            .formLogin(form -> form.loginPage("/login").successHandler((request, response, authentication) -> {
                var cache = documentRequestCache();
                var saved = cache.getRequest(request, response);
                // Discard asset destinations saved by older versions as well.
                if (saved != null && !java.net.URI.create(saved.getRedirectUrl()).getPath().startsWith(request.getContextPath() + "/app/")) {
                    cache.removeRequest(request, response);
                }
                var success = new org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler();
                success.setRequestCache(cache);
                success.setDefaultTargetUrl("/app/select-account");
                success.onAuthenticationSuccess(request, response, authentication);
            }).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private org.springframework.security.web.savedrequest.HttpSessionRequestCache documentRequestCache() {
        var cache = new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
        cache.setRequestMatcher(request -> "GET".equals(request.getMethod())
            && request.getServletPath().startsWith("/app")
            && "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
            && "document".equals(request.getHeader("Sec-Fetch-Dest")));
        return cache;
    }
}
