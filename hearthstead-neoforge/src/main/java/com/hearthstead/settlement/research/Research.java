package com.hearthstead.settlement.research;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Prøvebenken — the research house's own state and rules.
 *
 * <p><b>Its own {@link SavedData}, keyed by settlement UUID</b>, mirroring
 * {@link com.hearthstead.settlement.SettlementSavedData}'s exact shape — one
 * map, one factory, one {@code get(level)} — deliberately NOT a field on
 * {@link Settlement} itself, which belongs to another worker while this slice
 * lands. {@code PLAN_RESEARCH.md} §3 names the merge as follow-up work: when
 * it happens, only {@link #load} and {@link #save} below change, because
 * {@link ResearchState} is already the plain data record that would move.
 *
 * <p><b>This class is also the facade</b> — the {@link Building}/{@code
 * Employment}-style static API a screen and a goal call — rather than a
 * separate class the way {@code Employment} sits beside {@code Settlement}.
 * The one thing the task brief asks for by name, {@code Research.bonus(...)},
 * settled that: a second static-only class here would only exist to hold
 * that one method's neighbours.
 *
 * <h2>Chest truth (INV-3)</h2>
 *
 * <p>A project's cost leaves a real container the moment it starts — see
 * {@link #start} — and {@link #cancel} puts back a documented share of it
 * (see {@link #REFUND_FRACTION}) rather than the whole amount, so cancelling
 * is a real decision and not a free undo. Nothing here ever destroys an item:
 * a refund that cannot fit is dropped at the study's anchor, the same rule
 * {@code Production#run} follows.
 *
 * <h2>Where the materials come from (v1)</h2>
 *
 * <p>{@code BuildingType.ARCHITECTS_STUDY} (unowned by this worker) declares
 * no storage requirement — see {@code PLAN_RESEARCH.md} §2. {@link
 * #sourcesFor} therefore pays from a chest or barrel actually standing in the
 * study's own room when one is present ({@link WarehouseIndex#containers},
 * the same lookup {@code Production} uses for every crafting building), and
 * falls back to the settlement hearth's communal larder otherwise. Giving the
 * study a real storage requirement is the natural v2 follow-up, noted there.
 */
public final class Research extends SavedData {
    private static final String DATA_NAME = "hearthstead_research";

    /** Half the domain sample and none of the paper comes back on cancel —
     *  the write-up is spent regardless; see {@code PLAN_RESEARCH.md} §3 for
     *  why a flat half (rather than a per-project number) is the honest
     *  answer for a first version: a cancelled project is abandoned effort,
     *  not a free undo, and the player should be able to say "half" without
     *  checking six different numbers. */
    private static final float REFUND_FRACTION = 0.5F;

    /** The passive scribe trickle: a scholar who is merely employed (not
     *  necessarily working a session right now) nudges the active project
     *  forward once a day even between visits to the lectern. Small on
     *  purpose — about one free session every three days, against
     *  {@code ScholarWorkGoal}'s own session every few minutes of active
     *  work — so an attended study is always the main driver and an
     *  unattended one merely does not stall completely. */
    private static final float TRICKLE_PER_DAY = 0.34F;

    public final Map<UUID, ResearchState> settlements = new HashMap<>();

    private static final Factory<Research> FACTORY =
        new Factory<>(Research::new, Research::load, null);

    public static Research get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Research() {
    }

    public static Research load(CompoundTag tag, HolderLookup.Provider registries) {
        Research data = new Research();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            data.settlements.put(entry.getUUID("Id"),
                ResearchState.readNbt(entry.getCompound("State")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ResearchState> entry : settlements.entrySet()) {
            CompoundTag out = new CompoundTag();
            out.putUUID("Id", entry.getKey());
            out.put("State", entry.getValue().writeNbt());
            list.add(out);
        }
        tag.put("Settlements", list);
        return tag;
    }

    /** The record for one settlement, created empty on first touch. */
    public static ResearchState of(ServerLevel level, UUID settlementId) {
        return get(level).settlements.computeIfAbsent(settlementId, id -> new ResearchState());
    }

    // -------------------------------------------------------------- study ---

    /** The registered, valid architects' study whose room contains {@code pos}. */
    @Nullable
    public static Building studyAt(Settlement settlement, BlockPos pos) {
        for (Building building : settlement.buildings) {
            if (building.type == BuildingType.ARCHITECTS_STUDY && building.valid
                && building.contains(pos)) {
                return building;
            }
        }
        return null;
    }

    /** The settlement's first valid study, wherever its lectern is. Used by
     *  the daily-trickle driver, which has no click position to start from. */
    @Nullable
    public static Building firstStudy(Settlement settlement) {
        for (Building building : settlement.buildings) {
            if (building.type == BuildingType.ARCHITECTS_STUDY && building.valid) {
                return building;
            }
        }
        return null;
    }

    /** The study's one worker (capacity 1 — {@code BuildingType.ARCHITECTS_STUDY}),
     *  or null if the post is empty or they are unloaded. */
    @Nullable
    public static SettlerEntity scholarOf(ServerLevel level, Settlement settlement,
                                          Building study) {
        if (study.workers.isEmpty()) {
            return null;
        }
        UUID id = study.workers.get(0);
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getUUID().equals(id)) {
                return settler;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- start --

    /** Why a project could not be started, as a translation key. */
    public record Refusal(String key) {
    }

    /**
     * Starts a project: pays every cost up front, atomically (every line is
     * checked before any is taken), or refuses with a reason and takes
     * nothing.
     */
    @Nullable
    public static Refusal start(ServerLevel level, Settlement settlement, Building study,
                                ResearchProject project) {
        ResearchState state = of(level, settlement.id);
        if (state.active != null) {
            return new Refusal("hearthstead.research.refused.busy");
        }
        if (state.completed.contains(project)) {
            return new Refusal("hearthstead.research.refused.done");
        }
        List<IItemHandler> sources = sourcesFor(level, settlement, study);
        // COSTS.md's law 2, "the village helps": a standing library takes a
        // quarter off the materials, capped with every other discount at
        // half. The percentage comes from Costs so this side never grows a
        // second copy of the arithmetic -- a project's costs are fixed at
        // enum-construction time and are not a Costs.Price, which is exactly
        // why the table exposes the number instead of only the Price.
        int off = com.hearthstead.settlement.Costs.discountPercent(
            com.hearthstead.settlement.Costs.discountsFor(level, settlement,
                com.hearthstead.settlement.Costs.PriceKey.RESEARCH));
        for (ResearchProject.Cost cost : project.costs()) {
            int due = com.hearthstead.settlement.Costs.discounted(cost.count(), off);
            if (count(sources, cost.item()) < due) {
                return new Refusal("hearthstead.research.refused.materials");
            }
        }
        for (ResearchProject.Cost cost : project.costs()) {
            take(sources, cost.item(),
                com.hearthstead.settlement.Costs.discounted(cost.count(), off));
        }
        ResearchState.Active active = new ResearchState.Active();
        active.project = project;
        state.active = active;
        get(level).setDirty();
        return null;
    }

    /**
     * Cancels the active project. Refunds {@link #REFUND_FRACTION} of the
     * domain sample (never the paper — the write-up so far is spent) into
     * the same source it was paid from, dropping at the study's anchor
     * whatever will not fit.
     */
    public static void cancel(ServerLevel level, Settlement settlement, Building study) {
        ResearchState state = of(level, settlement.id);
        if (state.active == null) {
            return;
        }
        ResearchProject project = state.active.project;
        List<IItemHandler> sources = sourcesFor(level, settlement, study);
        for (ResearchProject.Cost cost : project.costs()) {
            if (cost.item() == ResearchProject.PAPER) {
                continue;
            }
            int refund = Math.round(cost.count() * REFUND_FRACTION);
            if (refund > 0) {
                giveBack(level, study, sources, cost.item(), refund);
            }
        }
        state.active = null;
        get(level).setDirty();
    }

    // ------------------------------------------------------------ progress --

    public static boolean hasActiveProject(ServerLevel level, UUID settlementId) {
        return of(level, settlementId).active != null;
    }

    /** One work-session finished at the lectern ({@code ScholarWorkGoal}). */
    public static void advanceSession(ServerLevel level, UUID settlementId) {
        ResearchState state = of(level, settlementId);
        if (state.active == null) {
            return;
        }
        state.active.sessions++;
        completeIfDone(state);
        get(level).setDirty();
    }

    /**
     * The passive scribe trickle. Fires at most once per in-game day, and
     * only when the study has an active project AND an employed scholar —
     * research a settlement pays nobody to think about does not advance
     * itself.
     */
    public static void tickDailyTrickle(ServerLevel level, Settlement settlement,
                                        Building study) {
        ResearchState state = of(level, settlement.id);
        if (state.active == null || study.workers.isEmpty()) {
            return;
        }
        long day = level.getDayTime() / 24000L;
        if (state.trickleDay == day) {
            return;
        }
        state.trickleDay = day;
        state.active.trickle += TRICKLE_PER_DAY;
        if (state.active.trickle >= 1.0F) {
            state.active.trickle -= 1.0F;
            state.active.sessions++;
        }
        completeIfDone(state);
        get(level).setDirty();
    }

    private static void completeIfDone(ResearchState state) {
        if (state.active != null && state.active.sessions >= state.active.project.workDays()) {
            state.completed.add(state.active.project);
            state.active = null;
        }
    }

    // ----------------------------------------------------------------- read --

    /** The multiplier every completed project touching {@code key} applies.
     *  {@code 1.0F} — neutral, never a gate (FLOWS.md) — when none has. */
    public static float bonus(ServerLevel level, UUID settlementId, ResearchKey key) {
        ResearchState state = of(level, settlementId);
        float value = 1.0F;
        for (ResearchProject project : state.completed) {
            if (project.key() == key) {
                value *= project.bonus();
            }
        }
        return value;
    }

    /** Changes exactly when a stale click could do the wrong thing — the
     *  same idea as {@code PlaqueBlockEntity#revision} and {@code
     *  SettlerNetwork#revisionOf}. */
    public static int revisionOf(ServerLevel level, UUID settlementId) {
        ResearchState state = of(level, settlementId);
        return Objects.hash(state.completed,
            state.active == null ? null : state.active.project,
            state.active == null ? 0 : state.active.sessions);
    }

    /** How much of each cost line the settlement has on hand right now, in
     *  {@link ResearchProject#values()} order, each inner list in that
     *  project's own {@code costs()} order. One container scan shared across
     *  all six, for the screen's affordability colouring. */
    public static List<List<Integer>> haveCounts(ServerLevel level, Settlement settlement,
                                                  Building study) {
        List<IItemHandler> sources = sourcesFor(level, settlement, study);
        List<List<Integer>> out = new ArrayList<>(ResearchProject.BY_ORDINAL.length);
        for (ResearchProject project : ResearchProject.values()) {
            List<Integer> haves = new ArrayList<>(project.costs().size());
            for (ResearchProject.Cost cost : project.costs()) {
                haves.add(count(sources, cost.item()));
            }
            out.add(haves);
        }
        return out;
    }

    // ------------------------------------------------------------- storage --

    /** The study's own chest(s) if it has any, else the hearth's communal
     *  larder — see the class doc's "Where the materials come from" section. */
    private static List<IItemHandler> sourcesFor(ServerLevel level, Settlement settlement,
                                                  Building study) {
        List<BlockPos> containers = WarehouseIndex.containers(level, study);
        List<IItemHandler> out = new ArrayList<>();
        if (!containers.isEmpty()) {
            for (BlockPos pos : containers) {
                if (level.getBlockEntity(pos) instanceof Container container) {
                    out.add(new InvWrapper(container));
                }
            }
            return out;
        }
        if (level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth) {
            out.add(hearth.getInventory());
        }
        return out;
    }

    private static int count(List<IItemHandler> sources, Item item) {
        int total = 0;
        for (IItemHandler handler : sources) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /** Removes up to {@code amount} matching items across every source.
     *  Callers only invoke this after {@link #count} has already confirmed
     *  enough exists, so a short take here would mean the world changed
     *  under us between the check and the write — the same race {@code
     *  Production#run} accepts as out of scope for a single-player-hosted
     *  save. */
    private static void take(List<IItemHandler> sources, Item item, int amount) {
        int left = amount;
        for (IItemHandler handler : sources) {
            for (int slot = 0; slot < handler.getSlots() && left > 0; slot++) {
                if (!handler.getStackInSlot(slot).is(item)) {
                    continue;
                }
                ItemStack extracted = handler.extractItem(slot, left, false);
                left -= extracted.getCount();
            }
        }
    }

    /** Inserts what it can; drops the remainder at the study's anchor rather
     *  than voiding it (INV-3), mirroring {@code Production}'s own giveBack. */
    private static void giveBack(ServerLevel level, Building study, List<IItemHandler> sources,
                                 Item item, int amount) {
        ItemStack remaining = new ItemStack(item, amount);
        for (IItemHandler handler : sources) {
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, false);
            if (remaining.isEmpty()) {
                return;
            }
        }
        if (!remaining.isEmpty() && study.anchor != null) {
            Block.popResource(level, study.anchor, remaining);
        }
    }
}
