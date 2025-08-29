package com.titiplex.budget.core.model;

public record Member(
        String id,       // userId
        String name,     // displayName
        boolean deleted,
        String ver,
        String author
) {
}