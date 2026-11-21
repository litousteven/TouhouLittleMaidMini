package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.ActionModule;
import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ModuleContext;
import com.touhoulittlemaid.mini.core.plan.ModuleExecution;
import com.touhoulittlemaid.mini.core.plan.ModuleParams;
import com.touhoulittlemaid.mini.core.plan.ModuleResult;
import com.touhoulittlemaid.mini.core.plan.ModuleSpec;
import com.touhoulittlemaid.mini.core.plan.ParamSpec;
import com.touhoulittlemaid.mini.core.plan.RiskClass;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tag-driven gathering: pick candidates by tag, then dig them one at a time.
 *
 * <p>No ore or tree id appears here. The caller supplies tags, the module checks the tool
 * and the home protection radius, and each block is broken with the same rules as
 * {@code dig_block}.
 */
final class GatherModule implements ActionModule {

    static final int HOME_PROTECTION_RADIUS = 16;
    private static final TagKey<Block> LOGS =
            TagKey.create(Registries.BLOCK, Identifier.parse("minecraft:logs"));

    private final String id;
    private final String summary;
    private final boolean logOnly;

    private GatherModule(String id, String summary, boolean logOnly) {
        this.id = id;
        this.summary = summary;
        this.logOnly = logOnly;
    }

    static GatherModule mineMaterial() {
        return new GatherModule("mine_material",
                "Mine blocks matching tags or ids, respecting tools and home protection.",
                false);
    }

    static GatherModule chopTree() {
        return new GatherModule("chop_tree", "Chop logs only, leaving saplings and leaves.",
                true);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(id, summary, List.of(
                ParamSpec.intRange("radius", 1, 32, 8, "search radius around home or self"),
                ParamSpec.intRange("count", 1, 64, 8, "how many blocks to break"),
                ParamSpec.stringList("tags", false, "block tags such as #c:stones"),
                ParamSpec.stringList("ids", false, "exact block ids")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.DESTROY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return 80 * params.integer("count", 8);
    }

    @Override
    public int destroyedBlocks(ModuleParams params) {
        return params.integer("count", 8);
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "gather needs a body");
        }
        List<String> tags = params.strings("tags");
        List<String> ids = params.strings("ids");
        if (!logOnly && tags.isEmpty() && ids.isEmpty()) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS,
                    "give tags, ids, or use chop_tree for logs");
        }
        List<BlockPos> candidates = scan(fabric, params);
        if (candidates.isEmpty()) {
            return ModuleResult.failed(FailureCodes.NOT_HARVESTABLE,
                    "no matching block within " + params.integer("radius", 8) + " blocks");
        }
        Tools.Verdict verdict = Tools.checkFor(fabric.companion(),
                fabric.level().getBlockState(candidates.get(0)));
        return verdict.ok()
                ? ModuleResult.ok(FailureCodes.OK, "ready")
                : ModuleResult.failed(verdict.code(), verdict.message());
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        int wanted = params.integer("count", 8);
        Deque<BlockPos> queue = new ArrayDeque<>(scan(fabric, params));
        return new ModuleExecution() {
            private BlockPos current;
            private int progress;
            private int required;
            private int broken;
            private ItemStack tool = ItemStack.EMPTY;

            @Override
            public ModuleResult tick() {
                if (broken >= wanted) {
                    return done();
                }
                if (current == null) {
                    current = next();
                    if (current == null) {
                        return done();
                    }
                    BlockState state = fabric.level().getBlockState(current);
                    tool = companion.getMainHandItem().copy();
                    required = Tools.ticksToBreak(state, tool, fabric.level(), current);
                    progress = 0;
                }
                BlockState state = fabric.level().getBlockState(current);
                if (state.isAir()) {
                    current = null;
                    return ModuleResult.running();
                }
                ItemStack held = companion.getMainHandItem();
                if (tool.isEmpty() != held.isEmpty() || (!tool.isEmpty()
                        && !ItemStack.isSameItemSameComponents(tool, held))) {
                    return ModuleResult.failed(FailureCodes.TOOL_LOST,
                            "the tool changed while mining");
                }
                progress++;
                if (progress < required) {
                    return ModuleResult.running();
                }
                BlockPos target = current;
                List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, fabric.level(),
                        target, fabric.level().getBlockEntity(target), companion, held));
                for (ItemStack drop : drops) {
                    Block.popResource(fabric.level(), target, drop);
                }
                fabric.level().removeBlock(target, false);
                if (held.isDamageableItem()) {
                    held.hurtAndBreak(1, companion, EquipmentSlot.MAINHAND);
                }
                broken++;
                current = null;
                return broken >= wanted ? done() : ModuleResult.running();
            }

            private BlockPos next() {
                BlockPos candidate = queue.pollFirst();
                while (candidate != null) {
                    BlockState state = fabric.level().getBlockState(candidate);
                    if (!state.isAir() && !protectedByHome(companion, candidate)
                            && state.getDestroySpeed(fabric.level(), candidate) >= 0.0F) {
                        return candidate;
                    }
                    candidate = queue.pollFirst();
                }
                return null;
            }

            private ModuleResult done() {
                return ModuleResult.ok("gathered", "broke " + broken + " block(s)",
                        Map.of("count", broken));
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("count");
    }

    private List<BlockPos> scan(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        BlockPos center = fabric.companion().blockPosition();
        List<TagKey<Block>> tags = tags(params);
        List<String> ids = params.strings("ids");
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius),
                center.offset(radius, 8, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            BlockState state = fabric.level().getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(fabric.level(), pos) < 0.0F) {
                continue;
            }
            if (logOnly && !state.is(LOGS)) {
                continue;
            }
            boolean matched = tags.isEmpty() && ids.isEmpty()
                    ? logOnly
                    : tags.stream().anyMatch(tag -> state.is(tag)) || matchesId(state, ids);
            if (matched && !protectedByHome(fabric.companion(), pos)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        int limit = Math.max(1, params.integer("count", 8)) * 4;
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private List<TagKey<Block>> tags(ModuleParams params) {
        List<TagKey<Block>> keys = new ArrayList<>();
        if (logOnly) {
            keys.add(LOGS);
        }
        for (String tag : params.strings("tags")) {
            String value = tag.startsWith("#") ? tag.substring(1) : tag;
            try {
                keys.add(TagKey.create(Registries.BLOCK, Identifier.parse(value)));
            } catch (RuntimeException ignored) {
                // A bad tag simply matches nothing; the gate already validated the shape.
            }
        }
        return keys;
    }

    private static boolean matchesId(BlockState state, List<String> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        Identifier key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock());
        return key != null && ids.contains(key.toString());
    }

    private static boolean protectedByHome(CompanionEntity companion, BlockPos pos) {
        return companion.homeBlockPos()
                .map(home -> home.distSqr(pos)
                        <= (double) HOME_PROTECTION_RADIUS * HOME_PROTECTION_RADIUS)
                .orElse(false);
    }
}
