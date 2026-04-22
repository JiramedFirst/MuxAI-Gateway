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

    public static ProviderCapabilities openAiFull() {
        return new ProviderCapabilities(true, true, true, true, true);
    }

    public static ProviderCapabilities anthropicFull() {
        return new ProviderCapabilities(true, true, false, true, true);
    }
}
