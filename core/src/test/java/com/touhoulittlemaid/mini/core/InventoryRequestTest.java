package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryRequestTest {

    @Test
    void actionAndTargetIdsParse() {
        for (InventoryAction action : InventoryAction.values()) {
            assertEquals(action, InventoryAction.fromId(action.id().toUpperCase()));
        }
        for (InventoryTarget target : InventoryTarget.values()) {
            assertEquals(target, InventoryTarget.fromId(target.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> InventoryAction.fromId("farm"));
        assertThrows(IllegalArgumentException.class, () -> InventoryTarget.fromId("bag"));
    }

    @Test
    void defaultsAreListAndInventory() {
        InventoryRequest request = InventoryRequest.list();
        assertEquals(InventoryAction.LIST, request.action());
        assertEquals(InventoryTarget.INVENTORY, request.source());
        assertEquals(InventoryTarget.INVENTORY, request.destination());
        assertEquals(-1, request.slot());
        assertFalse(request.hasItem());
    }

    @Test
    void itemIsTrimmedAndCountAndSlotAreBounded() {
        InventoryRequest request = new InventoryRequest(InventoryAction.MOVE,
                "  Minecraft:Wheat_Seeds ", 0,
                InventoryTarget.INVENTORY, InventoryTarget.MAIN_HAND, -9);
        assertEquals("minecraft:wheat_seeds", request.item());
        assertEquals(1, request.count());
        assertEquals(-1, request.slot());
        assertTrue(request.hasItem());

        InventoryRequest huge = new InventoryRequest(InventoryAction.MOVE,
                "minecraft:wheat", 999,
                InventoryTarget.INVENTORY, InventoryTarget.MAIN_HAND, 3);
        assertEquals(64, huge.count());
        assertEquals(3, huge.slot());
    }

    @Test
    void equipmentTargetsAreRecognised() {
        assertFalse(InventoryTarget.INVENTORY.isEquipment());
        assertTrue(InventoryTarget.MAIN_HAND.isEquipment());
        assertTrue(InventoryTarget.HEAD.isEquipment());
    }

    @Test
    void readHelperFindsBySlot() {
        InventoryRequest request = InventoryRequest.read("", 4);
        assertEquals(InventoryAction.READ, request.action());
        assertEquals(4, request.slot());
        assertFalse(request.hasItem());
    }
}
