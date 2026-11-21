package com.touhoulittlemaid.mini.core.semantic;

/** Rejection codes for the knowledge layer, stable for clients and logs. */
public final class KnowledgeCodes {

    public static final String OK = "ok";
    public static final String UNKNOWN_ID = "unknown_id";
    public static final String KIND_MISMATCH = "kind_mismatch";
    public static final String BAD_TAG = "bad_tag";
    public static final String TAG_CONFLICT = "tag_conflict";
    public static final String USER_LOCKED = "user_locked";
    public static final String LOW_CONFIDENCE = "low_confidence";

    public static final double PENDING_THRESHOLD = 0.6D;

    private KnowledgeCodes() {
    }
}
