package com.touhoulittlemaid.mini.fabric.entity;

import com.touhoulittlemaid.mini.core.CompanionHunger;
import com.touhoulittlemaid.mini.core.CompanionInventoryLayout;
import com.touhoulittlemaid.mini.core.CompanionSchedule;
import com.touhoulittlemaid.mini.core.CompanionServerConfig;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.config.CompanionConfigs;
import com.touhoulittlemaid.mini.fabric.entity.goal.BehaviorFollowOwnerGoal;
import com.touhoulittlemaid.mini.fabric.entity.goal.FarmGoal;
import com.touhoulittlemaid.mini.fabric.entity.goal.ReturnHomeGoal;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenus;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

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
    private static final EntityDataAccessor<Integer> DATA_HUNGER =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RADIUS =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_BACKPACK_USED =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_MCP_CONNECTED =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_SCHEDULE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);

    private static final String DEFAULT_NAME = "Companion";
    private static final String BACKPACK_KEY_PREFIX = "companion_backpack_";
    private static final String EQUIPMENT_KEY_PREFIX = "companion_equipment_";
    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 128;

    /** Menu slot order for the six equipment slots. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD
    };

    private final SimpleContainer backpack = new SimpleContainer(CompanionInventoryLayout.BACKPACK_SLOTS);
    private final SimpleContainer equipment = new EquipmentContainer();
    private final CompanionHunger hunger = CompanionHunger.full();
    private Optional<GlobalPos> home = Optional.empty();
    private int radius = DEFAULT_RADIUS;
    private boolean equipmentDirty;
    private boolean safetyStopped;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        setTame(true, true);
        setCanPickUpLoot(false);
        setCompanionName(DEFAULT_NAME);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BEHAVIOR, LocalBehavior.STAY.id());
        builder.define(DATA_HOME_SET, false);
        builder.define(DATA_DISPLAY_NAME, DEFAULT_NAME);
        builder.define(DATA_HUNGER, CompanionHunger.MAX_LEVEL);
        builder.define(DATA_RADIUS, DEFAULT_RADIUS);
        builder.define(DATA_BACKPACK_USED, 0);
        builder.define(DATA_MCP_CONNECTED, false);
        builder.define(DATA_SCHEDULE, CompanionSchedule.ALL.id());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new BehaviorFollowOwnerGoal(this, 1.0D, 6.0F, 2.0F));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2D, true));
        goalSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, 10,
                false, false, this::isValidGuardTarget));
        goalSelector.addGoal(3, new HurtByTargetGoal(this));
        goalSelector.addGoal(4, new ReturnHomeGoal(this));
        goalSelector.addGoal(5, new FarmGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    public LocalBehavior behavior() {
        return LocalBehavior.fromId(entityData.get(DATA_BEHAVIOR));
    }

    public CompanionSchedule schedule() {
        return CompanionSchedule.fromId(entityData.get(DATA_SCHEDULE));
    }

    public void setSchedule(CompanionSchedule schedule) {
        entityData.set(DATA_SCHEDULE, schedule.id());
        if (schedule != CompanionSchedule.ALL && !isWorkTime()) {
            getNavigation().stop();
            setTarget(null);
        }
    }

    /**
     * Whether the local behaviour may act right now.
     *
     * <p>The schedule gates only the body's own initiative. An explicit agent request
     * goes through the MCP backend and is never filtered here.
     */
    public boolean isWorkTime() {
        return schedule().isActive(level().isBrightOutside());
    }

    public void setBehavior(LocalBehavior behavior) {
        entityData.set(DATA_BEHAVIOR, behavior.id());
        if (behavior == LocalBehavior.FOLLOW) {
            setOrderedToSit(false);
        } else if (behavior == LocalBehavior.STAY) {
            setOrderedToSit(true);
            setTarget(null);
            getNavigation().stop();
        } else {
            setOrderedToSit(false);
            if (behavior == LocalBehavior.GUARD) {
                setTarget(null);
            }
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

    public Optional<BlockPos> homeBlockPos() {
        return home.map(GlobalPos::pos);
    }

    public Optional<ResourceKey<Level>> homeDimension() {
        return home.map(GlobalPos::dimension);
    }

    public void setHome(BlockPos position) {
        this.home = Optional.of(GlobalPos.of(level().dimension(), position.immutable()));
        entityData.set(DATA_HOME_SET, true);
    }

    public void clearHome() {
        this.home = Optional.empty();
        entityData.set(DATA_HOME_SET, false);
    }

    public int workRadius() {
        return radius;
    }

    public void setWorkRadius(int value) {
        radius = Math.max(1, Math.min(value, MAX_RADIUS));
        entityData.set(DATA_RADIUS, radius);
    }

    public int syncedWorkRadius() {
        return entityData.get(DATA_RADIUS);
    }

    public CompanionHunger hunger() {
        return hunger;
    }

    public boolean isSafetyStopped() {
        return CompanionConfigs.current().isSafetyStopped(getHealth(), getMaxHealth());
    }

    public boolean isMcpConnected() {
        return entityData.get(DATA_MCP_CONNECTED);
    }

    public SimpleContainer backpack() {
        return backpack;
    }

    public SimpleContainer equipment() {
        return equipment;
    }

    public static EquipmentSlot equipmentSlotAt(int index) {
        if (index < 0 || index >= EQUIPMENT_SLOTS.length) {
            throw new IllegalArgumentException("equipment index out of range: " + index);
        }
        return EQUIPMENT_SLOTS[index];
    }

    public int backpackUsedSlots() {
        int used = 0;
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            if (!backpack.getItem(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public int equipmentUsedSlots() {
        int used = 0;
        for (int slot = 0; slot < equipment.getContainerSize(); slot++) {
            if (!equipment.getItem(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public boolean isOwner(Player player) {
        net.minecraft.world.entity.EntityReference<LivingEntity> reference = getOwnerReference();
        return reference != null && reference.matches(player);
    }

    public CompanionSnapshot snapshot() {
        net.minecraft.world.entity.EntityReference<LivingEntity> reference = getOwnerReference();
        return new CompanionSnapshot(
                getUUID(),
                reference == null ? Optional.empty() : Optional.of(reference.getUUID()),
                companionName(),
                behavior(),
                schedule(),
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
        return stack.has(DataComponents.FOOD);
    }

    @Override
    public CompanionEntity getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (isOwnedBy(target)) {
            return false;
        }
        if (target instanceof Player) {
            return false;
        }
        if (target instanceof AbstractVillager) {
            return false;
        }
        if (target instanceof OwnableEntity ownable && ownable.getOwner() != null) {
            return false;
        }
        return super.canAttack(target);
    }

    private boolean isValidGuardTarget(LivingEntity target, ServerLevel level) {
        if (behavior() != LocalBehavior.GUARD || isSafetyStopped() || !isWorkTime()) {
            return false;
        }
        double chase = radius + 8.0D;
        var owner = getOwner();
        if (owner != null && owner.level().dimension().equals(level.dimension())
                && owner.distanceToSqr(target) <= chase * chase) {
            return true;
        }
        BlockPos homePos = homeBlockPos().orElse(null);
        if (homePos == null || !level.dimension().equals(homeDimension().orElse(null))) {
            return true;
        }
        return distanceToSqr(Vec3.atCenterOf(homePos)) <= chase * chase;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (equipmentDirty) {
            applyEquipment();
        }
        tickHunger(level);
        updateSafety();
        refreshSyncedSummaries();
    }

    private void tickHunger(ServerLevel level) {
        CompanionServerConfig config = CompanionConfigs.current();
        int interval = Math.max(1, config.hungerDecaySeconds() * 20);
        if (hunger.tick(interval)) {
            entityData.set(DATA_HUNGER, hunger.level());
        }
        if (hunger.needsFood() && tickCount % 40 == 0) {
            tryEat();
        }
        if (hunger.isStarving() && tickCount % 100 == 0 && getHealth() > 1.0F) {
            hurtServer(level, level.damageSources().starve(), 1.0F);
        }
    }

    private void tryEat() {
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            if (consumeFood(backpack, slot)) {
                return;
            }
        }
        for (int slot = 0; slot < 2; slot++) {
            if (consumeFood(equipment, slot)) {
                return;
            }
        }
    }

    private boolean consumeFood(SimpleContainer container, int slot) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || food.nutrition() <= 0) {
            return false;
        }
        hunger.eat(food.nutrition());
        entityData.set(DATA_HUNGER, hunger.level());
        stack.shrink(1);
        container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        heal(Math.min(2.0F, food.nutrition() * 0.5F));
        ate();
        return true;
    }

    private void updateSafety() {
        boolean stopped = isSafetyStopped();
        if (stopped && !safetyStopped) {
            getNavigation().stop();
            setTarget(null);
            CompanionRuntimeState.publish(getUUID(), "companion_hurt",
                    "{\"reason\":\"low_health\",\"health\":" + getHealth() + '}');
        }
        safetyStopped = stopped;
    }

    private void refreshSyncedSummaries() {
        int used = backpackUsedSlots();
        if (entityData.get(DATA_BACKPACK_USED) != used) {
            entityData.set(DATA_BACKPACK_USED, used);
        }
        if (entityData.get(DATA_RADIUS) != radius) {
            entityData.set(DATA_RADIUS, radius);
        }
        if (entityData.get(DATA_HUNGER) != hunger.level()) {
            entityData.set(DATA_HUNGER, hunger.level());
        }
        boolean connected = CompanionRuntimeState.mcpConnected();
        if (entityData.get(DATA_MCP_CONNECTED) != connected) {
            entityData.set(DATA_MCP_CONNECTED, connected);
        }
    }

    /** Copies the six menu slots onto the entity's vanilla equipment slots. */
    public void applyEquipment() {
        equipmentDirty = false;
        for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
            setItemSlot(EQUIPMENT_SLOTS[index], equipment.getItem(index));
        }
    }

    /** Re-reads the vanilla equipment after loading so armour bonuses apply immediately. */
    public void reloadEquipmentFromEntity() {
        for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
            ItemStack stack = getItemBySlot(EQUIPMENT_SLOTS[index]);
            equipment.setItem(index, stack.copy());
        }
        equipmentDirty = false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !isOwner(player)) {
            return super.mobInteract(player, hand);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            CompanionMenus.open(serverPlayer, this);
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            CompanionRuntimeState.publish(getUUID(), "companion_hurt",
                    "{\"damage\":" + amount + ",\"health\":" + getHealth() + '}');
        }
        return hurt;
    }

    public boolean canBeDeletedInventoryLossless() {
        return backpackUsedSlots() == 0 && equipmentUsedSlots() == 0;
    }

    public void moveInventoryTo(Player target) {
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            ItemStack stack = backpack.getItem(slot);
            if (!stack.isEmpty()) {
                target.getInventory().placeItemBackInInventory(stack.copy(), Prediction.SERVER_ONLY);
                backpack.setItem(slot, ItemStack.EMPTY);
            }
        }
        for (int slot = 0; slot < equipment.getContainerSize(); slot++) {
            ItemStack stack = equipment.getItem(slot);
            if (!stack.isEmpty()) {
                target.getInventory().placeItemBackInInventory(stack.copy(), Prediction.SERVER_ONLY);
                equipment.setItem(slot, ItemStack.EMPTY);
            }
        }
        backpack.setChanged();
        equipment.setChanged();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("companion_behavior", behavior().id());
        output.putString("companion_name", companionName());
        output.putInt("companion_hunger", hunger.level());
        output.putInt("companion_radius", radius);
        output.putString("companion_schedule", schedule().id());
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
        for (int slot = 0; slot < CompanionInventoryLayout.EQUIPMENT_SLOTS; slot++) {
            ItemStack stack = equipment.getItem(slot);
            if (!stack.isEmpty()) {
                output.store(EQUIPMENT_KEY_PREFIX + slot, ItemStack.CODEC, stack);
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

        hunger.setLevel(input.getIntOr("companion_hunger", CompanionHunger.MAX_LEVEL));
        entityData.set(DATA_HUNGER, hunger.level());
        setWorkRadius(input.getIntOr("companion_radius", DEFAULT_RADIUS));
        input.getString("companion_schedule").ifPresent(id -> {
            try {
                setSchedule(CompanionSchedule.fromId(id));
            } catch (IllegalArgumentException ignored) {
                setSchedule(CompanionSchedule.ALL);
            }
        });

        Optional<BlockPos> storedHome = input.read("companion_home_pos", BlockPos.CODEC);
        Optional<String> storedDimension = input.getString("companion_home_dimension");
        if (storedHome.isPresent() && storedDimension.isPresent()) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    Identifier.parse(storedDimension.get()));
            this.home = Optional.of(GlobalPos.of(dimension, storedHome.get()));
            entityData.set(DATA_HOME_SET, true);
        }

        backpack.clearContent();
        for (int slot = 0; slot < CompanionInventoryLayout.BACKPACK_SLOTS; slot++) {
            final int targetSlot = slot;
            input.read(BACKPACK_KEY_PREFIX + slot, ItemStack.CODEC)
                    .ifPresent(stack -> backpack.setItem(targetSlot, stack));
        }

        equipment.clearContent();
        for (int slot = 0; slot < CompanionInventoryLayout.EQUIPMENT_SLOTS; slot++) {
            final int targetSlot = slot;
            input.read(EQUIPMENT_KEY_PREFIX + slot, ItemStack.CODEC)
                    .ifPresent(stack -> equipment.setItem(targetSlot, stack));
        }
        applyEquipment();
    }

    private final class EquipmentContainer extends SimpleContainer {

        EquipmentContainer() {
            super(CompanionInventoryLayout.EQUIPMENT_SLOTS);
        }

        @Override
        public void setChanged() {
            equipmentDirty = true;
        }
    }
}
