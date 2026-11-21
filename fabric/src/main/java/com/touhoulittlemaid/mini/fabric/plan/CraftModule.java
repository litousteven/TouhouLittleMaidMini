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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Crafts from the companion's backpack against the real recipe table.
 *
 * <p>Recipes are read, never invented. The module reserves ingredients in a virtual copy
 * first, so a recipe that does not match cannot eat materials. Shaped and shapeless
 * crafting are both supported; cooking and custom recipe types are refused explicitly.
 */
public final class CraftModule implements ActionModule {

    public static final String ID = "craft_item";
    private static final int MAX_CRAFTS = 8;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Craft an item from backpack materials.", List.of(
                ParamSpec.string("item", true, "result item id"),
                ParamSpec.intRange("count", 1, MAX_CRAFTS, 1, "how many times to craft")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MODIFY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return 2 * params.integer("count", 1);
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "craft needs a body");
        }
        String item = params.string("item", "");
        if (findRecipe(fabric, item).isEmpty()) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                    "no craftable recipe for " + item + " from current materials");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        String item = params.string("item", "");
        int wanted = params.integer("count", 1);
        return new ModuleExecution() {
            private int crafted;
            private boolean finished;

            @Override
            public ModuleResult tick() {
                if (finished) {
                    return ModuleResult.ok("crafted", "crafted " + crafted + " x " + item,
                            Map.of("count", crafted));
                }
                if (crafted >= wanted) {
                    finished = true;
                    return ModuleResult.ok("crafted", "crafted " + crafted + " x " + item,
                            Map.of("count", crafted));
                }
                Optional<CraftingRecipe> recipe = findRecipe(fabric, item);
                if (recipe.isEmpty()) {
                    if (crafted == 0) {
                        return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                                "no craftable recipe for " + item);
                    }
                    finished = true;
                    return ModuleResult.ok("crafted", "crafted " + crafted + " x " + item
                            + ", then ran out of materials", Map.of("count", crafted));
                }
                ItemStack result = craftOnce(companion, recipe.get());
                if (result.isEmpty()) {
                    if (crafted == 0) {
                        return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                                "materials disappeared before crafting");
                    }
                    finished = true;
                    return ModuleResult.ok("crafted", "crafted " + crafted + " x " + item,
                            Map.of("count", crafted));
                }
                crafted += result.getCount();
                return ModuleResult.running();
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("count");
    }

    /** Finds a matching recipe and a way to lay its ingredients out in the backpack. */
    private static Optional<CraftingRecipe> findRecipe(FabricModuleContext fabric,
            String item) {
        CompanionEntity companion = fabric.companion();
        for (RecipeHolder<?> holder : fabric.level().getServer().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)
                    || !(recipe instanceof NormalCraftingRecipe)) {
                continue;
            }
            VirtualBag bag = new VirtualBag(companion.backpack());
            CraftingInput input = buildInput(recipe, bag);
            if (input == null || !recipe.matches(input, fabric.level())) {
                continue;
            }
            ItemStack result = recipe.assemble(input);
            if (!ItemMatch.matches(result, false, item)) {
                continue;
            }
            return Optional.of(recipe);
        }
        return Optional.empty();
    }

    private static CraftingInput buildInput(CraftingRecipe recipe, VirtualBag bag) {
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            List<Optional<Ingredient>> pattern = shaped.getIngredients();
            List<ItemStack> grid = new ArrayList<>(width * height);
            for (int index = 0; index < width * height; index++) {
                Optional<Ingredient> maybe = index < pattern.size()
                        ? pattern.get(index) : Optional.empty();
                if (maybe.isEmpty()) {
                    grid.add(ItemStack.EMPTY);
                    continue;
                }
                ItemStack taken = bag.take(maybe.get());
                if (taken.isEmpty()) {
                    return null;
                }
                grid.add(taken);
            }
            return CraftingInput.of(width, height, grid);
        }
        if (recipe instanceof ShapelessRecipe) {
            var info = ((NormalCraftingRecipe) recipe).placementInfo();
            if (info.isImpossibleToPlace()) {
                return null;
            }
            List<ItemStack> grid = new ArrayList<>(info.ingredients().size());
            for (Ingredient ingredient : info.ingredients()) {
                ItemStack taken = bag.take(ingredient);
                if (taken.isEmpty()) {
                    return null;
                }
                grid.add(taken);
            }
            return CraftingInput.of(grid.size(), 1, grid);
        }
        return null;
    }

    private static ItemStack craftOnce(CompanionEntity companion, CraftingRecipe recipe) {
        VirtualBag bag = new VirtualBag(companion.backpack());
        CraftingInput input = buildInput(recipe, bag);
        if (input == null) {
            return ItemStack.EMPTY;
        }
        if (!recipe.matches(input, companion.level())) {
            return ItemStack.EMPTY;
        }
        ItemStack result = recipe.assemble(input);
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        bag.commit();
        NonNullList<ItemStack> remainders = recipe.getRemainingItems(input);
        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty()) {
                ItemStack rest = companion.backpack().addItem(remainder.copy());
                if (!rest.isEmpty()) {
                    net.minecraft.world.level.block.Block.popResource(companion.level(),
                            companion.blockPosition(), rest);
                }
            }
        }
        ItemStack leftover = companion.backpack().addItem(result.copy());
        companion.backpack().setChanged();
        if (!leftover.isEmpty()) {
            net.minecraft.world.level.block.Block.popResource(companion.level(),
                    companion.blockPosition(), leftover);
        }
        return result;
    }

    /** Counts on a copy of the backpack so planning cannot consume anything. */
    private static final class VirtualBag {

        private final SimpleContainer container;
        private final int[] counts;

        VirtualBag(SimpleContainer container) {
            this.container = container;
            this.counts = new int[container.getContainerSize()];
            for (int slot = 0; slot < counts.length; slot++) {
                counts[slot] = container.getItem(slot).getCount();
            }
        }

        ItemStack take(Ingredient ingredient) {
            for (int slot = 0; slot < counts.length; slot++) {
                if (counts[slot] <= 0) {
                    continue;
                }
                ItemStack stack = container.getItem(slot);
                if (!ingredient.test(stack)) {
                    continue;
                }
                counts[slot]--;
                return stack.copyWithCount(1);
            }
            return ItemStack.EMPTY;
        }

        /** Writes the simulated counts back; only called after a successful match. */
        void commit() {
            for (int slot = 0; slot < counts.length; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (counts[slot] <= 0) {
                    container.setItem(slot, ItemStack.EMPTY);
                } else {
                    stack.setCount(counts[slot]);
                    container.setItem(slot, stack);
                }
            }
            container.setChanged();
        }
    }
}
