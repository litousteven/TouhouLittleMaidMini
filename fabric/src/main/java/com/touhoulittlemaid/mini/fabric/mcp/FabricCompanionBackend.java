package com.touhoulittlemaid.mini.fabric.mcp;

import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.ContextQuery;
import com.touhoulittlemaid.mini.core.InteractButton;
import com.touhoulittlemaid.mini.core.InteractHand;
import com.touhoulittlemaid.mini.core.InteractRequest;
import com.touhoulittlemaid.mini.core.InteractTargetKind;
import com.touhoulittlemaid.mini.core.InventoryRequest;
import com.touhoulittlemaid.mini.core.InventoryTarget;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.MoveMode;
import com.touhoulittlemaid.mini.core.MoveRequest;
import com.touhoulittlemaid.mini.core.MoveTargetKind;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.config.CompanionConfigs;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.mcp.CompanionTools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Bridges MCP tool calls onto live Fabric companions.
 *
 * <p>MCP requests arrive on HTTP virtual threads. Every world-touching step is submitted
 * to the server thread and the caller waits for the result, so the transport never
 * mutates the world directly.
 */
public final class FabricCompanionBackend implements CompanionTools.CompanionBackend {

    private static final long REQUEST_TIMEOUT_SECONDS = 10L;

    private final MinecraftServer server;
    private final com.touhoulittlemaid.mini.mcp.EventBus events;

    public FabricCompanionBackend(MinecraftServer server) {
        this(server, new com.touhoulittlemaid.mini.mcp.EventBus());
    }

    public FabricCompanionBackend(MinecraftServer server,
            com.touhoulittlemaid.mini.mcp.EventBus events) {
        this.server = server;
        this.events = java.util.Objects.requireNonNull(events, "events");
    }

    @Override
    public List<CompanionSnapshot> listCompanions() {
        return runOnServer(ignored -> allCompanions().stream()
                .map(CompanionEntity::snapshot)
                .toList());
    }

    @Override
    public CompanionSnapshot state(UUID companionId) {
        return runOnServer(ignored -> requireCompanion(companionId).snapshot());
    }

    @Override
    public ToolResult move(UUID companionId, MoveRequest request) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            if (companion.isSafetyStopped()) {
                return ToolResult.rejected("unsafe_area",
                        "companion health is too low to accept body actions");
            }
            if (!request.replace() && !companion.getNavigation().isDone()) {
                return ToolResult.rejected("busy",
                        "companion is already moving; pass replace=true to interrupt");
            }

            var level = companion.level();
            Vec3 target;
            if (request.targetKind() == MoveTargetKind.ENTITY) {
                net.minecraft.world.entity.Entity entity = level instanceof ServerLevel serverLevel
                        ? serverLevel.getEntityInAnyDimension(java.util.UUID.fromString(
                                request.entityId()))
                        : null;
                if (entity == null) {
                    return ToolResult.rejected("not_found",
                            "No loaded entity with id " + request.entityId());
                }
                target = entity.position();
            } else {
                target = new Vec3(request.x() + 0.5D, request.y(), request.z() + 0.5D);
            }

            Vec3 start = companion.position();
            Vec3 destination = target;
            if (request.mode() == MoveMode.AWAY) {
                Vec3 away = start.subtract(target);
                if (away.lengthSqr() < 1.0E-4D) {
                    return ToolResult.rejected("no_path",
                            "companion is already at the target position");
                }
                destination = start.add(away.normalize().scale(Math.max(1.0D, request.distance())));
            } else if (request.distance() > 0.05D) {
                Vec3 delta = target.subtract(start);
                if (delta.length() > request.distance()) {
                    destination = target.subtract(
                            delta.normalize().scale(request.distance()));
                }
            }

