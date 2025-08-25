package com.titiplex.budget.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Rule(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("kind") String kind,      // SUBSTRING | REGEX
        @JsonProperty("pattern") String pattern,
        @JsonProperty("category") String category,
        @JsonProperty("active") boolean active,
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("ver") String ver,
        @JsonProperty("author") String author
) {
}