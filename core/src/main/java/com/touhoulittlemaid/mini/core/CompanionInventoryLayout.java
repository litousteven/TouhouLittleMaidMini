package com.touhoulittlemaid.mini.core;

/**
 * Fixed backpack and equipment layout for a companion.
 *
 * <p>The layout is a stable number so persistence and the MCP contract do not need to
 * change when the visual container grows.
 */
public final class CompanionInventoryLayout {

    /** Nine general backpack slots. */
    public static final int BACKPACK_SLOTS = 9;

    /** Main hand, off hand, head, chest, legs and feet. */
    public static final int EQUIPMENT_SLOTS = 6;

    public static final int TOTAL_SLOTS = BACKPACK_SLOTS + EQUIPMENT_SLOTS;

    private CompanionInventoryLayout() {
    }
}