            companion.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0D);
            events.publish(companionId.toString(), "operation_started",
                    "{\"action\":\"move\",\"mode\":\"" + request.mode().id() + "\"}");
            return ToolResult.ok("moving", "moving " + companionId + " "
                    + request.mode().id() + " " + request.targetKind().id());
        }));
    }

    /**
     * Right-clicks a block as the companion.
     *
     * <p>{@code useItemOn} / {@code useWithoutItem} require a Player, but vanilla already
     * gives non-player mobs a supported path: {@code ServerLevel.destroyBlock(pos, true,
     * entity)} harvests a fully grown crop and drops the yield, exactly as
     * {@code HarvestFarmland} does for villagers. That is the interaction this exposes.
     */
    @Override
    public ToolResult interact(UUID companionId, InteractRequest request) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            if (companion.isSafetyStopped()) {
                return ToolResult.rejected("unsafe_area",
                        "companion health is too low to accept body actions");
            }
            if (!request.replace() && !companion.getNavigation().isDone()) {
                return ToolResult.rejected("busy",
                        "companion is already moving; pass replace=true to interrupt");
            }
            if (request.targetKind() != InteractTargetKind.BLOCK) {
                return ToolResult.rejected("unsupported_interaction",
                        "Only block interactions are exposed; " + request.targetKind().id()
                                + " interactions need a player context");
            }
            if (request.button() != InteractButton.RIGHT) {
                return ToolResult.rejected("unsupported_interaction",
                        "Left-click semantics are not exposed yet");
            }
            return interactWithBlock(companionId, companion, request);
        }));
    }

    private ToolResult interactWithBlock(UUID companionId, CompanionEntity companion,
            InteractRequest request) {
        var level = companion.level();
        var pos = new net.minecraft.core.BlockPos(request.x(), request.y(), request.z());
        if (!level.isLoaded(pos)) {
            return ToolResult.rejected("not_loaded", "block is not loaded");
        }

        var state = level.getBlockState(pos);
        String stateId = blockId(state);

        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
            if (!crop.isMaxAge(state)) {
                return ToolResult.rejected("not_ready", "crop is not fully grown: " + stateId);
            }
            boolean harvested = level.destroyBlock(pos, true, companion);
            if (!harvested) {
                return ToolResult.rejected("internal_error", "harvest failed: " + stateId);
            }
            events.publish(companionId.toString(), "operation_completed",
                    "{\"action\":\"harvest\",\"block\":\"" + stateId + "\"}");
            return ToolResult.ok("harvested", "harvested " + stateId);
        }

        if (state.isAir() && level.getBlockState(pos.below()).getBlock()
                instanceof net.minecraft.world.level.block.FarmlandBlock
                && plantSeed(companion, pos, request.hand())) {
            return ToolResult.ok("planted", "planted a crop at " + pos.toShortString());
        }

        return ToolResult.rejected("unsupported_interaction",
                "No supported block interaction for " + stateId
                        + "; supported: mature crop harvest and replanting on farmland");
    }

    /**
     * Replants exactly like vanilla {@code HarvestFarmland}: take a plantable
     * {@code BlockItem} from the selected hand, set the crop block, and consume one.
     */
    private static boolean plantSeed(CompanionEntity companion, net.minecraft.core.BlockPos pos,
            InteractHand hand) {
        net.minecraft.world.entity.EquipmentSlot slot = hand == InteractHand.OFF
                ? net.minecraft.world.entity.EquipmentSlot.OFFHAND
                : net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        ItemStack stack = companion.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.CropBlock)) {
            return false;
        }
        var place = blockItem.getBlock().defaultBlockState();
        if (!companion.level().setBlockAndUpdate(pos, place)) {
            return false;
        }
        stack.shrink(1);
        companion.setItemSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        return true;
    }

    @Override
    public ToolResult context(UUID companionId, ContextQuery query) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            int radius = CompanionConfigs.current().clampedRadius(query.radius());
            int maxResults = query.maxResults();
            try {
                return switch (query.category()) {
                    case SELF -> ToolResult.ok("context", selfJson(companion, radius));
                    case OWNER -> ToolResult.ok("context", ownerJson(companion));
                    case INVENTORY -> ToolResult.ok("context", inventoryJson(companion));
                    case BLOCKS -> ToolResult.ok("context",
                            blocksJson(companion, query, radius, maxResults));
                    case ENTITIES -> ToolResult.ok("context",
                            entitiesJson(companion, query, radius, maxResults));
                    case CONTAINERS -> ToolResult.ok("context",
                            containersJson(companion, query, radius, maxResults));
                    case HAZARDS -> ToolResult.ok("context",
                            hazardsJson(companion, radius, maxResults));
                };
            } catch (IllegalArgumentException exception) {
                return ToolResult.rejected("invalid_params", exception.getMessage());
            }
        }));
    }

    private static String selfJson(CompanionEntity companion, int radius) {
        StringBuilder json = new StringBuilder("{\"category\":\"self\"");
        stringField(json, "behavior", companion.behavior().id());
        numberField(json, "health", companion.getHealth());
        numberField(json, "max_health", companion.getMaxHealth());
        intField(json, "hunger", companion.hunger().level());
        intField(json, "radius", radius);
        booleanField(json, "home_set", companion.hasHome());
        booleanField(json, "mcp_connected", CompanionRuntimeState.mcpConnected());
        json.append(",\"dimension\":\"")
                .append(escape(companion.level().dimension().identifier().toString()))
                .append('"');
        json.append(",\"position\":");
        entityPosition(json, companion);
        json.append(",\"equipment\":[");
        boolean first = true;
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            ItemStack stack = companion.equipment().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"slot\":\"")
                    .append(escape(CompanionEntity.equipmentSlotAt(slot).getName()))
                    .append("\",\"item\":\"").append(escape(itemId(stack)))
                    .append("\",\"count\":").append(stack.getCount()).append('}');
        }
        return json.append("]}").toString();
    }

    private static String ownerJson(CompanionEntity companion) {
        net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.LivingEntity> reference =
                companion.getOwnerReference();
        if (reference == null) {
            return "{\"category\":\"owner\",\"online\":false}";
        }
        StringBuilder json = new StringBuilder("{\"category\":\"owner\",\"owner_id\":\"")
                .append(reference.getUUID()).append('"');
        net.minecraft.world.entity.LivingEntity owner = companion.getOwner();
        if (owner == null) {
            return json.append(",\"online\":false}").toString();
        }
        json.append(",\"online\":true");
        numberField(json, "health", owner.getHealth());
        numberField(json, "max_health", owner.getMaxHealth());
        json.append(",\"dimension\":\"")
                .append(escape(owner.level().dimension().identifier().toString())).append('"');
        json.append(",\"position\":");
        entityPosition(json, owner);
        numberField(json, "distance", Math.sqrt(companion.distanceToSqr(owner)));
        booleanField(json, "hurt", owner.getHealth() < owner.getMaxHealth());
        return json.append('}').toString();
    }

    private static String inventoryJson(CompanionEntity companion) {
        StringBuilder json = new StringBuilder("{\"category\":\"inventory\",\"backpack\":[");
        boolean first = true;
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"slot\":").append(slot)
                    .append(",\"item\":\"").append(escape(itemId(stack)))
                    .append("\",\"count\":").append(stack.getCount()).append('}');
        }
        json.append("],\"equipment\":[");
        first = true;
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            ItemStack stack = companion.equipment().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"slot\":\"")
                    .append(escape(CompanionEntity.equipmentSlotAt(slot).getName()))
                    .append("\",\"item\":\"").append(escape(itemId(stack)))
                    .append("\",\"count\":").append(stack.getCount()).append('}');
        }
        json.append("],\"free_backpack_slots\":")
                .append(Math.max(0, com.touhoulittlemaid.mini.core.CompanionInventoryLayout
                        .BACKPACK_SLOTS - companion.backpackUsedSlots()))
                .append('}');
        return json.toString();
    }

    private static String blocksJson(CompanionEntity companion, ContextQuery query,
            int radius, int maxResults) {
        java.util.List<TagKey<net.minecraft.world.level.block.Block>> tags =
                query.tags().stream().map(FabricCompanionBackend::blockTag).toList();
        java.util.Set<String> ids = java.util.Set.copyOf(query.blockIds());
        var level = companion.level();
        var origin = companion.blockPosition();
        int vertical = Math.min(radius, 8);
        int scanned = 0;
        int unloaded = 0;
        int matched = 0;
        boolean truncated = false;
        StringBuilder entries = new StringBuilder();

        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -vertical; dy <= vertical; dy++) {
                    var pos = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        unloaded++;
                        continue;
                    }
                    scanned++;
                    var state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = blockId(state);
                    if (!matchesBlock(state, id, ids, tags)) {
                        continue;
                    }
                    if (matched >= maxResults) {
                        truncated = true;
                        break outer;
                    }
                    if (matched > 0) {
                        entries.append(',');
                    }
                    appendBlockEntry(entries, companion, state, id, pos);
                    matched++;
                }
            }
        }

        return new StringBuilder("{\"category\":\"blocks\",\"radius\":").append(radius)
                .append(",\"scanned\":").append(scanned)
                .append(",\"unloaded\":").append(unloaded)
                .append(",\"partial\":").append(unloaded > 0)
                .append(",\"matched\":").append(matched)
                .append(",\"truncated\":").append(truncated)
                .append(",\"blocks\":[").append(entries).append("]}").toString();
    }

    private static String entitiesJson(CompanionEntity companion, ContextQuery query,
            int radius, int maxResults) {
        var level = companion.level();
        var box = companion.getBoundingBox().inflate(radius);
        java.util.List<net.minecraft.world.entity.LivingEntity> entities =
                new java.util.ArrayList<>(level.getEntitiesOfClass(
                        net.minecraft.world.entity.LivingEntity.class, box,
                        entity -> entity != companion));
        if (!query.entityTypes().isEmpty()) {
            entities.removeIf(entity -> !query.entityTypes().contains(entityTypeId(entity)));
        }
        entities.sort(java.util.Comparator.comparingDouble(companion::distanceToSqr));
        boolean truncated = entities.size() > maxResults;
        int limit = Math.min(maxResults, entities.size());

        StringBuilder entries = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                entries.append(',');
            }
            var entity = entities.get(index);
            entries.append("{\"uuid\":\"").append(entity.getUUID()).append('"');
            stringField(entries, "type", entityTypeId(entity));
            if (entity.hasCustomName() && entity.getCustomName() != null) {
                stringField(entries, "name", entity.getCustomName().getString());
            }
            entries.append(",\"position\":");
            entityPosition(entries, entity);
            numberField(entries, "distance", Math.sqrt(companion.distanceToSqr(entity)));
            numberField(entries, "health", entity.getHealth());
            booleanField(entries, "hostile",
                    entity instanceof net.minecraft.world.entity.monster.Monster);
            if (entity instanceof net.minecraft.world.entity.OwnableEntity ownable
                    && ownable.getOwnerReference() != null) {
                stringField(entries, "owner_id", ownable.getOwnerReference().getUUID().toString());
            }
            entries.append('}');
        }
        return new StringBuilder("{\"category\":\"entities\",\"radius\":").append(radius)
                .append(",\"matched\":").append(entities.size())
                .append(",\"truncated\":").append(truncated)
                .append(",\"entities\":[").append(entries).append("]}").toString();
    }

    private static String containersJson(CompanionEntity companion, ContextQuery query,
            int radius, int maxResults) {
        var level = companion.level();
        var origin = companion.blockPosition();
        int vertical = Math.min(radius, 8);
        StringBuilder entries = new StringBuilder();
        int matched = 0;
        int unloaded = 0;
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -vertical; dy <= vertical; dy++) {
                    var pos = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        unloaded++;
                        continue;
                    }
                    var state = level.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) {
                        continue;
                    }
                    if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container)) {
                        continue;
                    }
                    if (matched >= maxResults) {
                        break outer;
                    }
                    if (matched > 0) {
                        entries.append(',');
                    }
                    entries.append("{\"id\":\"").append(escape(blockId(state)))
                            .append("\",\"pos\":");
                    blockPosition(entries, pos);
                    numberField(entries, "distance", Math.sqrt(companion.distanceToSqr(
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
                    entries.append(",\"contents\":\"not_read\"}");
                    matched++;
                }
            }
        }
        return new StringBuilder("{\"category\":\"containers\",\"radius\":").append(radius)
                .append(",\"unloaded\":").append(unloaded)
                .append(",\"partial\":").append(unloaded > 0)
                .append(",\"containers\":[").append(entries).append("]}").toString();
    }

    private static String hazardsJson(CompanionEntity companion, int radius, int maxResults) {
        var level = companion.level();
        var origin = companion.blockPosition();
        int vertical = Math.min(radius, 8);
        StringBuilder blocks = new StringBuilder();
        int blockCount = 0;
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -vertical; dy <= vertical; dy++) {
                    var pos = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    var state = level.getBlockState(pos);
                    String id = blockId(state);
                    if (!HAZARD_BLOCKS.contains(id)) {
                        continue;
                    }
                    if (blockCount >= maxResults) {
                        break outer;
                    }
                    if (blockCount > 0) {
                        blocks.append(',');
                    }
                    blocks.append("{\"id\":\"").append(escape(id)).append("\",\"pos\":");
                    blockPosition(blocks, pos);
                    numberField(blocks, "distance", Math.sqrt(companion.distanceToSqr(
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
                    blocks.append('}');
                    blockCount++;
                }
            }
        }

        var hostiles = level.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                companion.getBoundingBox().inflate(radius));
        hostiles.sort(java.util.Comparator.comparingDouble(companion::distanceToSqr));
        int hostileLimit = Math.min(maxResults, hostiles.size());
        StringBuilder hostileJson = new StringBuilder();
        for (int index = 0; index < hostileLimit; index++) {
            if (index > 0) {
                hostileJson.append(',');
            }
            var entity = hostiles.get(index);
            hostileJson.append("{\"uuid\":\"").append(entity.getUUID())
                    .append("\",\"type\":\"").append(escape(entityTypeId(entity)))
                    .append("\",\"pos\":");
            entityPosition(hostileJson, entity);
            numberField(hostileJson, "distance", Math.sqrt(companion.distanceToSqr(entity)));
            hostileJson.append('}');
        }
        return new StringBuilder("{\"category\":\"hazards\",\"radius\":").append(radius)
                .append(",\"blocks\":[").append(blocks)
                .append("],\"hostiles\":[").append(hostileJson).append("]}").toString();
    }

    private static TagKey<net.minecraft.world.level.block.Block> blockTag(String tag) {
        try {
            return TagKey.create(Registries.BLOCK, Identifier.parse(tag));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid block tag: " + tag);
        }
    }

    private static boolean matchesBlock(
            net.minecraft.world.level.block.state.BlockState state,
            String id, java.util.Set<String> ids,
            java.util.List<TagKey<net.minecraft.world.level.block.Block>> tags) {
        if (!ids.isEmpty() && !ids.contains(id)) {
            return false;
        }
        if (!tags.isEmpty()) {
            var holder = state.getBlock().builtInRegistryHolder();
            boolean matched = false;
            for (TagKey<net.minecraft.world.level.block.Block> tag : tags) {
                if (holder.is(tag)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static void appendBlockEntry(StringBuilder json, CompanionEntity companion,
            net.minecraft.world.level.block.state.BlockState state, String id,
            net.minecraft.core.BlockPos pos) {
        json.append("{\"id\":\"").append(escape(id)).append("\",\"pos\":");
        blockPosition(json, pos);
        numberField(json, "distance", Math.sqrt(companion.distanceToSqr(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
        json.append(",\"properties\":{");
        boolean first = true;
        for (var value : state.getValues().toList()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(value.property().getName()))
                    .append("\":\"").append(escape(value.valueName())).append('"');
        }
        json.append("}}");
    }

    private static String blockId(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock().builtInRegistryHolder().key().identifier().toString();
    }

    private static String entityTypeId(net.minecraft.world.entity.Entity entity) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).toString();
    }

    private static void blockPosition(StringBuilder json, net.minecraft.core.BlockPos pos) {
        json.append("{\"x\":").append(pos.getX())
                .append(",\"y\":").append(pos.getY())
                .append(",\"z\":").append(pos.getZ()).append('}');
    }

    private static void entityPosition(StringBuilder json, net.minecraft.world.entity.Entity entity) {
        json.append("{\"x\":").append(formatNumber(entity.getX()))
                .append(",\"y\":").append(formatNumber(entity.getY()))
                .append(",\"z\":").append(formatNumber(entity.getZ())).append('}');
    }

    private static void stringField(StringBuilder json, String name, String value) {
        json.append(",\"").append(name).append("\":\"")
                .append(escape(value)).append('"');
    }

    private static void intField(StringBuilder json, String name, int value) {
        json.append(",\"").append(name).append("\":").append(value);
    }

    private static void numberField(StringBuilder json, String name, double value) {
        json.append(",\"").append(name).append("\":").append(formatNumber(value));
    }

    private static void booleanField(StringBuilder json, String name, boolean value) {
        json.append(",\"").append(name).append("\":").append(value);
    }

    private static String formatNumber(double value) {
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1.0E9) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x",
                                (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static final java.util.Set<String> HAZARD_BLOCKS = java.util.Set.of(
            "minecraft:lava",
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:magma_block",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:tnt",
            "minecraft:powder_snow",
            "minecraft:cactus",
            "minecraft:sweet_berry_bush",
            "minecraft:wither_rose");

    @Override
    public ToolResult inventory(UUID companionId, InventoryRequest request) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            try {
                return switch (request.action()) {
                    case LIST -> ToolResult.ok("inventory", inventoryListJson(companion));
                    case READ -> readItem(companion, request);
                    case MOVE -> moveItem(companion, request, false);
                    case EQUIP -> moveItem(companion, request, true);
                    case DROP -> dropItem(companion, request);
                };
            } catch (IllegalArgumentException exception) {
                return ToolResult.rejected("invalid_params", exception.getMessage());
            }
        }));
    }

    private record SourceRef(net.minecraft.world.SimpleContainer container, int index,
            ItemStack stack) {
    }

    private static ToolResult readItem(CompanionEntity companion, InventoryRequest request) {
        if (request.hasItem()) {
            SourceRef source = findSource(companion, request);
            if (source == null) {
                return ToolResult.rejected("missing_item",
                        "No matching item in " + request.source().id());
            }
            return ToolResult.ok("read", itemResult(request.source(), source));
        }
        if (request.slot() < 0) {
            return ToolResult.rejected("invalid_params", "read requires an item or a slot");
        }
        var container = containerFor(companion, request.source());
        if (request.slot() >= container.getContainerSize()) {
            return ToolResult.rejected("invalid_params", "slot out of range");
        }
        ItemStack stack = container.getItem(request.slot());
        if (stack.isEmpty()) {
            return ToolResult.ok("empty_slot", "slot " + request.slot() + " is empty");
        }
        return ToolResult.ok("read", itemResult(request.source(),
                new SourceRef(container, request.slot(), stack)));
    }

    private static ToolResult moveItem(CompanionEntity companion, InventoryRequest request,
            boolean equip) {
        InventoryTarget destination = request.destination();
        if (equip && !destination.isEquipment()) {
            return ToolResult.rejected("invalid_params",
                    "equip requires an equipment destination");
        }
        SourceRef source = findSource(companion, request);
        if (source == null) {
            return ToolResult.rejected("missing_item",
                    "No matching item in " + request.source().id());
        }

        int amount = equip ? 1 : Math.min(request.count(), source.stack().getCount());
        ItemStack moved = source.stack().copyWithCount(amount);

        if (destination == InventoryTarget.INVENTORY) {
            if (source.container() == companion.backpack()) {
                return ToolResult.ok("no_change", "item is already in the backpack");
            }
            int space = backpackSpace(companion.backpack(), moved);
            if (space < amount) {
                CompanionRuntimeState.publish(companion.getUUID(), "inventory_full",
                        "{\"action\":\"move\"}");
                return ToolResult.rejected("inventory_full",
                        "backpack has room for only " + space + " of " + amount);
            }
            removeFromSource(source, amount);
            ItemStack leftover = moved.copy();
            insertIntoBackpack(companion.backpack(), leftover);
            companion.backpack().setChanged();
        } else {
            var equipment = companion.equipment();
            int index = equipmentIndex(destination);
            if (index < 0) {
                return ToolResult.rejected("unsupported_target",
                        "Unsupported equipment target: " + destination.id());
            }
            if (!equipment.getItem(index).isEmpty()) {
                return ToolResult.rejected("busy",
                        "destination slot is occupied: " + destination.id());
            }
            if (destination != InventoryTarget.MAIN_HAND
                    && destination != InventoryTarget.OFF_HAND
                    && !companion.isEquippableInSlot(moved, CompanionEntity.equipmentSlotAt(index))) {
                return ToolResult.rejected("invalid_params",
                        "item cannot be equipped in " + destination.id());
            }
            removeFromSource(source, amount);
            equipment.setItem(index, moved);
            companion.applyEquipment();
        }

        CompanionRuntimeState.publish(companion.getUUID(), "operation_completed",
                "{\"action\":\"inventory\",\"change\":\"" + (equip ? "equip" : "move") + "\"}");
        return ToolResult.ok(equip ? "equipped" : "moved",
                new StringBuilder("{\"target\":\"").append(destination.id())
                        .append("\",\"item\":\"").append(escape(itemId(moved)))
                        .append("\",\"count\":").append(amount).append('}')
                        .toString());
    }

    private static ToolResult dropItem(CompanionEntity companion, InventoryRequest request) {
        SourceRef source = findSource(companion, request);
        if (source == null) {
            return ToolResult.rejected("missing_item",
                    "No matching item in " + request.source().id());
        }
        int amount = Math.min(request.count(), source.stack().getCount());
        ItemStack dropped = source.stack().copyWithCount(amount);
        removeFromSource(source, amount);
        if (companion.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            companion.spawnAtLocation(serverLevel, dropped);
        }
        CompanionRuntimeState.publish(companion.getUUID(), "operation_completed",
                "{\"action\":\"inventory\",\"change\":\"drop\"}");
        return ToolResult.ok("dropped", "Dropped " + amount + " "
                + itemId(dropped));
    }

    private static SourceRef findSource(CompanionEntity companion, InventoryRequest request) {
        var container = containerFor(companion, request.source());
        if (request.slot() >= 0) {
            if (request.slot() >= container.getContainerSize()) {
                return null;
            }
            ItemStack stack = container.getItem(request.slot());
            return stack.isEmpty() ? null : new SourceRef(container, request.slot(), stack);
        }
        if (!request.hasItem()) {
            return null;
        }
        for (int index = 0; index < container.getContainerSize(); index++) {
            ItemStack stack = container.getItem(index);
            if (!stack.isEmpty() && itemId(stack).equals(request.item())) {
                return new SourceRef(container, index, stack);
            }
        }
        return null;
    }

    private static void removeFromSource(SourceRef source, int amount) {
        ItemStack stack = source.stack();
        stack.shrink(amount);
        source.container().setItem(source.index(),
                stack.isEmpty() ? ItemStack.EMPTY : stack);
    }

    private static int backpackSpace(net.minecraft.world.SimpleContainer backpack,
            ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), backpack.getMaxStackSize());
        int space = 0;
        for (int index = 0; index < backpack.getContainerSize(); index++) {
            ItemStack existing = backpack.getItem(index);
            if (existing.isEmpty()) {
                space += max;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                space += Math.max(0, max - existing.getCount());
            }
        }
        return space;
    }

    private static void insertIntoBackpack(net.minecraft.world.SimpleContainer backpack,
            ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), backpack.getMaxStackSize());
        for (int index = 0; index < backpack.getContainerSize() && !stack.isEmpty(); index++) {
            ItemStack existing = backpack.getItem(index);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int moved = Math.min(max - existing.getCount(), stack.getCount());
            if (moved > 0) {
                existing.grow(moved);
                stack.shrink(moved);
                backpack.setChanged();
            }
        }
        for (int index = 0; index < backpack.getContainerSize() && !stack.isEmpty(); index++) {
            if (!backpack.getItem(index).isEmpty()) {
                continue;
            }
            int moved = Math.min(max, stack.getCount());
            backpack.setItem(index, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
    }

    private static net.minecraft.world.SimpleContainer containerFor(CompanionEntity companion,
            InventoryTarget target) {
        return target == InventoryTarget.INVENTORY ? companion.backpack() : companion.equipment();
    }

    private static int equipmentIndex(InventoryTarget target) {
        return switch (target) {
            case MAIN_HAND -> 0;
            case OFF_HAND -> 1;
            case FEET -> 2;
            case LEGS -> 3;
            case CHEST -> 4;
            case HEAD -> 5;
            case INVENTORY -> -1;
        };
    }

    private static String inventoryListJson(CompanionEntity companion) {
        StringBuilder json = new StringBuilder("{\"action\":\"list\",\"backpack\":[");
        boolean first = true;
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            appendInventoryEntry(json, slot, stack);
        }
        json.append("],\"equipment\":[");
        first = true;
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            ItemStack stack = companion.equipment().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"slot\":\"")
                    .append(escape(CompanionEntity.equipmentSlotAt(slot).getName()))
                    .append("\",\"item\":\"").append(escape(itemId(stack)))
                    .append("\",\"count\":").append(stack.getCount()).append('}');
        }
        json.append("],\"free_backpack_slots\":")
                .append(Math.max(0, com.touhoulittlemaid.mini.core.CompanionInventoryLayout
                        .BACKPACK_SLOTS - companion.backpackUsedSlots()))
                .append('}');
        return json.toString();
    }

    private static void appendInventoryEntry(StringBuilder json, int slot, ItemStack stack) {
        json.append("{\"slot\":").append(slot)
                .append(",\"item\":\"").append(escape(itemId(stack)))
                .append("\",\"count\":").append(stack.getCount()).append('}');
    }

    private static String itemResult(InventoryTarget target, SourceRef source) {
        return new StringBuilder("{\"target\":\"").append(target.id())
                .append("\",\"slot\":").append(source.index())
                .append(",\"item\":\"").append(escape(itemId(source.stack())))
                .append("\",\"count\":").append(source.stack().getCount()).append('}')
                .toString();
    }

    private static String itemId(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().key().identifier().toString();
    }

    @Override
    public ToolResult setBehavior(UUID companionId, LocalBehavior behavior) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            LocalBehavior previous = companion.behavior();
            companion.setBehavior(behavior);
            if (previous != behavior) {
                events.publish(companionId.toString(), "behavior_changed",
                        "{\"previous\":\"" + previous.id() + "\",\"current\":\""
                                + behavior.id() + "\"}");
            }
            return ToolResult.ok("behavior_set", behavior.id());
        }));
    }

    @Override
    public ToolResult waitEvents(UUID companionId, long afterSequence,
            int timeoutSeconds, int maxEvents) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            if (events.cursorExpired(afterSequence)) {
                return ToolResult.rejected("event_cursor_expired",
                        "Cursor is older than the retained window; call companion_state first");
            }
            List<com.touhoulittlemaid.mini.core.CompanionEvent> pending =
                    events.after(afterSequence, maxEvents);
            StringBuilder json = new StringBuilder("{\"events\":[");
            for (int index = 0; index < pending.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(pending.get(index).toJson());
            }
            json.append("],\"next_seq\":").append(events.lastSequence())
                    .append(",\"timed_out\":").append(pending.isEmpty()).append('}');
            return ToolResult.ok("events", json.toString());
        }));
    }

    @Override
    public ToolResult say(UUID companionId, String text) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            var owner = companion.getOwner();
            if (owner instanceof net.minecraft.server.level.ServerPlayer player) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
            }
            return ToolResult.ok("said", "said to owner");
        }));
    }

    @Override
    public ToolResult stop(UUID companionId) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            companion.getNavigation().stop();
            events.publish(companionId.toString(), "operation_stopped",
                    "{\"reason\":\"agent_requested\"}");
            return ToolResult.ok("stopped", "stopped " + companionId);
        }));
    }

    private CompanionEntity requireCompanion(UUID companionId) {
        return allCompanions().stream()
                .filter(companion -> companion.getUUID().equals(companionId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown companion: " + companionId));
    }

    /** Wraps a companion lookup so a missing companion becomes a structured refusal. */
    private ToolResult withCompanion(UUID companionId,
            java.util.function.Function<CompanionEntity, ToolResult> action) {
        CompanionEntity companion = allCompanions().stream()
                .filter(candidate -> candidate.getUUID().equals(companionId))
                .findFirst()
                .orElse(null);
        if (companion == null) {
            return ToolResult.rejected("unknown_companion", "No such companion: " + companionId);
        }
        return action.apply(companion);
    }

    private List<CompanionEntity> allCompanions() {
        List<CompanionEntity> companions = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion) {
                    companions.add(companion);
                }
            }
        }
        return companions;
    }

    private <T> T runOnServer(Function<MinecraftServer, T> work) {
        if (server.isSameThread()) {
            return work.apply(server);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(work.apply(server));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("server thread did not answer the MCP request in time",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread",
                    exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Convenience for callers that only need a count and must not keep Minecraft types. */
    public Map<String, Object> serverIdentity() {
        return Map.of("minecraftVersion", server.getServerVersion());
    }

    static Optional<UUID> parseCompanionId(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
