package com.muxai.gateway.auth;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.GatewayProperties.ApiKey;
import com.muxai.gateway.hotreload.ConfigRuntime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private volatile Map<String, ApiKey> keyToApiKey;
    private final AuthenticationEntryPoint entryPoint;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyAuthFilter(ConfigRuntime runtime, AuthenticationEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
        rebuild(runtime.current());
        runtime.addListener(this::rebuild);
    }

    private void rebuild(GatewayProperties props) {
        Map<String, ApiKey> map = new HashMap<>();
        for (ApiKey k : props.apiKeysOrEmpty()) {
            if (k.key() != null && k.appId() != null) {
                map.put(k.key(), k);
            }
        }
        this.keyToApiKey = Map.copyOf(map);
        log.info("ApiKeyAuthFilter loaded {} API key(s)", keyToApiKey.size());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String p : PublicPaths.PATTERNS) {
            if (pathMatcher.match(p, path)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = extractBearer(header);
        if (token == null) {
            reject(request, response, "Missing or malformed Authorization header");
            return;
        }
        ApiKey apiKey = keyToApiKey.get(token);
        if (apiKey == null) {
            reject(request, response, "Invalid API key");
            return;
        }
        if (apiKey.isExpired(Instant.now())) {
            reject(request, response, "API key expired");
            return;
        }

        AppPrincipal principal = new AppPrincipal(apiKey.appId(), apiKey);
        String authority = "ROLE_" + apiKey.roleOrDefault().toUpperCase(Locale.ROOT);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, token, AuthorityUtils.createAuthorityList(authority));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException, ServletException {
        AuthenticationException ex = new BadCredentialsException(message);
        entryPoint.commence(request, response, ex);
    }

    private static String extractBearer(String header) {
        if (header == null) return null;
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String token = header.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
