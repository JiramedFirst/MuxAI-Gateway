package com.muxai.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.observability.RequestIdFilter;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.ratelimit.RateLimitFilter;
import com.muxai.gateway.ratelimit.RateLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    // Register RequestIdFilter at the container level so it also wraps static
    // resources, actuator endpoints, and the error dispatch — anything that
    // produces a log line benefits from MDC correlation, not just secured paths.
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(
            RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Integer.MIN_VALUE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ConfigRuntime runtime,
                                             AuthenticationEntryPoint entryPoint) {
        return new ApiKeyAuthFilter(runtime, entryPoint);
    }

    // Prevent Spring Boot from also registering ApiKeyAuthFilter as a servlet-container
    // Filter. It should only run inside Spring Security's filter chain — otherwise it runs
    // twice (and the outer copy collides with ErrorPageFilter, swallowing our 401 body).
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter limiter,
                                           ObjectMapper mapper,
                                           RequestMetrics metrics) {
        return new RateLimitFilter(limiter, mapper, metrics);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   AuthenticationEntryPoint entryPoint) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicPaths.PATTERNS.toArray(String[]::new)).permitAll()
                        // Admin REST surface is admin-role only. Static admin assets stay
                        // public above (browsers can't send Bearer on <script src>); the UI
                        // sends Bearer when calling /admin/api/* via fetch.
                        .requestMatchers("/admin/api/**").hasAuthority("ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, ApiKeyAuthFilter.class);
        return http.build();
    }
}
