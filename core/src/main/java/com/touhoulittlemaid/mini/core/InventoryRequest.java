package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Typed request for {@code companion_inventory}.
 *
 * <p>IDs are normalised, counts are bounded, and a negative slot means "find by item
 * ID". The body never creates stacks that do not already exist.
 */
public record InventoryRequest(
        InventoryAction action,
        String item,
        int count,
        InventoryTarget source,
        InventoryTarget destination,
        int slot) {

    public InventoryRequest {
        Objects.requireNonNull(action, "action");
        item = item == null ? "" : item.strip().toLowerCase();
        if (count < 1) {
            count = 1;
        }
        count = Math.min(count, 64);
        source = source == null ? InventoryTarget.INVENTORY : source;
        destination = destination == null ? InventoryTarget.INVENTORY : destination;
        slot = Math.max(slot, -1);
    }

    public static InventoryRequest list() {
        return new InventoryRequest(InventoryAction.LIST, "", 1,
                InventoryTarget.INVENTORY, InventoryTarget.INVENTORY, -1);
    }

    public static InventoryRequest read(String item, int slot) {
        return new InventoryRequest(InventoryAction.READ, item, 1,
                InventoryTarget.INVENTORY, InventoryTarget.INVENTORY, slot);
    }

    public boolean hasItem() {
        return !item.isEmpty();
    }
}
