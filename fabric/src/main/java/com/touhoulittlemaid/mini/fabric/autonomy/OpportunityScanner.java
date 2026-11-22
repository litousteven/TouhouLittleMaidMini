package com.touhoulittlemaid.mini.fabric.autonomy;

import com.touhoulittlemaid.mini.core.autonomy.Opportunity;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.semantic.CropShape;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Six deterministic checks that decide what is worth doing.
 *
 * <p>Rules, not a model: the cost of asking a model to look around is unbounded and the answer
 * is not reproducible. Each opportunity carries why it exists, so the model only has to pick.
 * Every check is bounded — one pass over a 16-block radius, a handful of containers, no chunk
 * loading.
 */
public final class OpportunityScanner {

    public static final int SCAN_RADIUS = 16;
    public static final int MIN_MATURE_CROPS = 8;
    public static final int MIN_SMELTABLES = 4;
    public static final int MIN_STATION_DROPS = 8;
    public static final int MIN_PLACEMENTS = 32;
    public static final int LOW_BUILDING_STOCK = 16;
    public static final int LOW_FOOD_STOCK = 3;
    public static final int MESSY_CHEST_KINDS = 6;
    public static final int SPREAD_CHESTS = 3;

    private OpportunityScanner() {
    }

    public static List<Opportunity> scan(ServerLevel level, CompanionEntity companion,
            long now, boolean tidyAllowed) {
        return scan(level, companion, now, tidyAllowed, PlanGate.Policy.free());
    }

    /**
     * Only candidates the current tier can actually execute are offered.
     *
     * <p>Offering "go mining" under {@code chores} would be a wasted model round trip and a
     * guaranteed refusal: docs/10 §6.2 puts gathering in {@code free}. The same filtering is
     * what makes "no tool, no opportunity" work without trusting the model to notice.
     */
    public static List<Opportunity> scan(ServerLevel level, CompanionEntity companion,
            long now, boolean tidyAllowed, PlanGate.Policy policy) {
        Scan scan = collect(level, companion);
        List<Opportunity> found = new ArrayList<>();
        harvestFarm(companion, scan).ifPresent(found::add);
        cookFood(level, companion, scan).ifPresent(found::add);
        supplyOwner(companion, scan).ifPresent(found::add);
        tidyStation(level, companion).ifPresent(found::add);
        if (tidyAllowed) {
            tidyChests(scan).ifPresent(found::add);
        }
        gatherBuilding(companion, scan, now).ifPresent(found::add);
        found.removeIf(candidate ->
                !Opportunity.allowedBy(policy == null ? PlanGate.Policy.free() : policy,
                        candidate.id()));
        found.sort(Comparator.comparingInt(Opportunity::priority));
        return found.size() > 4 ? List.copyOf(found.subList(0, 4)) : List.copyOf(found);
    }

    // ---- individual rules -------------------------------------------------------------------

