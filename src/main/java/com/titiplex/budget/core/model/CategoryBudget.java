package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record CategoryBudget(
        @JsonProperty("id") String id,
        @JsonProperty("category") String category,
        @JsonProperty("monthlyLimit") BigDecimal monthlyLimit,
        @JsonProperty("currency") String currency,
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("ver") String ver,
        @JsonProperty("author") String author,
        @JsonProperty("rolloverMode") String rolloverMode, // NONE|SURPLUS|DEFICIT|BOTH
        @JsonProperty("rolloverCap") BigDecimal rolloverCap
) {
}