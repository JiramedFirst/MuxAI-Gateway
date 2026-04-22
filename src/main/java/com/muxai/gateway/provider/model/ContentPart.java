package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One part of a multi-part {@code content} array on a chat message — the
 * OpenAI vision wire format. A part is either a {@code text} or an
 * {@code image_url}. Other providers (Anthropic) translate these into their
 * own block shapes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(
        String type,
        String text,
        @JsonProperty("image_url") ImageUrl imageUrl
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImageUrl(String url, String detail) {}

    public static ContentPart text(String s) {
        return new ContentPart("text", s, null);
    }

    public static ContentPart image(String url) {
        return new ContentPart("image_url", null, new ImageUrl(url, null));
    }

    public static ContentPart image(String url, String detail) {
        return new ContentPart("image_url", null, new ImageUrl(url, detail));
    }
}