    private static Optional<Opportunity> harvestFarm(CompanionEntity companion, Scan scan) {
        if (scan.matureCrops < MIN_MATURE_CROPS || !scan.hasSeeds) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("harvest_farm",
                "农田里有 " + scan.matureCrops + " 株成熟作物，背包或箱子里有种子",
                2, Map.of("radius", Math.min(companion.workRadius(), 8), "replant", true,
                        "max", Math.min(scan.matureCrops, 16))));
    }

    private static Optional<Opportunity> cookFood(ServerLevel level, CompanionEntity companion,
            Scan scan) {
        if (scan.smeltables < MIN_SMELTABLES || scan.fuel < 1) {
            return Optional.empty();
        }
        String input = scan.smeltableId;
        String fuel = scan.fuelId;
        if (input.isBlank() || fuel.isBlank()) {
            return Optional.empty();
        }
        // The furnace has to be reachable from where she stands; the module needs a position.
        BlockPos furnace = findFurnace(level, companion);
        if (furnace == null) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("cook_food",
                "箱里有 " + scan.smeltables + " 份可熔炼材料与燃料，熔炉在 "
                        + furnace.toShortString(),
                2, Map.of("x", furnace.getX(), "y", furnace.getY(), "z", furnace.getZ(),
                        "input", input, "fuel", fuel, "count", Math.min(scan.smeltables, 8))));
    }

    private static Optional<Opportunity> supplyOwner(CompanionEntity companion, Scan scan) {
        if (!(companion.getOwner() instanceof ServerPlayer owner)) {
            return Optional.empty();
        }
        Map<String, Integer> carried = new LinkedHashMap<>();
        for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
            ItemStack stack = owner.getInventory().getItem(slot);
            if (!stack.isEmpty() && isBuildingMaterial(stack)) {
                carried.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        int total = carried.values().stream().mapToInt(Integer::intValue).sum();
        if (total >= LOW_BUILDING_STOCK) {
            return Optional.empty();
        }
        String best = "";
        int stock = 0;
        for (Map.Entry<String, Integer> row : scan.containerStock.entrySet()) {
            if (isBuildingMaterialId(row.getKey()) && row.getValue() > stock) {
                best = row.getKey();
                stock = row.getValue();
            }
        }
        if (stock < LOW_BUILDING_STOCK * 2) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("supply_owner",
                "主人身上只有 " + total + " 个建筑材料，箱里有 " + stock + " 个 " + best,
                2, Map.of("item", best, "count", Math.min(stock, 32))));
    }

    private static Optional<Opportunity> tidyStation(ServerLevel level, CompanionEntity companion) {
        int drops = 0;
        BlockPos center = companion.blockPosition();
        var box = companion.getBoundingBox().inflate(8.0D, 4.0D, 8.0D);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            BlockPos under = item.blockPosition().below();
            BlockState state = level.getBlockState(under);
            if (state.getBlock() instanceof CraftingTableBlock
                    || state.getBlock() instanceof FurnaceBlock
                    || state.getBlock() instanceof SmokerBlock
                    || state.getBlock() instanceof BlastFurnaceBlock) {
                drops++;
            }
        }
        if (drops < MIN_STATION_DROPS) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("tidy_station",
                "工作台和熔炉旁有 " + drops + " 个掉落物", 3, Map.of("radius", 8)));
    }

    private static Optional<Opportunity> tidyChests(Scan scan) {
        if (scan.containers.size() < 2) {
            return Optional.empty();
        }
        int messy = 0;
        int spread = 0;
        for (Map.Entry<String, Set<Integer>> row : scan.itemSpread.entrySet()) {
            if (row.getValue().size() >= SPREAD_CHESTS) {
                spread++;
            }
        }
        for (Map<String, Integer> chest : scan.chestKinds) {
            if (chest.size() > MESSY_CHEST_KINDS) {
                messy++;
            }
        }
        if (messy == 0 && spread == 0) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("tidy_chests",
                "有 " + messy + " 个杂箱，" + spread + " 种物品散在 3 个以上箱子",
                3, Map.of("radius", SCAN_RADIUS)));
    }

    private static Optional<Opportunity> gatherBuilding(CompanionEntity companion, Scan scan,
            long now) {
        if (!(companion.getOwner() instanceof ServerPlayer owner)) {
            return Optional.empty();
        }
        if (!scan.hasPickaxe && !scan.hasAxe) {
            return Optional.empty();
        }
        Map<String, Integer> placed = PlacementTracker.counts(owner.getUUID(), now);
        String material = "";
        int placedCount = 0;
        for (Map.Entry<String, Integer> row : placed.entrySet()) {
            if (row.getValue() > placedCount && isBuildingMaterialId(row.getKey())) {
                material = row.getKey();
                placedCount = row.getValue();
            }
        }
        if (placedCount < MIN_PLACEMENTS) {
            return Optional.empty();
        }
        int stock = scan.containerStock.getOrDefault(material, 0);
        if (stock >= LOW_BUILDING_STOCK) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity("gather_building",
                "主人在 10 分钟内放了 " + placedCount + " 个 " + material + "，箱里只剩 "
                        + stock + " 个",
                1, Map.of("material", material, "count", 32, "radius", 12)));
    }

    // ---- data collection --------------------------------------------------------------------

    record Scan(List<Container> containers, int matureCrops, boolean hasSeeds, boolean hasPickaxe,
            boolean hasAxe, boolean hasHoe, int smeltables, String smeltableId, int fuel,
            String fuelId, Map<String, Integer> containerStock,
            List<Map<String, Integer>> chestKinds, Map<String, Set<Integer>> itemSpread) {
    }

    private static Scan collect(ServerLevel level, CompanionEntity companion) {
        BlockPos center = companion.blockPosition();
        int radius = Math.min(companion.workRadius(), SCAN_RADIUS);
        List<Container> containers = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius),
                center.offset(radius, 4, radius))) {
            if (!level.isLoaded(pos) || !seen.add(pos.immutable())) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof Container container) {
                containers.add(container);
            }
        }

        Map<String, Integer> stock = new LinkedHashMap<>();
        List<Map<String, Integer>> chestKinds = new ArrayList<>();
        Map<String, Set<Integer>> spread = new LinkedHashMap<>();
        int smeltables = 0;
        String smeltableId = "";
        int fuel = 0;
        String fuelId = "";
        boolean seeds = hasSeed(companion, companion.backpack())
                || hasSeed(companion, companion.equipment());

        for (int index = 0; index < containers.size(); index++) {
            Container container = containers.get(index);
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = itemId(stack);
                kinds.merge(id, stack.getCount(), Integer::sum);
                stock.merge(id, stack.getCount(), Integer::sum);
                spread.computeIfAbsent(id, key -> new HashSet<>()).add(index);
                if (isSmeltable(level, stack)) {
                    if (smeltables == 0) {
                        smeltableId = id;
                    }
                    smeltables += stack.getCount();
                }
                if (stack.get(net.minecraft.core.component.DataComponents.COOKING_FUEL) != null
                        && fuel == 0) {
                    fuel = 1;
                    fuelId = id;
                }
            }
            chestKinds.add(kinds);
        }

        int mature = 0;
        int cropRadius = Math.min(companion.workRadius(), 12);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-cropRadius, -2, -cropRadius),
                center.offset(cropRadius, 2, cropRadius))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (CropShape.looksLikeCrop(state) && CropShape.isMature(state)
                    && SemanticService.resolve(state).has(SemanticTag.CROP)) {
                mature++;
            }
        }

        return new Scan(List.copyOf(containers), mature, seeds,
                hasTagged(companion, ItemTags.PICKAXES), hasTagged(companion, ItemTags.AXES),
                hasTagged(companion, ItemTags.HOES), smeltables, smeltableId, fuel, fuelId,
                stock, List.copyOf(chestKinds), spread);
    }

    /** A stack that would plant a crop: a block item the semantic layer calls a crop. */
    private static boolean hasSeed(CompanionEntity companion, Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (SemanticService.resolve(blockItem.getBlock().defaultBlockState())
                    .has(SemanticTag.CROP)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTagged(CompanionEntity companion, net.minecraft.tags.TagKey<?> tag) {
        return hasTaggedStack(companion, tag, companion.backpack())
                || hasTaggedStack(companion, tag, companion.equipment());
    }

    private static boolean hasTaggedStack(CompanionEntity companion, net.minecraft.tags.TagKey<?> tag,
            Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.typeHolder().is(
                    (net.minecraft.tags.TagKey<net.minecraft.world.item.Item>) tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSmeltable(ServerLevel level, ItemStack stack) {
        var manager = level.getServer() == null ? null : level.getServer().getRecipeManager();
        if (manager == null) {
            return false;
        }
        return manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level)
                .isPresent();
    }

    static boolean isBuildingMaterial(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        var state = blockItem.getBlock().defaultBlockState();
        if (state.is(net.minecraft.tags.BlockTags.CROPS)
                || state.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || state.is(net.minecraft.tags.BlockTags.FLOWERS)
                || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
            return false;
        }
        return stack.getMaxStackSize() > 1;
    }

    static boolean isBuildingMaterialId(String itemId) {
        try {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(itemId));
            return item != null && isBuildingMaterial(new ItemStack(item));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static BlockPos findFurnace(ServerLevel level, CompanionEntity companion) {
        BlockPos center = companion.blockPosition();
        int radius = Math.min(companion.workRadius(), SCAN_RADIUS);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius),
                center.offset(radius, 4, radius))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static String itemId(ItemStack stack) {
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    /** Test hook: how many containers a scan would look at. */
    public static int containerCount(ServerLevel level, CompanionEntity companion) {
        return collect(level, companion).containers().size();
    }

    /** Unused player parameter keeps the signature symmetric with future owner checks. */
    static boolean ownerInMenu(Player player) {
        return player != null && player.containerMenu != null
                && player.containerMenu != player.inventoryMenu;
    }
}
