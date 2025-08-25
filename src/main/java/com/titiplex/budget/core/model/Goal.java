package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record Goal(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("target") BigDecimal target,
        @JsonProperty("currency") String currency,
        @JsonProperty("dueTs") long dueTs,  // timestamp cible (optionnel)
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("ver") String ver,
        @JsonProperty("author") String author
) {
}