package org.mifos.creditbureau.cb_ild.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration — Phase 1.
 *
 * Phase 1 design (confirmed by Victor — no OAuth2 on mifos-bank-1):
 *   Permit all requests — no authentication enforcement.
 *   CSRF disabled — CB-ILD is a stateless REST API, not a browser app.
 *   Stateless sessions — no server-side session storage.
 *
 * Why @EnableMethodSecurity is NOT here in Phase 1:
 *   @PreAuthorize on BureauReadinessController requires roles.
 *   In permit-all mode, SecurityContextHolder has anonymousUser.
 *   anonymousUser has no roles — @PreAuthorize blocks all requests.
 *   @EnableMethodSecurity added in Phase 2 when real auth configured.
 *
 * Phase 2 plan:
 *   1. Add Basic Auth or API key validation
 *   2. Add @EnableMethodSecurity to this class
 *   3. @PreAuthorize on controller enforces roles automatically
 *   4. No JWT — Victor confirmed no OAuth2 on mifos-bank-1
 *
 * CorrelationIdFilter:
 *   Runs at HIGHEST_PRECEDENCE before this security filter chain.
 *   Sets requestId + userId in MDC.
 *   userId = "anonymous" in Phase 1 — recorded in audit_entry.
 *
 * Security maintained in Phase 1:
 *   CSRF disabled — no browser session cookies
 *   Stateless — no HttpSession created
 *   No hardcoded credentials
 *   Audit trail active — every @Auditable call recorded
 *   userId = "anonymous" in all audit entries until Phase 2
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Phase 1 security filter chain.
     * Stateless + CSRF disabled + permit all.
     *
     * Phase 2: replace permitAll() with authentication requirements.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
            // CSRF disabled — stateless REST API, no browser cookies
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless — no server-side sessions
            .sessionManagement(session -> session
                    .sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS))

            // Phase 1 — permit all requests
            // Phase 2 — replace with authentication requirements
            .authorizeHttpRequests(auth -> auth
                    .anyRequest().permitAll());

        return http.build();
    }
}
