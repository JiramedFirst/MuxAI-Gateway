package com.muxai.gateway.router;

import com.muxai.gateway.config.RouteProperties;
import com.muxai.gateway.hotreload.ConfigRuntime;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;

@Component
public class RouteMatcher {

    private final ConfigRuntime runtime;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public RouteMatcher(ConfigRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * First declared rule wins. Returns null if no rule matches.
     */
    public RouteDecision findRoute(String appId, String model) {
        List<RouteProperties> routes = runtime.current().routesOrEmpty();
        for (int i = 0; i < routes.size(); i++) {
            RouteProperties r = routes.get(i);
            RouteProperties.Match m = r.match() != null ? r.match() : RouteProperties.Match.empty();

            if (m.appId() != null && !m.appId().equals(appId)) continue;
            if (m.model() != null && model != null && !modelMatches(m.model(), model)) continue;
            if (m.model() != null && model == null) continue;

            String description = "route[" + i + "] match=" + describeMatch(m);
            return new RouteDecision(r, i, r.primary(), r.fallbackOrEmpty(), description);
        }
        return null;
    }

    private boolean modelMatches(String pattern, String actual) {
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return pattern.equals(actual);
        }
        return antPathMatcher.match(pattern, actual);
    }

    private String describeMatch(RouteProperties.Match m) {
        String appPart = m.appId() == null ? "*" : m.appId();
        String modelPart = m.model() == null ? "*" : m.model();
        return "{app=" + appPart + ",model=" + modelPart + "}";
    }
}
