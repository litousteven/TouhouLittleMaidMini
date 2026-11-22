package com.touhoulittlemaid.mini.fabric.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.semantic.CropShape;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Turns a companion into the compact JSON a model can read.
 *
 * <p>Bounded on purpose: at most {@link #MAX_ITEMS} stacks, one hostile line, one crop count,
 * one container count. No NBT, no other players' chat, no full inventory. The model needs
 * enough to choose a plan, not a copy of the world.
 */
public final class AgentObserver {

    public static final int MAX_ITEMS = 24;

    private static final int SCAN_RADIUS = 12;

    private AgentObserver() {
    }

    public static String observe(CompanionEntity companion, String category) {
        String mode = category == null || category.isBlank() ? "status" : category;
        JsonObject root = new JsonObject();
        root.add("companion", companionJson(companion));
        if (!"inventory".equals(mode)) {
            root.add("world", worldJson(companion.level()));
        }
        if (!"world".equals(mode)) {
            root.add("space", spaceJson(companion));
            root.add("items", itemsJson(companion));
        }
        if (!"inventory".equals(mode)) {
            root.add("nearby", nearbyJson(companion));
        }
        root.addProperty("mode", mode);
        PlanRuntime.snapshot(companion.getUUID()).ifPresent(snapshot -> {
            JsonObject task = new JsonObject();
            task.addProperty("plan_id", snapshot.planId());
            task.addProperty("step", snapshot.step());
            task.addProperty("steps", snapshot.steps());
            task.addProperty("module", snapshot.module());
            task.addProperty("actions", snapshot.actions());
            root.add("task", task);
        });
        return root.toString();
    }

    private static JsonObject companionJson(CompanionEntity companion) {
        JsonObject json = new JsonObject();
        json.addProperty("name", companion.companionName());
        json.addProperty("behavior", companion.behavior().id());
        json.addProperty("schedule", companion.schedule().id());
        json.addProperty("health", round(companion.getHealth()));
        json.addProperty("max_health", round(companion.getMaxHealth()));
        json.addProperty("hunger", companion.hunger().level());
        json.addProperty("safety_stopped", companion.isSafetyStopped());
        json.addProperty("work_radius", companion.workRadius());
        json.addProperty("position", join(companion.getX(), companion.getY(), companion.getZ()));
        Identifier dimension = companion.level().dimension().identifier();
        json.addProperty("dimension", dimension.toString());
        if (companion.getOwner() instanceof ServerPlayer owner) {
            JsonObject ownerJson = new JsonObject();
            ownerJson.addProperty("name", owner.getName().getString());
            ownerJson.addProperty("distance", round(Math.sqrt(owner.distanceToSqr(companion))));
            ownerJson.addProperty("health", round(owner.getHealth()));
            ownerJson.addProperty("online", true);
            json.add("owner", ownerJson);
        }
        return json;
    }

    private static JsonObject worldJson(Level level) {
        JsonObject json = new JsonObject();
        long time = Math.floorMod(level.getOverworldClockTime(), 24_000L);
        json.addProperty("time_ticks", time);
        json.addProperty("day", time < 13_000L);
        json.addProperty("raining", level.isRaining());
        json.addProperty("thundering", level.isThundering());
        return json;
    }

    private static JsonObject spaceJson(CompanionEntity companion) {
        JsonObject json = new JsonObject();
        int used = companion.backpackUsedSlots();
        int slots = companion.backpack().getContainerSize();
        json.addProperty("backpack_used", used);
        json.addProperty("backpack_slots", slots);
        json.addProperty("backpack_free", Math.max(0, slots - used));
        json.addProperty("main_hand", itemId(companion.getMainHandItem()));
        json.addProperty("off_hand", itemId(companion.getOffhandItem()));
        return json;
    }

    private static JsonArray itemsJson(CompanionEntity companion) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            count(counts, companion.backpack().getItem(slot));
        }
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            count(counts, companion.equipment().getItem(slot));
        }
        JsonArray array = new JsonArray();
        counts.entrySet().stream().limit(MAX_ITEMS).forEach(row -> {
            JsonObject item = new JsonObject();
            item.addProperty("id", row.getKey());
            item.addProperty("count", row.getValue());
            array.add(item);
        });
        return array;
    }

    private static void count(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        counts.merge(itemId(stack), stack.getCount(), Integer::sum);
    }

    private static JsonObject nearbyJson(CompanionEntity companion) {
        JsonObject json = new JsonObject();
        Level level = companion.level();
        int radius = Math.min(companion.workRadius(), SCAN_RADIUS);
        BlockPos center = companion.blockPosition();

        int hostiles = 0;
        double nearest = -1;
        for (Monster monster : level.getEntitiesOfClass(Monster.class,
                companion.getBoundingBox().inflate(16))) {
            hostiles++;
            double distance = Math.sqrt(monster.distanceToSqr(companion));
            if (nearest < 0 || distance < nearest) {
                nearest = distance;
            }
        }
        json.addProperty("hostiles", hostiles);
        json.addProperty("nearest_hostile_distance", nearest < 0 ? -1 : round(nearest));

        int mature = 0;
        int containers = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            var state = level.getBlockState(pos);
            if (CropShape.looksLikeCrop(state) && CropShape.isMature(state)
                    && SemanticService.resolve(state).has(SemanticTag.CROP)) {
                mature++;
            }
            if (level.getBlockEntity(pos) instanceof Container) {
                containers++;
            }
        }
        json.addProperty("mature_crops", mature);
        json.addProperty("containers", containers);

        int homeDistance = companion.homeBlockPos()
                .map(home -> (int) Math.sqrt(home.distSqr(center))).orElse(-1);
        json.addProperty("home_distance", homeDistance);
        return json;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    private static String join(double x, double y, double z) {
        return round(x) + "," + round(y) + "," + round(z);
    }

    private static double round(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    /** One line describing what a companion can currently do, used by the chat header. */
    public static String capabilityLine(CompanionEntity companion) {
        boolean hasPick = hasTagged(companion, "minecraft:pickaxes");
        boolean hasHoe = hasTagged(companion, "minecraft:hoes");
        boolean hasAxe = hasTagged(companion, "minecraft:axes");
        StringBuilder out = new StringBuilder();
        out.append("harvest");
        if (hasHoe) {
            out.append(", till");
        }
        if (hasPick) {
            out.append(", mine");
        }
        if (hasAxe) {
            out.append(", chop");
        }
        out.append(", tidy, deposit");
        return out.toString();
    }

    private static boolean hasTagged(CompanionEntity companion, String tag) {
        var key = net.minecraft.tags.TagKey.create(BuiltInRegistries.ITEM.key(),
                Identifier.parse(tag));
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (!stack.isEmpty() && stack.typeHolder().is(key)) {
                return true;
            }
        }
        return false;
    }
}
