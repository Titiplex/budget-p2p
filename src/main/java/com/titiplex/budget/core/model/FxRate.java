package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record FxRate(
        @JsonProperty("code") String code,          // ex: EUR, CAD
        @JsonProperty("perBase") BigDecimal perBase, // combien vaut 1 code en BASE (ex: en EUR)
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("ver") String ver,
        @JsonProperty("author") String author
) {
}