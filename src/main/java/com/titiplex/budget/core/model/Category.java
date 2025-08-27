package com.titiplex.budget.core.model;

public record Category(
        String id,
        String name,
        boolean deleted,
        String ver,
        String author
) {
}