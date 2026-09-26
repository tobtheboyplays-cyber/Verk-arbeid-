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
    /**
     * The vanilla banner this settlement flies, count 1, or EMPTY for
     * Bannerhold's own founding colours. Held as the real item so that
     * hanging new colours is an exact exchange and breaking the Banner
     * returns it.
     */
    private ItemStack heraldry = ItemStack.EMPTY;
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
            hearth.foundNow(serverLevel);
            return;
        }

        Settlement s = SettlementManager.byId(serverLevel, hearth.settlementId);
        if (s == null) {
            hearth.settlementId = null;
            hearth.setChanged();
            return;
        }
        if (hearth.tickCount % 200 == 0) {
            com.hearthstead.settlement.work.FishMeals.prepareOne(hearth.inventory);
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

    /**
     * Founds the settlement now if this Banner has none and no retry is pending: the same
     * attempt the once-a-second tick makes, also used when a player opens a fresh Banner so
     * the menu gets the real identity instead of NO_SETTLEMENT (QA-UI-03). Returns whether
     * the Banner is bound afterwards.
     */
    public boolean foundNow(ServerLevel level) {
        if (settlementId != null) {
            return true;
        }
        if (foundingCooldown > 0) {
            return false;
        }
        Settlement founded = SettlementManager.tryFound(level, worldPosition);
        if (founded == null) {
            foundingCooldown = 10; // seconds between retries
            return false;
        }
        settlementId = founded.id;
        setChanged();
        // Anyone who opened this Banner before it was founded holds a NO_SETTLEMENT menu
        // that the server rightly refuses: reopen it with the real identity.
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof HearthMenu menu && worldPosition.equals(menu.getHearthPos())
                && HearthMenu.NO_SETTLEMENT.equals(menu.getSettlementId())) {
                player.closeContainer();
                com.hearthstead.block.HearthBlock.openMenu(player, this);
            }
        }
        return true;
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
        SimpleContainer drops = new SimpleContainer(inventory.getSlots() + 1);
        for (int i = 0; i < inventory.getSlots(); i++) {
            drops.setItem(i, inventory.getStackInSlot(i));
        }
        // A banner a player hung here is theirs: it drops with the stores.
        drops.setItem(inventory.getSlots(), heraldry);
        heraldry = ItemStack.EMPTY;
        Containers.dropContents(level, worldPosition, drops);
    }

    // -------------------------------------------------------- heraldry ---

    /** The adopted vanilla banner, or EMPTY while flying the founding colours. */
    public ItemStack getHeraldry() {
        return heraldry;
    }

    /**
     * Hangs {@code banner} (exactly one vanilla banner, already removed from
     * its owner) and returns what flew before: the previous adopted banner, or
     * EMPTY when the founding colours are replaced. The caller owns both
     * sides of the exchange; this method never creates or discards an item.
     */
    public ItemStack exchangeHeraldry(ItemStack banner) {
        if (!SettlementHeraldry.isBanner(banner) || banner.getCount() != 1) {
            throw new IllegalArgumentException("heraldry must be exactly one banner");
        }
        ItemStack previous = heraldry;
        heraldry = banner;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
        return previous;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Clients only need the colours; stores travel through the menu.
        CompoundTag tag = new CompoundTag();
        saveHeraldry(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
        getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadHeraldry(tag, registries);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        loadHeraldry(packet.getTag(), registries);
    }

    private void saveHeraldry(CompoundTag tag, HolderLookup.Provider registries) {
        if (!heraldry.isEmpty()) {
            tag.put("Heraldry", heraldry.save(registries));
        }
    }

    private void loadHeraldry(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack loaded = tag.contains("Heraldry", net.minecraft.nbt.Tag.TAG_COMPOUND)
            ? ItemStack.parseOptional(registries, tag.getCompound("Heraldry"))
            : ItemStack.EMPTY;
        heraldry = SettlementHeraldry.isBanner(loaded) ? loaded.copyWithCount(1) : ItemStack.EMPTY;
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
                    case HearthMenu.DATA_JOURNEY_PHASE -> -1;
                    case HearthMenu.DATA_JOURNEY_V3_MODE,
                         HearthMenu.DATA_JOURNEY_V3_CURRENT,
                         HearthMenu.DATA_JOURNEY_V3_CHAPTER -> -1;
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
                    s.recruitment != null && s.recruitment.lockedTarget() > 0
                        ? (int) Math.min(100L,
                            (long) s.recruitment.progress() * 100L
                                / s.recruitment.lockedTarget())
                        : 0;
                case HearthMenu.DATA_TAVERN -> SettlementManager.hasValidTavern(s) ? 1 : 0;
                case HearthMenu.DATA_RECRUIT_BLOCKER -> assessment.blocker().wireId();
                case HearthMenu.DATA_READY_AFTER_PRICE -> assessment.readyFoodAfterPrice();
                case HearthMenu.DATA_REQUIRED_RESERVE -> assessment.requiredReadyFood();
                case HearthMenu.DATA_MISSING_RESERVE -> assessment.missingReadyFood();
                case HearthMenu.DATA_RECRUIT_STAGE -> assessment.stage().wireId();
                case HearthMenu.DATA_JOURNEY_PHASE -> s.foundingJourney.phase().wireId();
                case HearthMenu.DATA_JOURNEY_REVISION -> s.foundingJourney.revision();
                case HearthMenu.DATA_JOURNEY_CAN_SKIP -> s.foundingJourney.active() ? 1 : 0;
                case HearthMenu.DATA_JOURNEY_V3_MODE -> s.journeyState.mode().wireId();
                case HearthMenu.DATA_JOURNEY_V3_REVISION -> s.journeyState.revision();
                case HearthMenu.DATA_JOURNEY_V3_CAN_SKIP ->
                    s.journeyState.mode()
                        == com.hearthstead.settlement.journey.JourneyPresentationMode.ACTIVE
                        ? 1 : 0;
                case HearthMenu.DATA_JOURNEY_V3_CURRENT ->
                    s.journeyState.currentStep().map(
                        com.hearthstead.settlement.journey.JourneyStep::ordinal)
                        .orElse(-1);
                case HearthMenu.DATA_JOURNEY_V3_COMPLETED ->
                    s.journeyState.completedCount();
                case HearthMenu.DATA_JOURNEY_V3_OUTCOME ->
                    s.journeyState.outcome().wireId();
                case HearthMenu.DATA_JOURNEY_V3_CHAPTER ->
                    s.journeyState.currentChapter().map(
                        com.hearthstead.settlement.journey.JourneyDefinition.V2
                            .chapters()::indexOf).orElse(-1);
                case HearthMenu.DATA_RECRUIT_REVISION ->
                    s.recruitment == null ? -1 : s.recruitment.revision();
                case HearthMenu.DATA_RECRUIT_TRANSACTION_STATUS ->
                    s.recruitment == null
                        ? com.hearthstead.settlement.RecruitmentTransaction.Status.UNKNOWN.wireId()
                        : s.recruitment.status().wireId();
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
        saveHeraldry(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        // Saves from before the Banner have no Heraldry key: they fly the
        // founding colours, with stores and settlement link untouched.
        loadHeraldry(tag, registries);
        assessmentCacheTick = Long.MIN_VALUE;
        assessmentCache = null;
    }
}
