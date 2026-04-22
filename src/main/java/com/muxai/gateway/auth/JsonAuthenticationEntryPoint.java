package com.muxai.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String message = authException != null && authException.getMessage() != null
                ? authException.getMessage()
                : "Unauthorized";
        byte[] payload = objectMapper.writeValueAsBytes(
                ErrorResponse.of(message, "auth_error", "UNAUTHORIZED"));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.flushBuffer();
    }
}
