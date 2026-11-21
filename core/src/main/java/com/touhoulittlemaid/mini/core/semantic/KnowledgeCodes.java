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
    /** A hostile claim is never applied by a model; the player has to confirm it. */
    public static final String HOSTILE_UNCONFIRMED = "hostile_unconfirmed";
    /** The model listed the id as unknown, so even a tag is only a proposal. */
    public static final String MODEL_UNKNOWN = "model_unknown";
    /** The model answered about content that was not in the request. */
    public static final String UNEXPECTED_ID = "unexpected_id";
    /** The hard exclusion list wins over any model claim. */
    public static final String HOSTILE_EXCLUDED = "hostile_excluded";

    /** Ledger codes: why a scan or batch was refused. */
    public static final String RATE_LIMITED = "rate_limited";
    public static final String SESSION_QUOTA = "session_quota";
    public static final String DAILY_QUOTA = "daily_quota";

    public static final double PENDING_THRESHOLD = 0.6D;

    private KnowledgeCodes() {
    }
}
