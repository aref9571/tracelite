package com.tracelite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    ApiKeyRegistry apiKeyRegistry(ApiSecurityProperties properties) {
        ApiKeyRegistry registry = new ApiKeyRegistry(properties);
        if (!registry.hasKeys()) {
            log.warn("No API keys are configured, so every request to /api/** will be rejected");
        }
        return registry;
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ApiKeyRegistry apiKeyRegistry, ObjectMapper objectMapper) throws Exception {
        SecurityProblemResponder problemResponder = new SecurityProblemResponder(objectMapper);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyRegistry), AnonymousAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemResponder)
                        .accessDeniedHandler(problemResponder))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/events").hasAuthority(ApiKeyRegistry.INGEST_EVENTS)
                        .requestMatchers("/api/v1/issues", "/api/v1/issues/**").hasAuthority(ApiKeyRegistry.MANAGE_ISSUES)
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(ApiSecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.allowedOrigins().isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(properties.allowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "POST"));
            configuration.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, ApiKeyAuthenticationFilter.API_KEY_HEADER));
            configuration.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }
}
