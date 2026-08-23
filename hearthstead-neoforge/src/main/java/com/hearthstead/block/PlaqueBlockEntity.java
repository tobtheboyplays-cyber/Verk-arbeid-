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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
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
 */
public class PlaqueBlockEntity extends BlockEntity {

    /** How often a hung plaque re-checks its room, in ticks (10 s). */
    private static final int SURVEY_INTERVAL = 200;

    private BuildingType type = BuildingType.HOUSE;
    private PlaqueState state = PlaqueState.UNLINKED;
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

    public PlaqueBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLAQUE.get(), pos, state);
    }

    // ------------------------------------------------------------- state ---

    public BuildingType type() {
        return type;
    }

    public void setType(BuildingType type) {
        this.type = type;
        setChanged();
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
     * call often: it is a bounded flood fill plus a requirement tally.
     */
    public void survey(ServerLevel level) {
        RoomScanner.Result result = surveyRoom(level);
        PlaqueState previous = state;

        if (result == null || !result.enclosed() || result.skyLeak()
            || result.volume() > RoomScanner.MAX_HOME_VOLUME) {
            lastSurvey = List.of();
            unlink(level, PlaqueState.UNLINKED);
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
                unlink(level, PlaqueState.INCOMPLETE);
            }
        }

        updateGlow(level);
        if (state != previous) {
            announce(level, previous);
        }
        revision++;
        setChanged();
    }

    /**
     * Finds the room this plaque speaks for. Players hang plaques inside the
     * room and outside beside the door in equal measure, so both are tried
     * rather than documented: in front first, then through the wall behind.
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
            if (result.enclosed() && !result.skyLeak()) {
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
            state = PlaqueState.UNLINKED;
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
        state = PlaqueState.LINKED;
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

    /** Breaking the plaque dissolves the building outright. */
    public void dissolveBuilding(ServerLevel level, @Nullable Player breaker) {
        Settlement settlement = settlementFor(level);
        Building building = building(level);
        if (settlement == null || building == null) {
            return;
        }
        releaseResidents(level, building);
        settlement.buildings.remove(building);
        buildingId = null;
        state = PlaqueState.UNLINKED;
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
        if (state == PlaqueState.LINKED) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.02);
            level.playSound(null, worldPosition,
                com.hearthstead.registry.ModSounds.PROFESSION_ASSIGNED.get(),
                SoundSource.BLOCKS, 0.8F, 1.15F);
        } else if (previous == PlaqueState.LINKED) {
            level.playSound(null, worldPosition,
                net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                SoundSource.BLOCKS, 0.7F, 0.8F);
        }
    }

    public void openScreen(ServerPlayer player) {
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        type = BuildingType.byId(tag.getString("Type"));
        state = PlaqueState.byId(tag.getString("State"));
        revision = tag.getInt("Revision");
        buildingId = tag.hasUUID("Building") ? tag.getUUID("Building") : null;
    }

    /** The client needs the type and state to render the plaque's face. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
