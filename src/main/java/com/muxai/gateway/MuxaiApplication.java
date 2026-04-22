package com.muxai.gateway;

import com.muxai.gateway.cache.CacheProperties;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.hotreload.HotReloadProperties;
import com.muxai.gateway.pii.PiiProperties;
import com.muxai.gateway.ratelimit.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        GatewayProperties.class,
        PiiProperties.class,
        CacheProperties.class,
        HotReloadProperties.class,
        RateLimitProperties.class
})
public class MuxaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MuxaiApplication.class, args);
    }
}
