package com.touhoulittlemaid.mini.fabric.semantic;

import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The vanilla-shaped "has an age, ripens at the maximum" contract.
 *
 * <p>Kept separate from the resolver: the resolver decides <em>what</em> a block is, this
 * decides <em>how far along</em> it is. Modded crops that reuse the age convention work
 * without new Java.
 */
public final class CropShape {

    private CropShape() {
    }

    public static boolean looksLikeCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock || state.getBlock() instanceof StemBlock
                || ageProperty(state) != null;
    }

    public static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer
                    && "age".equalsIgnoreCase(property.getName())) {
                return integer;
            }
        }
        return null;
    }

    public static boolean isMature(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        IntegerProperty age = ageProperty(state);
        if (age == null) {
            return false;
        }
        int maximum = age.getPossibleValues().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return state.getValue(age) >= maximum;
    }
}
