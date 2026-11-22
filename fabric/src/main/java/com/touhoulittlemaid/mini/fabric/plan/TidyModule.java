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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Merges duplicate stacks across nearby containers.
 *
 * <p>Deterministic Java, not a model decision: the model may decide to tidy, never how to
 * move things. Every move is one slot per tick, the total item count must be identical
 * afterwards, and keep-tagged or "keep:"-named containers are untouched.
 */
public final class TidyModule implements ActionModule {

    public static final String ID = "tidy_chests";
    private static final TagKey<Block> KEEP =
            TagKey.create(Registries.BLOCK, Identifier.parse("tlmm:keep"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Merge duplicate stacks across nearby containers.", List.of(
                ParamSpec.intRange("radius", 1, 32, 8, "search radius"),
                ParamSpec.intRange("budget", 1, 200, 200, "maximum slot moves")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MODIFY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return Math.min(400, params.integer("budget", 200));
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "tidy needs a body");
        }
        List<Container> containers = containers(fabric, params);
        if (containers.size() < 2) {
            return ModuleResult.failed(FailureCodes.NO_DESTINATION,
                    "need at least two containers to tidy");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        List<Container> containers = containers(fabric, params);
        Map<Integer, Integer> before = totals(containers);
        int budget = params.integer("budget", 200);
        Deque<Move> moves = plan(containers);

        return new ModuleExecution() {
            private int performed;

            @Override
            public ModuleResult tick() {
                if (moves.isEmpty()) {
                    return verify(containers, before, performed);
                }
                if (performed >= budget) {
                    return ModuleResult.failed(FailureCodes.BUDGET_EXCEEDED,
                            "stopped after " + performed + " slot moves with "
                                    + moves.size() + " left");
                }
                Move move = moves.pollFirst();
                ItemStack source = move.from().getItem(move.fromSlot());
                if (source.isEmpty()) {
                    return ModuleResult.running();
                }
                int leftover = Containers.insert(move.to(), source);
                if (leftover == source.getCount()) {
                    return ModuleResult.running();
                }
                if (leftover == 0) {
                    move.from().setItem(move.fromSlot(), ItemStack.EMPTY);
                } else {
                    source.setCount(leftover);
                    move.from().setItem(move.fromSlot(), source);
                }
                move.from().setChanged();
                performed++;
                return ModuleResult.running();
            }
        };
    }

    private static ModuleResult verify(List<Container> containers, Map<Integer, Integer> before,
            int performed) {
        Map<Integer, Integer> after = totals(containers);
        if (!before.equals(after)) {
            return ModuleResult.failed(FailureCodes.COUNT_MISMATCH,
                    "item totals changed after " + performed + " moves");
        }
        int empty = 0;
        for (Container container : containers) {
            if (container.isEmpty()) {
                empty++;
            }
        }
        return ModuleResult.ok("tidied", "made " + performed + " slot moves, " + empty
                + " container(s) now empty", Map.of("moves", performed, "empty", empty));
    }

    /**
     * M6.7 test hook: nanoseconds for one deterministic planning pass over N containers.
     * Planning is the only unbounded part of a tidy step; each tick performs exactly one move.
     */
    public static long planNanos(List<Container> containers) {
        long start = System.nanoTime();
        plan(containers);
        return System.nanoTime() - start;
    }

    /** Chooses a destination per source stack, keeping one container free as a buffer. */
    private static Deque<Move> plan(List<Container> containers) {
        Deque<Move> moves = new ArrayDeque<>();
        int emptyCount = 0;
        for (Container container : containers) {
            if (container.isEmpty()) {
                emptyCount++;
            }
        }
        for (Container source : containers) {
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                ItemStack stack = source.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Container best = null;
                int bestCount = -1;
                for (Container candidate : containers) {
                    if (candidate == source || !hasSpace(candidate, stack)) {
                        continue;
                    }
                    if (candidate.isEmpty() && emptyCount <= 1) {
                        // Keep the last empty container as slack, as the design requires.
                        continue;
                    }
                    int count = countSame(candidate, stack);
                    if (count > bestCount) {
                        bestCount = count;
                        best = candidate;
                    }
                }
                if (best != null && bestCount > 0) {
                    moves.addLast(new Move(source, slot, best));
                }
            }
        }
        return moves;
    }

    private static boolean hasSpace(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static int countSame(Container container, ItemStack stack) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                total += existing.getCount();
            }
        }
        return total;
    }

    private static Map<Integer, Integer> totals(List<Container> containers) {
        Map<Integer, Integer> totals = new HashMap<>();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int key = ItemStack.hashItemAndComponents(stack);
                totals.merge(key, stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    private static List<Container> containers(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        BlockPos center = fabric.companion().blockPosition();
        List<Container> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius),
                center.offset(radius, 4, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            BlockEntity entity = fabric.level().getBlockEntity(pos);
            if (!(entity instanceof Container container)) {
                continue;
            }
            if (entity instanceof AbstractFurnaceBlockEntity) {
                // Furnace slots are process state, not storage.
                continue;
            }
            if (fabric.level().getBlockState(pos).is(KEEP)) {
                continue;
            }
            if (entity instanceof Nameable nameable && nameable.hasCustomName()) {
                String name = nameable.getPlainTextName();
                if (name != null && name.strip().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("keep:")) {
                    continue;
                }
            }
            found.add(container);
        }
        return found;
    }

    private record Move(Container from, int fromSlot, Container to) {
    }

    @Override
    public List<String> outputs() {
        return List.of("moves", "empty");
    }
}
