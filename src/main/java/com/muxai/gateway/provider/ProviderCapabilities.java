package com.muxai.gateway.provider;

public record ProviderCapabilities(
        boolean chat,
        boolean streaming,
        boolean embeddings,
        boolean tools,
        boolean vision
) {
    public static ProviderCapabilities chatOnly() {
        return new ProviderCapabilities(true, false, false, false, false);
    }
    public static ProviderCapabilities chatAndEmbeddings() {
        return new ProviderCapabilities(true, false, true, false, false);
    }
}
