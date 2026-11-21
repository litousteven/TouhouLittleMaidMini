package com.touhoulittlemaid.mini.fabric.semantic;

import com.touhoulittlemaid.mini.core.semantic.RealFacts;
import com.touhoulittlemaid.mini.core.semantic.SemanticKind;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Registry truth for the semantic layer.
 *
 * <p>Everything here is read from the game: components, tags, block classes and entity base
 * classes. Nothing is configurable and nothing is guessed beyond the documented heuristic
 * rules, which is why a knowledge file cannot override it.
 */
public final class FabricRealFacts implements RealFacts {

    private static final TagKey<Item> ITEM_ORES =
            TagKey.create(Registries.ITEM, Identifier.parse("c:ores"));
    private static final TagKey<Block> BLOCK_ORES =
            TagKey.create(Registries.BLOCK, Identifier.parse("c:ores"));

    @Override
    public boolean exists(SemanticKind kind, String id) {
        Identifier key;
        try {
            key = Identifier.parse(id);
        } catch (RuntimeException exception) {
            return false;
        }
        return switch (kind) {
            case ITEM -> BuiltInRegistries.ITEM.getValue(key) != null;
            case BLOCK -> BuiltInRegistries.BLOCK.getValue(key) != null;
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.getValue(key) != null;
        };
    }

    @Override
    public Set<SemanticTag> realData(SemanticKind kind, String id) {
        if (kind != SemanticKind.ITEM) {
            return Set.of();
        }
        Item item = item(id);
        if (item == null) {
            return Set.of();
        }
        ItemStack stack = new ItemStack(item);
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (stack.get(DataComponents.FOOD) != null) {
            tags.add(SemanticTag.FOOD);
        }
        if (stack.get(DataComponents.COOKING_FUEL) != null) {
            tags.add(SemanticTag.FUEL);
        }
        if (stack.get(DataComponents.TOOL) != null) {
            tags.add(SemanticTag.TOOL);
        }
        if (item instanceof BlockItem blockItem && (blockItem.getBlock() instanceof CropBlock
                || blockItem.getBlock() instanceof StemBlock)) {
            tags.add(SemanticTag.SEED);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    @Override
    public Set<SemanticTag> realTags(SemanticKind kind, String id) {
        return switch (kind) {
            case ITEM -> itemTags(id);
            case BLOCK -> blockTags(id);
            case ENTITY -> Set.of();
        };
    }

    @Override
    public Set<SemanticTag> heuristic(SemanticKind kind, String id) {
        return switch (kind) {
            case ITEM -> Set.of();
            case BLOCK -> blockHeuristic(id);
            case ENTITY -> entityHeuristic(id);
        };
    }

    @Override
    public Set<String> rawTags(SemanticKind kind, String id) {
        return switch (kind) {
            case ITEM -> item(id) == null ? Set.of()
                    : tagsOf(item(id).builtInRegistryHolder().tags());
            case BLOCK -> block(id) == null ? Set.of()
                    : tagsOf(block(id).builtInRegistryHolder().tags());
            case ENTITY -> entityType(id) == null ? Set.of()
                    : tagsOf(entityType(id).builtInRegistryHolder().tags());
        };
    }

    /**
     * Type-level hard exclusions for {@code hostile}.
     *
     * <p>Named pets and tamed animals are instance facts, so the guard has to check them at
     * targeting time; this list is only the part the knowledge layer can decide.
     */
    @Override
    public boolean hostileExcluded(SemanticKind kind, String id) {
        if (kind != SemanticKind.ENTITY) {
            return false;
        }
        EntityType<?> type = entityType(id);
        if (type == null) {
            return false;
        }
        Class<? extends net.minecraft.world.entity.Entity> base = type.getBaseClass();
        return net.minecraft.world.entity.player.Player.class.isAssignableFrom(base)
                || net.minecraft.world.entity.npc.villager.AbstractVillager.class.isAssignableFrom(base)
                || net.minecraft.world.entity.boss.enderdragon.EnderDragon.class
                        .isAssignableFrom(base)
                || net.minecraft.world.entity.boss.wither.WitherBoss.class
                        .isAssignableFrom(base)
                || net.minecraft.world.entity.monster.warden.Warden.class
                        .isAssignableFrom(base);
    }

    private static Set<String> tagsOf(java.util.stream.Stream<? extends TagKey<?>> tags) {
        Set<String> values = tags.map(TagKey::location)
                .map(location -> "#" + location)
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        return values.isEmpty() ? Set.of() : Set.copyOf(values);
    }

    private static EntityType<?> entityType(String id) {
        try {
            return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Set<SemanticTag> itemTags(String id) {
        Item item = item(id);
        if (item == null) {
            return Set.of();
        }
        ItemStack stack = new ItemStack(item);
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (stack.typeHolder().is(ItemTags.LOGS)) {
            tags.add(SemanticTag.LOG);
        }
        if (stack.typeHolder().is(ItemTags.PLANKS)) {
            tags.add(SemanticTag.PLANK);
        }
        if (stack.typeHolder().is(ITEM_ORES)) {
            tags.add(SemanticTag.ORE);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    private Set<SemanticTag> blockTags(String id) {
        Block block = block(id);
        if (block == null) {
            return Set.of();
        }
        var state = block.defaultBlockState();
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (state.is(BlockTags.LOGS)) {
            tags.add(SemanticTag.LOG);
        }
        if (state.is(BlockTags.PLANKS)) {
            tags.add(SemanticTag.PLANK);
        }
        if (state.is(BlockTags.CROPS)) {
            tags.add(SemanticTag.CROP);
        }
        if (state.is(BLOCK_ORES)) {
            tags.add(SemanticTag.ORE);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    private Set<SemanticTag> blockHeuristic(String id) {
        Block block = block(id);
        if (block == null) {
            return Set.of();
        }
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (block instanceof CropBlock || block instanceof StemBlock) {
            tags.add(SemanticTag.CROP);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    private Set<SemanticTag> entityHeuristic(String id) {
        EntityType<?> type;
        try {
            type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
        } catch (RuntimeException exception) {
            return Set.of();
        }
        if (type == null) {
            return Set.of();
        }
        Class<? extends net.minecraft.world.entity.Entity> base = type.getBaseClass();
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (Monster.class.isAssignableFrom(base)) {
            tags.add(SemanticTag.HOSTILE);
        }
        if (Animal.class.isAssignableFrom(base)) {
            tags.add(SemanticTag.LIVESTOCK);
            tags.add(SemanticTag.BREEDABLE);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    /** Container and station checks need the placed block entity, not the registry entry. */
    public Set<SemanticTag> placedBlockTags(BlockEntity entity) {
        if (entity == null) {
            return Set.of();
        }
        Set<SemanticTag> tags = EnumSet.noneOf(SemanticTag.class);
        if (entity instanceof Container) {
            tags.add(SemanticTag.CONTAINER);
        }
        if (entity instanceof AbstractFurnaceBlockEntity
                || entity.getBlockState().getBlock() instanceof CrafterBlock
                || entity.getBlockState().getBlock() instanceof SmithingTableBlock) {
            tags.add(SemanticTag.STATION);
        }
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    private static Item item(String id) {
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Block block(String id) {
        try {
            return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String idOf(Item item) {
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : key.toString();
    }

    static String idOf(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "" : key.toString();
    }

    static String idOf(EntityType<?> type) {
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return key == null ? "" : key.toString();
    }

    /** Keeps the Mob import meaningful for future hostile filtering rules. */
    static boolean isMob(EntityType<?> type) {
        return Mob.class.isAssignableFrom(type.getBaseClass());
    }
}
