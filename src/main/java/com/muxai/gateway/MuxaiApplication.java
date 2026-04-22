package com.muxai.gateway;

import com.muxai.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class MuxaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MuxaiApplication.class, args);
    }
}
