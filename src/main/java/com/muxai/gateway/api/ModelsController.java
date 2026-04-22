package com.muxai.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.api.dto.ModelInfo;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final GatewayProperties props;

    public ModelsController(GatewayProperties props) {
        this.props = props;
    }

    @GetMapping("/models")
    public ResponseEntity<ModelList> list() {
        Map<String, ModelInfo> byId = new LinkedHashMap<>();
        for (ProviderProperties pp : props.providersOrEmpty()) {
            for (String model : pp.modelsOrEmpty()) {
                byId.put(model, ModelInfo.of(model, pp.id()));
            }
        }
        return ResponseEntity.ok(new ModelList("list", new ArrayList<>(byId.values())));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelList(String object, List<ModelInfo> data) {}
}
