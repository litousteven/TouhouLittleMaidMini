package com.touhoulittlemaid.mini.fabric.plan;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Item id and tag matching shared by modules and predicates. */
final class ItemMatch {

    private ItemMatch() {
    }

    static boolean matches(ItemStack stack, boolean tag, String id) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            if (tag) {
                TagKey<Item> key = TagKey.create(Registries.ITEM, Identifier.parse(id));
                return stack.typeHolder().is(key);
            }
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return item != null && stack.getItem() == item;
        } catch (RuntimeException exception) {
            // An unparsable id is simply "not this item"; the module reports the code.
            return false;
        }
    }

    static Optional<Identifier> idOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
