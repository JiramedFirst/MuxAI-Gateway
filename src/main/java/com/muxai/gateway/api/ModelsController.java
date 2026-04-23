package com.muxai.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.api.dto.ModelInfo;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final GatewayProperties props;

    public ModelsController(GatewayProperties props) {
        this.props = props;
    }

    @GetMapping("/models")
    public ResponseEntity<ModelList> list(@AuthenticationPrincipal AppPrincipal principal) {
        // If the caller's API key has allowedModels set, filter the listing to
        // those entries — otherwise advertise everything the gateway can reach.
        // Empty allowed list means "no scope" (unrestricted), matching how
        // ModelScopeGuard treats it for chat/embed/ocr.
        List<String> allowed = principal != null ? principal.allowedModelsOrEmpty() : List.of();
        Set<String> allowedSet = allowed.isEmpty() ? null : Set.copyOf(allowed);

        Map<String, ModelInfo> byId = new LinkedHashMap<>();
        for (ProviderProperties pp : props.providersOrEmpty()) {
            for (String model : pp.modelsOrEmpty()) {
                if (allowedSet != null && !allowedSet.contains(model)) continue;
                byId.put(model, ModelInfo.of(model, pp.id()));
            }
        }
        return ResponseEntity.ok(new ModelList("list", new ArrayList<>(byId.values())));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelList(String object, List<ModelInfo> data) {}
}
