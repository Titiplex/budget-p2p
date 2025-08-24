
package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record Expense(
        @JsonProperty("id") String id,
        @JsonProperty("who") String who,
        @JsonProperty("category") String category,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("note") String note,
        @JsonProperty("ts") long ts,
        @JsonProperty("deleted") boolean deleted
) {
}
