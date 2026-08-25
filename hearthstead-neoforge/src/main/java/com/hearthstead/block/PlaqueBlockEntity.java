package com.hearthstead.block;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The plaque's brain: it surveys the room it hangs in, decides whether that
 * room satisfies its building type, and keeps a link to the resulting
 * building.
 *
 * <p>It deliberately stores almost nothing. The building id, the type and the
 * last survey are here; residents, workers and building geometry live in the
 * settlement. A plaque that cached its own resident list would be a second
 * source of truth, and the two would drift the first time a settler died
 * while the chunk was unloaded.
 *
 * <p>D-006: a hung plaque starts {@link PlaqueState#EMPTY} and does nothing
 * — no UI, no survey — until a Build Plan item is fitted into it. The fitted
 * plan is itself a real, conserved item: it is stored here, saved and loaded
 * like the rest of this entity's state, and comes back out exactly as it
 * went in (INV-3) when the plan is extracted or the plaque is broken.
 */
public class PlaqueBlockEntity extends BlockEntity {

    /** How often a hung plaque re-checks its room, in ticks (10 s). */
    private static final int SURVEY_INTERVAL = 200;

    private BuildingType type = BuildingType.HOUSE;
    private PlaqueState state = PlaqueState.EMPTY;
    /** The Build Plan currently fitted; {@link ItemStack#EMPTY} while {@link PlaqueState#EMPTY}. */
    private ItemStack insertedPlan = ItemStack.EMPTY;
    @Nullable
    private UUID buildingId;
    /**
     * Bumped on every server-side change. A screen sends the revision it was
     * drawn from, so a click made against a stale view is refused instead of
     * quietly acting on outdated information.
     */
    private int revision;
    private long nextSurveyTick;
    private List<Requirement.Status> lastSurvey = List.of();

    /** How many times a real room scan has been attempted — test telemetry for W3. */
    private int scanAttempts;
    /** How many times this plaque's screen has been opened — test telemetry for W4. */
    private int screenOpens;

    public PlaqueBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLAQUE.get(), pos, state);
    }

    // ------------------------------------------------------------- state ---

    public BuildingType type() {
        return type;
    }

    public PlaqueState state() {
        return state;
    }

    public int revision() {
        return revision;
    }

    public List<Requirement.Status> lastSurvey() {
        return lastSurvey;
    }

    @Nullable
    public UUID buildingId() {
        return buildingId;
    }

    public ItemStack insertedPlan() {
        return insertedPlan.copy();
    }

    public int scanAttempts() {
        return scanAttempts;
    }

    public int screenOpenCount() {
        return screenOpens;
    }

    /**
     * Announce this plaque to the level's registry so nearby block changes can
     * find it. Done on load rather than on placement so plaques that come back
     * with a chunk are known too.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).buildingManager
                .registerPlaque(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).buildingManager
                .forgetPlaque(worldPosition);
        }
        super.setRemoved();
    }

    // --------------------------------------------------------------- plan ---

    /**
     * Fits a Build Plan. Only valid while {@link PlaqueState#EMPTY} — a
     * plaque that already has one must be extracted first. Reads the type off
     * the plan, stores a single copy of it, and starts the surveyor: this is
     * the moment D-006 says the plaque begins working.
     *
     * @return whether the plan was accepted
     */
    public boolean insertPlan(ServerLevel level, ItemStack plan) {
        if (state != PlaqueState.EMPTY || plan.isEmpty()) {
            return false;
        }
        type = PlaqueItemData.buildingType(plan);
        insertedPlan = plan.copyWithCount(1);
        state = PlaqueState.PLAN_INSERTED_UNLINKED;
        setChanged();
        survey(level);
        return true;
    }

    /**
     * Pulls the fitted plan back out, dissolving whatever it declared exactly
     * as breaking the plaque does. Returns the exact item that was fitted —
     * same component, same count — so this conserves it (INV-3);
     * {@link ItemStack#EMPTY} if nothing was fitted.
     */
    public ItemStack extractPlan(ServerLevel level, @Nullable Player remover) {
        if (state == PlaqueState.EMPTY || insertedPlan.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack out = insertedPlan.copy();
        dissolveBuilding(level, remover);
        insertedPlan = ItemStack.EMPTY;
        lastSurvey = List.of();
        state = PlaqueState.EMPTY;
        updateGlow(level);
        revision++;
        setChanged();
        return out;
    }

    // ------------------------------------------------------------ survey ---

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
                                  BlockState state, PlaqueBlockEntity plaque) {
        if (level instanceof ServerLevel serverLevel
            && serverLevel.getGameTime() >= plaque.nextSurveyTick) {
            plaque.nextSurveyTick = serverLevel.getGameTime() + SURVEY_INTERVAL;
            plaque.survey(serverLevel);
        }
    }

    /**
     * Look at the room and update everything that follows from it. Safe to
     * call often: it is a bounded flood fill plus a requirement tally. A
     * blank plaque (W3) refuses outright — no plan means nothing to look
     * for, and no room scan ever runs for one.
     */
    public void survey(ServerLevel level) {
        if (state == PlaqueState.EMPTY) {
            return;
        }
        scanAttempts++;
        RoomScanner.Result result = surveyRoom(level);
        PlaqueState previous = state;

        if (result == null || !result.enclosed() || result.skyLeak()
            || result.volume() > RoomScanner.MAX_HOME_VOLUME) {
            lastSurvey = List.of();
            unlink(level, PlaqueState.PLAN_INSERTED_UNLINKED);
        } else {
            List<Requirement.Status> statuses = new ArrayList<>();
            boolean allMet = true;
            for (Requirement requirement : type.requirements()) {
                Requirement.Status status = requirement.measure(result);
                statuses.add(status);
                allMet &= status.met();
            }
            lastSurvey = List.copyOf(statuses);
            if (allMet) {
                link(level, result);
            } else {
                unlink(level, PlaqueState.LINKED_INCOMPLETE);
            }
        }

        updateGlow(level);
        if (state != previous) {
            announce(level, previous);
        }
        revision++;
        setChanged();
        // setChanged() alone marks the chunk dirty for SAVING; it does not
        // resend the block. Without this the sheet would only refresh when
        // the chunk reloaded, so a bed placed in front of the player would
        // not tick its line over until they walked away and back.
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
            Block.UPDATE_CLIENTS);
    }

    /**
     * Finds the room this plaque speaks for. Players hang plaques inside the
     * room and outside beside the door in equal measure, so both are tried
     * rather than documented: in front first, then through the wall behind.
     *
     * <p>A candidate only wins outright when it is a room by D-004's own
     * measure — enclosed, roofed, AND within {@code MAX_HOME_VOLUME} — not
     * merely enclosed and roofed. Without the volume bound, a wrong-direction
     * seed that happens to flood into some far larger enclosed space (a cave,
     * a courtyard, a GameTest arena under its own barrier ceiling) would win
     * the short-circuit before the correct candidate is ever tried, then get
     * rejected downstream for being oversized — reporting no room found at
     * all even though a later candidate would have found the real one.
     */
    @Nullable
    private RoomScanner.Result surveyRoom(ServerLevel level) {
        Direction facing = getBlockState().getValue(PlaqueBlock.FACING);
        BlockPos[] candidates = {
            worldPosition.relative(facing),                       // hung inside
            worldPosition.relative(facing.getOpposite(), 2),      // hung outside, thin wall
            worldPosition.relative(facing.getOpposite(), 3),      // ...thick wall
        };
        RoomScanner.Result best = null;
        for (BlockPos seed : candidates) {
            RoomScanner.Result result = RoomScanner.scan(level, seed);
            if (result == null) {
                continue;
            }
            if (result.enclosed() && !result.skyLeak()
                && result.volume() <= RoomScanner.MAX_HOME_VOLUME) {
                return result; // a real room wins immediately
            }
            if (best == null) {
                best = result; // keep the best near-miss so the UI can explain it
            }
        }
        return best;
    }

    // -------------------------------------------------------------- link ---

    private void link(ServerLevel level, RoomScanner.Result result) {
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            state = PlaqueState.PLAN_INSERTED_UNLINKED;
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        Building building = building(level);
        boolean isNew = building == null;
        if (isNew) {
            building = new Building(UUID.randomUUID(), type, worldPosition,
                result.beds().isEmpty() ? worldPosition : result.beds().get(0),
                result.bounds());
            settlement.buildings.add(building);
            buildingId = building.id;
        }
        building.type = type;
        building.plaquePos = worldPosition;
        building.anchor = result.beds().isEmpty() ? worldPosition : result.beds().get(0);
        building.bounds = result.bounds();
        building.interiorVolume = result.volume();
        building.beds.clear();
        building.beds.addAll(result.beds());
        building.doorCount = result.doors();
        building.lightSources = result.lights();
        building.furnishingScore = result.furnishingScore();
        building.valid = true;
        building.lastValidatedGameTime = level.getGameTime();
        state = PlaqueState.LINKED_VALID;
        data.setDirty();

        if (type.housesResidents()) {
            data.buildingManager.assignFreeBeds(level, settlement, building);
        }
    }

    /** Drops the link without destroying the building's memory of its people. */
    private void unlink(ServerLevel level, PlaqueState newState) {
        Building building = building(level);
        if (building != null && building.valid) {
            building.valid = false;
            releaseResidents(level, building);
            SettlementSavedData.get(level).setDirty();
        }
        state = newState;
    }

    /** Breaking the plaque, or extracting its plan, dissolves the building outright. */
    public void dissolveBuilding(ServerLevel level, @Nullable Player breaker) {
        Settlement settlement = settlementFor(level);
        Building building = building(level);
        if (settlement == null || building == null) {
            return;
        }
        releaseResidents(level, building);
        settlement.buildings.remove(building);
        buildingId = null;
        state = PlaqueState.PLAN_INSERTED_UNLINKED;
        SettlementSavedData.get(level).setDirty();
        if (breaker != null) {
            breaker.displayClientMessage(Component.translatable(
                "hearthstead.plaque.dissolved", type.displayName()), false);
        }
    }

    /** Everyone housed here loses their bed, and feels it. */
    private void releaseResidents(ServerLevel level, Building building) {
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            return;
        }
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            BlockPos bed = settler.getClaimedBed();
            if (bed != null && building.beds.contains(bed)) {
                settler.releaseBed();
                settler.addMorale(-6.0F);
            }
        }
        building.workers.clear();
    }

    @Nullable
    public Building building(ServerLevel level) {
        if (buildingId == null) {
            return null;
        }
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            return null;
        }
        for (Building building : settlement.buildings) {
            if (building.id.equals(buildingId)) {
                return building;
            }
        }
        buildingId = null;
        state = PlaqueState.ORPHANED;
        return null;
    }

    @Nullable
    public Settlement settlementFor(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement nearest = null;
        double best = Double.MAX_VALUE;
        for (Settlement settlement : data.settlements.values()) {
            if (settlement.inside(worldPosition)) {
                double distance = settlement.center.distSqr(worldPosition);
                if (distance < best) {
                    best = distance;
                    nearest = settlement;
                }
            }
        }
        return nearest;
    }

    // ------------------------------------------------------ presentation ---

    private void updateGlow(ServerLevel level) {
        boolean anyProgress = false;
        for (Requirement.Status status : lastSurvey) {
            anyProgress |= status.met() || status.partial();
        }
        PlaqueBlock.Glow glow = PlaqueBlock.Glow.forState(state, anyProgress);
        BlockState current = getBlockState();
        if (current.getValue(PlaqueBlock.GLOW) != glow) {
            level.setBlock(worldPosition, current.setValue(PlaqueBlock.GLOW, glow), 3);
        }
    }

    private void announce(ServerLevel level, PlaqueState previous) {
        if (state == PlaqueState.LINKED_VALID) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.02);
            level.playSound(null, worldPosition,
                com.hearthstead.registry.ModSounds.PROFESSION_ASSIGNED.get(),
                SoundSource.BLOCKS, 0.8F, 1.15F);
        } else if (previous == PlaqueState.LINKED_VALID) {
            level.playSound(null, worldPosition,
                net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                SoundSource.BLOCKS, 0.7F, 0.8F);
        }
    }

    public void openScreen(ServerPlayer player) {
        screenOpens++;
        com.hearthstead.network.PlaqueNetwork.openFor(player, this);
    }

    // --------------------------------------------------------------- nbt ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putString("Type", type.id());
        tag.putString("State", state.id());
        tag.putInt("Revision", revision);
        if (buildingId != null) {
            tag.putUUID("Building", buildingId);
        }
        if (!insertedPlan.isEmpty()) {
            tag.put("Plan", insertedPlan.saveOptional(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        type = BuildingType.byId(tag.getString("Type"));
        buildingId = tag.hasUUID("Building") ? tag.getUUID("Building") : null;
        state = PlaqueState.byId(tag.getString("State"), buildingId != null);
        revision = tag.getInt("Revision");
        if (tag.contains(SURVEY_KEY)) {
            List<Requirement.Status> restored = new ArrayList<>();
            ListTag list = tag.getList(SURVEY_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                Requirement requirement = type.requirementById(entry.getString("Id"));
                if (requirement != null) {
                    restored.add(new Requirement.Status(requirement,
                        entry.getInt("Have"), entry.getInt("Needed")));
                }
            }
            lastSurvey = List.copyOf(restored);
        }
        insertedPlan = tag.contains("Plan")
            ? ItemStack.parseOptional(provider, tag.getCompound("Plan"))
            : ItemStack.EMPTY;
        // EMPTY while holding a Building id is contradictory after a load
        // (W2 refined #3) — a Building id means this was linked to something,
        // so resolve toward LINKED_VALID and let the next survey correct it.
        if (state == PlaqueState.EMPTY && buildingId != null) {
            state = PlaqueState.LINKED_VALID;
        }
    }

    /** Key for the surveyed requirement list. Wire-only, never on disk. */
    private static final String SURVEY_KEY = "Survey";

    /**
     * The client needs the type, the state AND the survey to draw the sheet.
     *
     * <p>The survey rides the update tag but is deliberately NOT written by
     * {@code saveAdditional}: it is derived data, recomputed by every
     * {@link #survey}, so persisting it would be a second copy of something
     * the world already knows. Without it on the wire, though, the block
     * renders identically in every state -- which is exactly what it did
     * before this, and why a player had to run a command to learn whether
     * their room passed.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        ListTag list = new ListTag();
        for (Requirement.Status status : lastSurvey) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", status.requirement().id());
            entry.putInt("Have", status.have());
            entry.putInt("Needed", status.needed());
            list.add(entry);
        }
        tag.put(SURVEY_KEY, list);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
