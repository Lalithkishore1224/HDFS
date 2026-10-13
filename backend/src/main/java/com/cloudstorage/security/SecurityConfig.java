package com.cloudstorage.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session-based security for a single-page app:
 * <ul>
 *   <li>"Continue with Google" is Spring's OAuth2/OIDC login, active when Google credentials are configured.</li>
 *   <li>Any signed-in person can reach {@code /api/me}; the drive needs an approved account; {@code /api/admin} needs an administrator.</li>
 *   <li>Errors are JSON statuses (401/403), never redirects, because the UI is a separate app.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AccessRules rules,
                                            OidcAccountUserService oidcAccountUserService,
                                            SecurityContextRepository contextRepository,
                                            ObjectProvider<ClientRegistrationRepository> googleRegistrations) throws Exception {
        http
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // The file viewer shows PDFs in a frame; allow that for our own origin only.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/auth/**", "/oauth2/**", "/login/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers("/api/admin/**").access(rules.administrator())
                        .requestMatchers("/api/**").access(rules.approvedMember())
                        .anyRequest().denyAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"You do not have access to this resource\"}");
                        }));

        if (googleRegistrations.getIfAvailable() != null) {
            http.oauth2Login(login -> login
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcAccountUserService))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/?authError=true"));
        }
        return http.build();
    }
}
