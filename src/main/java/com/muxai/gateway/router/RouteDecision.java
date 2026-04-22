package com.muxai.gateway.router;

import com.muxai.gateway.config.RouteProperties;

import java.util.List;

public record RouteDecision(
        RouteProperties source,
        int index,
        RouteProperties.Step primary,
        List<RouteProperties.Step> fallback,
        String description
) {}
