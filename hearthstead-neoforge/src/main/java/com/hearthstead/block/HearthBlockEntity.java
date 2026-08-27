package com.hearthstead.block;

import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.UUID;

public class HearthBlockEntity extends BlockEntity implements MenuProvider {
    public static final int INVENTORY_SIZE = 24;

    private long assessmentCacheTick = Long.MIN_VALUE;
    @Nullable
    private RecruitmentPolicy.Assessment assessmentCache;

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            assessmentCacheTick = Long.MIN_VALUE;
            assessmentCache = null;
            setChanged();
        }
    };

    @Nullable
    private UUID settlementId;
    private int tickCount;
    private int foundingCooldown;

    public HearthBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEARTH.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  HearthBlockEntity hearth) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        hearth.tickCount++;
        if (hearth.tickCount % 20 != 0) {
            return;
        }

        if (hearth.settlementId == null) {
            if (hearth.foundingCooldown > 0) {
                hearth.foundingCooldown--;
                return;
            }
            Settlement founded = SettlementManager.tryFound(serverLevel, pos);
            if (founded != null) {
                hearth.settlementId = founded.id;
                hearth.setChanged();
            } else {
                hearth.foundingCooldown = 10; // seconds between retries
            }
            return;
        }

        Settlement s = SettlementManager.byId(serverLevel, hearth.settlementId);
        if (s == null) {
            hearth.settlementId = null;
            hearth.setChanged();
            return;
        }
        s.foodCache = hearth.countFoodUnits();
        SettlementManager.tickRecruitment(serverLevel, s);
        // The hearth IS the settlement's heartbeat: no hearth, no settlement,
        // and nothing to raid. Idempotent per night, so this once-a-second
        // call cannot double-roll.
        com.hearthstead.settlement.raid.RaidDirector.tick(serverLevel, s);
    }

    // ------------------------------------------------------------ food ---

    /** Number of edible items in communal storage. */
    public int countFoodUnits() {
        return ReadyFood.count(inventory);
    }

    /**
     * Removes and returns one food item, preferring the most nourishing.
     * Returns EMPTY when the larder is bare.
     */
    public ItemStack extractBestFood() {
        return ReadyFood.extractBest(inventory);
    }

    /** Burns up to {@code count} food items (recruitment cost). */
    public void consumeFood(int count) {
        for (int n = 0; n < count; n++) {
            if (extractBestFood().isEmpty()) {
                return;
            }
        }
    }

    /** Deposits a stack into communal storage; returns whatever did not fit. */
    public ItemStack insertGoods(ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(inventory, stack, false);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    @Nullable
    public UUID getSettlementId() {
        return settlementId;
    }

    /** Direct binding for tests and admin tools; skips the founding flow. */
    public void bindSettlement(@Nullable UUID id) {
        this.settlementId = id;
        assessmentCacheTick = Long.MIN_VALUE;
        assessmentCache = null;
        setChanged();
    }

    public String settlementNameForMenu() {
        if (level instanceof ServerLevel serverLevel && settlementId != null) {
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s != null) {
                return s.name;
            }
        }
        return "";
    }

    public void dropContents() {
        if (level == null) {
            return;
        }
        SimpleContainer drops = new SimpleContainer(inventory.getSlots());
        for (int i = 0; i < inventory.getSlots(); i++) {
            drops.setItem(i, inventory.getStackInSlot(i));
        }
        Containers.dropContents(level, worldPosition, drops);
    }

    // ------------------------------------------------------------ menu ---

    private final ContainerData menuData = new ContainerData() {
        @Override
        public int get(int index) {
            if (!(level instanceof ServerLevel serverLevel)) {
                return 0;
            }
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s == null) {
                return switch (index) {
                    case HearthMenu.DATA_RECRUIT_BLOCKER ->
                        RecruitmentPolicy.Blocker.INVALID_STATE.wireId();
                    case HearthMenu.DATA_RECRUIT_STAGE ->
                        RecruitmentPolicy.Stage.INVALID.wireId();
                    default -> 0;
                };
            }
            RecruitmentPolicy.Assessment assessment = menuAssessment(serverLevel, s);
            return switch (index) {
                case HearthMenu.DATA_POPULATION -> s.population();
                case HearthMenu.DATA_CAPACITY -> s.capacity();
                case HearthMenu.DATA_EMPLOYED -> s.employed();
                case HearthMenu.DATA_FOOD -> Math.min(s.foodCache, 9999);
                case HearthMenu.DATA_MORALE -> s.moraleCache;
                case HearthMenu.DATA_RADIUS -> s.radius;
                case HearthMenu.DATA_ALERT -> s.alertActive(serverLevel.getGameTime()) ? 1 : 0;
                case HearthMenu.DATA_RECRUIT ->
                    s.recruitTarget > 0
                        ? (int) Math.min(100L,
                            (long) s.recruitProgress * 100L / s.recruitTarget)
                        : 0;
                case HearthMenu.DATA_TAVERN -> SettlementManager.hasValidTavern(s) ? 1 : 0;
                case HearthMenu.DATA_RECRUIT_BLOCKER -> assessment.blocker().wireId();
                case HearthMenu.DATA_READY_AFTER_PRICE -> assessment.readyFoodAfterPrice();
                case HearthMenu.DATA_REQUIRED_RESERVE -> assessment.requiredReadyFood();
                case HearthMenu.DATA_MISSING_RESERVE -> assessment.missingReadyFood();
                case HearthMenu.DATA_RECRUIT_STAGE -> assessment.stage().wireId();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return HearthMenu.DATA_COUNT;
        }
    };

    private RecruitmentPolicy.Assessment menuAssessment(ServerLevel serverLevel,
                                                         Settlement settlement) {
        long now = serverLevel.getGameTime();
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.stageFor(settlement);
        if (assessmentCache == null || assessmentCacheTick != now
            || assessmentCache.stage() != stage) {
            assessmentCache = RecruitmentPolicy.assess(serverLevel, settlement, stage);
            assessmentCacheTick = now;
        }
        return assessmentCache;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.hearthstead.hearth");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new HearthMenu(windowId, playerInventory, this, menuData, settlementNameForMenu());
    }

    // ------------------------------------------------------ persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        assessmentCacheTick = Long.MIN_VALUE;
        assessmentCache = null;
    }
}
