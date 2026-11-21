package com.touhoulittlemaid.mini.core.semantic;

/** What a semantic id refers to. Tags are validated per kind. */
public enum SemanticKind {

    ITEM,
    BLOCK,
    ENTITY;

    public static SemanticKind fromId(String id) {
        for (SemanticKind kind : values()) {
            if (kind.name().equalsIgnoreCase(id)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown semantic kind: " + id);
    }
}
