package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record RecurringRule(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("period") String period, // MONTHLY|WEEKLY|YEARLY
        @JsonProperty("day") int day,          // 1..31 (ou 0 pour weekly/yearly->sem/jour)
        @JsonProperty("weekday") int weekday,  // 1=Mon..7=Sun (si WEEKLY)
        @JsonProperty("month") int month,      // 1..12 (si YEARLY)
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("category") String category,
        @JsonProperty("note") String note,
        @JsonProperty("active") boolean active,
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("ver") String ver,
        @JsonProperty("author") String author
) {
}
