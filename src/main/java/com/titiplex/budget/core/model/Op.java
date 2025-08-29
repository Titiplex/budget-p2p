package com.titiplex.budget.core.model;

public record Op(Type type, Object payload) {
    public enum Type {
        ADD, DELETE,
        BUDGET_UPSERT, BUDGET_DELETE,
        FX_UPSERT, FX_DELETE,
        RULE_UPSERT, RULE_DELETE,
        RECUR_UPSERT, RECUR_DELETE,
        GOAL_UPSERT, GOAL_DELETE,
        CATEGORY_UPSERT, CATEGORY_DELETE,
        MEMBER_UPSERT, MEMBER_DELETE
    }
}