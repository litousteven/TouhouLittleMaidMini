package com.touhoulittlemaid.mini.fabric.semantic;

import com.touhoulittlemaid.mini.core.semantic.KnowledgeCandidate;
import com.touhoulittlemaid.mini.core.semantic.SemanticKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds content the player has actually met but the semantic layer cannot explain.
 *
 * <p>The whole registry is never scanned: a large mod pack has tens of thousands of entries
 * and most of them are not in this world. Only what the player owns, what stands near them,
 * and what sits inside reachable containers is a candidate.
 *
 * <p>Blocks are sampled on a {@value #STRIDE}-block grid inside {@value #RADIUS} blocks
 * horizontally and {@value #VERTICAL} vertically. Walking 128³ positions would stall the
 * server; the grid still catches every kind of block a player realistically walks past, while
 * block entities and container contents are read exactly instead of sampled.
 */
public final class UnknownContentScanner {

    public static final int RADIUS = 64;
    public static final int VERTICAL = 16;
    public static final int STRIDE = 2;
    public static final int MAX_CANDIDATES = 128;

    public record Result(List<KnowledgeCandidate> candidates, int positions, int containers,
            boolean truncated) {

        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static Result empty() {
            return new Result(List.of(), 0, 0, false);
        }

        public int count(SemanticKind kind) {
            return (int) candidates.stream().filter(item -> item.kind() == kind).count();
        }
    }

    private UnknownContentScanner() {
    }

    public static Result scan(ServerLevel level, Vec3 center, List<Container> inventories) {
        Map<String, KnowledgeCandidate> found = new LinkedHashMap<>();
        Set<String> classifiedByEntity = new HashSet<>();
        int containers = 0;

        if (inventories != null) {
            for (Container inventory : inventories) {
                addContainer(found, inventory);
            }
        }

        for (Entity entity : level.getEntitiesOfClass(Entity.class,
                AABB.ofSize(center, RADIUS * 2.0D + 1.0D, VERTICAL * 2.0D + 1.0D,
                        RADIUS * 2.0D + 1.0D))) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof ItemEntity itemEntity) {
                addStack(found, itemEntity.getItem());
                continue;
            }
            if (entity instanceof ExperienceOrb) {
                continue;
            }
            addEntity(found, entity);
        }

        BlockPos centerPos = BlockPos.containing(center);
        int minY = Math.max(level.getMinY(), centerPos.getY() - VERTICAL);
        int maxY = Math.min(level.getMaxY(), centerPos.getY() + VERTICAL);

        // Block entities first: a placed chest is a container even though its registry entry
        // says nothing, so the exact pass has to win over the sampled one.
        int chunkRadius = (RADIUS >> 4) + 1;
        int centerChunkX = centerPos.getX() >> 4;
        int centerChunkZ = centerPos.getZ() >> 4;
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius;
                chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius;
                    chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity.getBlockPos().distSqr(centerPos) > (double) RADIUS * RADIUS) {
                        continue;
                    }
                    BlockState state = entity.getBlockState();
                    if (SemanticService.resolve(state, entity).isKnown()) {
                        classifiedByEntity.add(FabricRealFacts.idOf(state.getBlock()));
                    }
                    if (entity instanceof Container container) {
                        containers++;
                        addContainer(found, container);
                    }
                    addBlock(found, state);
                }
            }
        }

        int positions = 0;
        for (int offsetX = -RADIUS; offsetX <= RADIUS; offsetX += STRIDE) {
            for (int offsetZ = -RADIUS; offsetZ <= RADIUS; offsetZ += STRIDE) {
                for (int y = minY; y <= maxY; y += STRIDE) {
                    BlockPos pos = new BlockPos(centerPos.getX() + offsetX, y,
                            centerPos.getZ() + offsetZ);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    positions++;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()
                            || classifiedByEntity.contains(FabricRealFacts.idOf(state.getBlock()))) {
                        continue;
                    }
                    addBlock(found, state);
                }
            }
        }

        List<KnowledgeCandidate> ordered = new ArrayList<>(found.values());
        ordered.sort(Comparator
                .comparing((KnowledgeCandidate item) -> item.kind().name())
                .thenComparing(KnowledgeCandidate::id));
        boolean truncated = ordered.size() > MAX_CANDIDATES;
        if (truncated) {
            ordered = new ArrayList<>(ordered.subList(0, MAX_CANDIDATES));
        }
        return new Result(ordered, positions, containers, truncated);
    }

    private static void addContainer(Map<String, KnowledgeCandidate> found, Container container) {
        if (container == null) {
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            addStack(found, container.getItem(slot));
        }
    }

    private static void addStack(Map<String, KnowledgeCandidate> found, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = FabricRealFacts.idOf(stack.getItem());
        if (id.isEmpty() || SemanticService.resolve(stack).isKnown()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stack", stack.getMaxStackSize());
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            data.put("food", Map.of("nutrition", food.nutrition()));
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            data.put("block", FabricRealFacts.idOf(blockItem.getBlock()));
        }
        found.putIfAbsent(KnowledgeCandidate.key(SemanticKind.ITEM, id),
                KnowledgeCandidate.of(id, SemanticKind.ITEM,
                        SemanticService.rawTags(SemanticKind.ITEM, id), data));
    }

    private static void addBlock(Map<String, KnowledgeCandidate> found, BlockState state) {
        String id = FabricRealFacts.idOf(state.getBlock());
        if (id.isEmpty() || SemanticService.resolve(state).isKnown()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block_entity", state.hasBlockEntity());
        List<String> states = ageStates(state);
        if (!states.isEmpty()) {
            data.put("states", states);
        }
        found.putIfAbsent(KnowledgeCandidate.key(SemanticKind.BLOCK, id),
                KnowledgeCandidate.of(id, SemanticKind.BLOCK,
                        SemanticService.rawTags(SemanticKind.BLOCK, id), data));
    }

    private static void addEntity(Map<String, KnowledgeCandidate> found, Entity entity) {
        String id = FabricRealFacts.idOf(entity.getType());
        if (id.isEmpty() || SemanticService.resolve(entity).isKnown()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mob", entity instanceof Mob);
        found.putIfAbsent(KnowledgeCandidate.key(SemanticKind.ENTITY, id),
                KnowledgeCandidate.of(id, SemanticKind.ENTITY,
                        SemanticService.rawTags(SemanticKind.ENTITY, id), data));
    }

    /** Age-like integer properties, written as {@code age=0..7} for the model. */
    private static List<String> ageStates(BlockState state) {
        List<String> states = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            if (!(property instanceof IntegerProperty integer)) {
                continue;
            }
            String name = property.getName();
            if (!name.equals("age") && !name.equals("stage")) {
                continue;
            }
            Set<Integer> values = new LinkedHashSet<>(integer.getPossibleValues());
            if (values.isEmpty()) {
                continue;
            }
            int min = values.stream().min(Integer::compareTo).orElse(0);
            int max = values.stream().max(Integer::compareTo).orElse(0);
            states.add(name + "=" + min + ".." + max);
        }
        return states;
    }
}
