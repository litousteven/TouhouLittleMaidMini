package com.touhoulittlemaid.mini.fabric.entity;

import com.touhoulittlemaid.mini.core.CompanionInventoryLayout;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The base companion body.
 *
 * <p>This entity owns only per-tick, safety and persistence responsibilities. Task
 * selection belongs to the external agent; the local behaviours here are the fallback
 * that keeps the body safe when no agent is driving it.
 */
public final class CompanionEntity extends TamableAnimal {

    private static final EntityDataAccessor<String> DATA_BEHAVIOR =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_HOME_SET =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_DISPLAY_NAME =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);

    private static final String DEFAULT_NAME = "Companion";
    private static final String BACKPACK_KEY_PREFIX = "companion_backpack_";

    private final SimpleContainer backpack = new SimpleContainer(CompanionInventoryLayout.BACKPACK_SLOTS);
    private Optional<GlobalPos> home = Optional.empty();

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        setTame(true, true);
        setCanPickUpLoot(false);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BEHAVIOR, LocalBehavior.STAY.id());
        builder.define(DATA_HOME_SET, false);
        builder.define(DATA_DISPLAY_NAME, DEFAULT_NAME);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new FollowOwnerGoal(this, 1.0D, 6.0F, 2.0F));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    public LocalBehavior behavior() {
        return LocalBehavior.fromId(entityData.get(DATA_BEHAVIOR));
    }

    public void setBehavior(LocalBehavior behavior) {
        entityData.set(DATA_BEHAVIOR, behavior.id());
        if (behavior == LocalBehavior.FOLLOW) {
            setOrderedToSit(false);
        } else if (behavior == LocalBehavior.STAY) {
            setOrderedToSit(true);
        }
    }

    public String companionName() {
        return entityData.get(DATA_DISPLAY_NAME);
    }

    public void setCompanionName(String name) {
        String trimmed = name == null ? "" : name.strip();
        entityData.set(DATA_DISPLAY_NAME, trimmed.isEmpty() ? DEFAULT_NAME : trimmed);
        setCustomName(Component.literal(companionName()));
    }

    public boolean hasHome() {
        return entityData.get(DATA_HOME_SET) && home.isPresent();
    }

    public Optional<GlobalPos> homePosition() {
        return home;
    }

    public void setHome(BlockPos position) {
        this.home = Optional.of(GlobalPos.of(level().dimension(), position.immutable()));
        entityData.set(DATA_HOME_SET, true);
    }

    public void clearHome() {
        this.home = Optional.empty();
        entityData.set(DATA_HOME_SET, false);
    }

    public SimpleContainer backpack() {
        return backpack;
    }

    public CompanionSnapshot snapshot() {
        return new CompanionSnapshot(
                getUUID(),
                getOwner() instanceof Player owner ? Optional.of(owner.getUUID()) : Optional.empty(),
                companionName(),
                behavior(),
                hasHome());
    }

    @Override
    public void setCustomName(Component name) {
        super.setCustomName(name);
        if (name != null) {
            entityData.set(DATA_DISPLAY_NAME, name.getString());
        }
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return stack.is(ItemTags.COW_FOOD);
    }

    @Override
    public CompanionEntity getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean canAttack(net.minecraft.world.entity.LivingEntity target) {
        if (isOwnedBy(target)) {
            return false;
        }
        return super.canAttack(target);
    }

    public boolean canBeDeletedInventoryLossless() {
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            if (!backpack.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void moveInventoryTo(Player target) {
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            ItemStack stack = backpack.getItem(slot);
            if (!stack.isEmpty()) {
                target.getInventory().placeItemBackInInventory(stack.copy(), Prediction.SERVER_ONLY);
                backpack.setItem(slot, ItemStack.EMPTY);
            }
        }
        backpack.setChanged();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("companion_behavior", behavior().id());
        output.putString("companion_name", companionName());
        home.ifPresent(position -> {
            output.putString("companion_home_dimension", position.dimension().identifier().toString());
            output.store("companion_home_pos", BlockPos.CODEC, position.pos());
        });
        for (int slot = 0; slot < CompanionInventoryLayout.BACKPACK_SLOTS; slot++) {
            ItemStack stack = backpack.getItem(slot);
            if (!stack.isEmpty()) {
                output.store(BACKPACK_KEY_PREFIX + slot, ItemStack.CODEC, stack);
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String storedName = input.getString("companion_name").orElse(DEFAULT_NAME);
        setCompanionName(storedName);

        input.getString("companion_behavior").ifPresent(id -> {
            try {
                setBehavior(LocalBehavior.fromId(id));
            } catch (IllegalArgumentException ignored) {
                setBehavior(LocalBehavior.STAY);
            }
        });

        Optional<BlockPos> storedHome = input.read("companion_home_pos", BlockPos.CODEC);
        Optional<String> storedDimension = input.getString("companion_home_dimension");
        if (storedHome.isPresent() && storedDimension.isPresent()) {
            net.minecraft.resources.ResourceKey<Level> dimension =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.Identifier.parse(storedDimension.get()));
            this.home = Optional.of(GlobalPos.of(dimension, storedHome.get()));
            entityData.set(DATA_HOME_SET, true);
        }

        backpack.clearContent();
        for (int slot = 0; slot < CompanionInventoryLayout.BACKPACK_SLOTS; slot++) {
            final int targetSlot = slot;
            input.read(BACKPACK_KEY_PREFIX + slot, ItemStack.CODEC)
                    .ifPresent(stack -> backpack.setItem(targetSlot, stack));
        }
    }
}
