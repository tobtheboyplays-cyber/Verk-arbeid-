package com.hearthstead.event.worldevent;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tavern brawl: two residents fall out over their ale and come to blows
 * (no real damage). A Guard runs over and pulls them apart; a player can
 * step in first by right-clicking a brawler; left alone they fight until
 * they are tired. Each ending costs a little morale, the last one the most.
 * The brawlers are real settlers: nothing is spawned or discarded.
 */
final class TavernBrawlEvent implements WorldEventHandler, WorldEventDirector.RoleListener {
    static final float GUARD_PENALTY = -3.0F;
    static final float PLAYER_PENALTY = -1.0F;
    static final float TIRED_PENALTY = -6.0F;
    static final float BYSTANDER_PENALTY = -1.0F;

    @Override
    public WorldEventType type() {
        return WorldEventType.TAVERN_BRAWL;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return WorldEventActors.tavern(level, settlement) != null
            && brawlerCandidates(level, settlement, WorldEventActors.tavern(level, settlement)).size() >= 2;
    }

    private static List<SettlerEntity> brawlerCandidates(ServerLevel level, Settlement settlement, Building tavern) {
        List<SettlerEntity> out = WorldEventActors.members(level, settlement, s -> !WorldEventActors.isMartial(s)
            && !s.isSleeping() && s.getTarget() == null && !s.getUUID().equals(settlement.mayorId)
            && s.blockPosition().distSqr(tavern.anchor) <= 48 * 48);
        out.sort(Comparator.comparingDouble(s -> s.blockPosition().distSqr(tavern.anchor)));
        return out;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Building tavern = WorldEventActors.tavern(level, settlement);
        if (tavern == null) return false;
        List<SettlerEntity> candidates = brawlerCandidates(level, settlement, tavern);
        if (candidates.size() < 2) return false;
        SettlerEntity a = candidates.get(0), b = candidates.get(1);
        if (a.hasTavernSeat()) a.leaveSeat();
        if (b.hasTavernSeat()) b.leaveSeat();
        active.state.putUUID("A", a.getUUID());
        active.state.putUUID("B", b.getUUID());
        active.state.putString("NameA", a.getSettlerName());
        active.state.putString("NameB", b.getSettlerName());
        active.state.putLong("Tavern", tavern.anchor.asLong());
        roles(level, active);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.brawl.start",
                "A shouting match at the Tavern turns into a brawl: %s and %s!", a.getSettlerName(), b.getSettlerName()),
            Component.translatableWithFallback("hearthstead.event.brawl.cta",
                "A guard is on the way, or step in yourself (right-click a brawler)."), tavern.anchor);
        return true;
    }

    private static void roles(ServerLevel level, WorldEventSavedData.Active active) {
        long until = level.getGameTime() + 40;
        if (level.getEntity(active.state.getUUID("A")) instanceof SettlerEntity a
            && level.getEntity(active.state.getUUID("B")) instanceof SettlerEntity b) {
            WorldEventDirector.assign(a, new WorldEventDirector.Role(WorldEventDirector.RoleKind.BRAWL,
                active.id, b.getUUID(), null, until, null, 1.0D));
            WorldEventDirector.assign(b, new WorldEventDirector.Role(WorldEventDirector.RoleKind.BRAWL,
                active.id, a.getUUID(), null, until, null, 1.0D));
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (!(level.getEntity(active.state.getUUID("A")) instanceof SettlerEntity a) || !a.isAlive()
            || !(level.getEntity(active.state.getUUID("B")) instanceof SettlerEntity b) || !b.isAlive()) {
            WorldEventDirector.finish(level, settlement, "fizzled", null);
            return;
        }
        roles(level, active);
        // Call the nearest free guard once the fists fly.
        List<SettlerEntity> guards = WorldEventActors.members(level, settlement,
            s -> WorldEventActors.isMartial(s) && s.getTarget() == null);
        SettlerEntity guard = WorldEventActors.nearest(guards, a, 64.0D);
        if (guard != null) {
            WorldEventDirector.assign(guard, new WorldEventDirector.Role(WorldEventDirector.RoleKind.CHASE,
                active.id, a.getUUID(), null, level.getGameTime() + 40, null, 1.15D));
            active.state.putUUID("Guard", guard.getUUID());
        }
    }

    @Override
    public void roleReached(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            SettlerEntity settler, WorldEventDirector.Role role) {
        resolve(level, settlement, active, GUARD_PENALTY, "guard", Component.translatableWithFallback(
            "hearthstead.event.brawl.guard", "%s pulls %s and %s apart. Both sulk into their ale.",
            settler.getSettlerName(), active.state.getString("NameA"), active.state.getString("NameB")));
    }

    /** A player right-clicked one of the brawlers. */
    static boolean separate(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, ServerPlayer player) {
        ((TavernBrawlEvent) WorldEventDirector.handler(WorldEventType.TAVERN_BRAWL)).resolve(level, settlement, active,
            PLAYER_PENALTY, "player", Component.translatableWithFallback("hearthstead.event.brawl.player",
                "%s steps between %s and %s. They grumble, then shake hands.", player.getDisplayName(),
                active.state.getString("NameA"), active.state.getString("NameB")));
        return true;
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos tavern = BlockPos.of(active.state.getLong("Tavern"));
        for (SettlerEntity bystander : WorldEventActors.members(level, settlement,
                s -> s.blockPosition().distSqr(tavern) <= 16 * 16)) {
            if (!isBrawler(active, bystander.getUUID())) bystander.addMorale(BYSTANDER_PENALTY);
        }
        resolve(level, settlement, active, TIRED_PENALTY, "tired", Component.translatableWithFallback(
            "hearthstead.event.brawl.tired", "%s and %s fight until neither can lift a fist. The whole Tavern is sour tonight.",
            active.state.getString("NameA"), active.state.getString("NameB")));
    }

    static boolean isBrawler(WorldEventSavedData.Active active, UUID id) {
        return id.equals(active.state.getUUID("A")) || id.equals(active.state.getUUID("B"));
    }

    private void resolve(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                         float penalty, String outcome, Component notice) {
        for (String key : new String[]{"A", "B"}) {
            if (level.getEntity(active.state.getUUID(key)) instanceof SettlerEntity brawler && brawler.isAlive()) {
                brawler.addMorale(penalty);
                WorldEventDirector.clearRole(brawler);
                brawler.getNavigation().stop();
            }
        }
        if (active.state.hasUUID("Guard") && level.getEntity(active.state.getUUID("Guard")) instanceof SettlerEntity guard) {
            WorldEventDirector.clearRole(guard);
        }
        WorldEventDirector.finish(level, settlement, outcome, notice);
    }
}
