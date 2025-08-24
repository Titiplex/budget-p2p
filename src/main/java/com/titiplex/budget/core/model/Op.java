
package com.titiplex.budget.core.model;

public record Op(Type type, Object payload) {
    public enum Type {ADD, DELETE}
}
