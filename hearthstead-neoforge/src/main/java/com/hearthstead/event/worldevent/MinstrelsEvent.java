package com.hearthstead.event.worldevent;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Travelling minstrels: three players arrive at the Tavern at dusk. Host
 * the feast (a few Coins) for a full evening set, or let them play a short
 * set for tips; everyone who spent the evening in the Tavern wakes up in a
 * better mood. They play with the existing Tavern bard presentation
 * (PLAYING_MUSIC: the bard clip plus the Tavern tune), so no new audio.
 */
final class MinstrelsEvent implements WorldEventHandler {
    static final String ROLE_LEAD = "minstrel_lead";
    static final String ROLE_MINSTREL = "minstrel";
    static final int BAND = 3;
    static final int HOST_COINS = 4;
    static final int FULL_SET_TICKS = 3_600;
    static final int SHORT_SET_TICKS = 1_600;
    static final float HOSTED_LIFT = 8.0F;
    static final float TIPS_LIFT = 4.0F;
    static final int MAX_ATTENDEES = 64;

    @Override
    public WorldEventType type() {
        return WorldEventType.MINSTRELS;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return WorldEventActors.tavern(level, settlement) != null && settlement.population() >= 2;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Building tavern = WorldEventActors.tavern(level, settlement);
        if (tavern == null) return false;
        List<BlockPos> spots = WorldEventActors.standingSpots(level, tavern, 12);
        if (spots.size() < BAND) return false;
        for (int i = 0; i < BAND; i++) {
            BlockPos feet = spots.get(Math.min(spots.size() - 1, i * 2));
            SettlerEntity minstrel = WorldEventActors.spawnVisitor(level, settlement, active, feet,
                i == 0 ? ROLE_LEAD : ROLE_MINSTREL, 1.0D);
            if (minstrel == null) return false;
            active.state.putLong("Stage" + i, feet.asLong());
            WorldEventActors.markWaiting(minstrel, Component.translatableWithFallback(
                "hearthstead.event.minstrels.who", "minstrel"), i == 0);
            if (i > 0) minstrel.setCustomNameVisible(false);
            if (i == 0) {
                WorldEventConversations.bind(minstrel, settlement, WorldEventConversations.MINSTRELS, "minstrel",
                    "conversation.hearthstead.title.minstrel", java.util.Map.of("coins", HOST_COINS));
            }
        }
        active.state.putUUID("Tavern", tavern.id);
        roles(level, active, null);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.minstrels.arrive",
                "Travelling minstrels have come to the Tavern for the evening."),
            Component.translatableWithFallback("hearthstead.event.minstrels.cta",
                "Talk to their leader to host a feast, or let them play for tips."), tavern.anchor);
        return true;
    }

    private static void roles(ServerLevel level, WorldEventSavedData.Active active, SettlerActivity activity) {
        long until = level.getGameTime() + 60;
        int i = 0;
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (!(actor instanceof SettlerEntity minstrel)) continue;
            BlockPos stage = BlockPos.of(active.state.getLong("Stage" + Math.min(i, BAND - 1)));
            WorldEventDirector.assign(minstrel, new WorldEventDirector.Role(
                active.state.getBoolean("Leaving") ? WorldEventDirector.RoleKind.WALK_OFF : WorldEventDirector.RoleKind.HOLD,
                active.id, null, active.state.getBoolean("Leaving") ? leavePos(active) : stage, until, activity, 0.6D));
            i++;
        }
    }

    private static BlockPos leavePos(WorldEventSavedData.Active active) {
        return BlockPos.of(active.state.getLong("LeavePos"));
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (active.state.getBoolean("Leaving")) {
            roles(level, active, null);
            if (active.eligibleTicks - active.state.getInt("LeaveAt") >= 300) {
                WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
            }
            return;
        }
        maybeDefault(level, settlement, active);
        boolean playing = active.state.getBoolean("Playing");
        roles(level, active, playing ? SettlerActivity.PLAYING_MUSIC : null);
        if (!playing) return;
        collectAttendees(level, settlement, active);
        int playedFor = active.eligibleTicks - active.state.getInt("PlayingSince");
        if (playedFor >= active.state.getInt("SetLength")) endSet(level, settlement, active);
    }

    private static void collectAttendees(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Building tavern = WorldEventActors.tavern(level, settlement);
        if (tavern == null) return;
        ListTag list = active.state.getList("Attendees", Tag.TAG_INT_ARRAY);
        AABB room = AABB.of(tavern.bounds).inflate(3.0D);
        for (SettlerEntity settler : level.getEntitiesOfClass(SettlerEntity.class, room,
                s -> s.isAlive() && settlement.id.equals(s.getSettlementId()))) {
            if (list.size() >= MAX_ATTENDEES) break;
            Tag id = NbtUtils.createUUID(settler.getUUID());
            if (!list.contains(id)) list.add(id);
        }
        active.state.put("Attendees", list);
    }

    private static void beginSet(ServerLevel level, WorldEventSavedData.Active active, boolean hosted) {
        active.state.putBoolean("Playing", true);
        active.state.putBoolean("Hosted", hosted);
        active.state.putInt("PlayingSince", active.eligibleTicks);
        active.state.putInt("SetLength", hosted ? FULL_SET_TICKS : SHORT_SET_TICKS);
        for (Entity actor : WorldEventActors.actors(level, active)) {
            WorldEventConversations.unbind(actor, null);
            WorldEventActors.markWaiting(actor, Component.translatableWithFallback(
                "hearthstead.event.minstrels.who", "minstrel"), false);
            actor.setCustomNameVisible(false);
        }
        roles(level, active, SettlerActivity.PLAYING_MUSIC);
    }

    private static void endSet(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        boolean hosted = active.state.getBoolean("Hosted");
        float lift = hosted ? HOSTED_LIFT : TIPS_LIFT;
        ListTag list = active.state.getList("Attendees", Tag.TAG_INT_ARRAY);
        int cheered = 0;
        for (Tag raw : list) {
            if (level.getEntity(NbtUtils.loadUUID(raw)) instanceof SettlerEntity settler && settler.isAlive()) {
                settler.addMorale(lift);
                cheered++;
            }
        }
        // The tune carries over the rooftops: everyone else gets a little of it.
        for (SettlerEntity settler : WorldEventActors.members(level, settlement, s -> true)) {
            if (!list.contains(NbtUtils.createUUID(settler.getUUID()))) settler.addMorale(hosted ? 2.0F : 1.0F);
        }
        active.state.putInt("Cheered", cheered);
        leave(level, settlement, active, hosted ? "feast" : "tips");
        WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
            hosted ? "hearthstead.event.minstrels.feast_end" : "hearthstead.event.minstrels.tips_end",
            hosted ? "The feast winds down. %s guests will be humming the tunes all tomorrow."
                : "The minstrels finish their short set. %s listeners go home smiling.", cheered));
    }

    private static void leave(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        active.state.putBoolean("Playing", false);
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", outcome);
        for (Entity actor : WorldEventActors.actors(level, active)) WorldEventConversations.unbind(actor, null);
        BlockPos stage = BlockPos.of(active.state.getLong("Stage0"));
        double dx = stage.getX() - settlement.center.getX(), dz = stage.getZ() - settlement.center.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        active.state.putLong("LeavePos", BlockPos.containing(settlement.center.getX() + dx / len * (settlement.radius + 24),
            stage.getY(), settlement.center.getZ() + dz / len * (settlement.radius + 24)).asLong());
        roles(level, active, null);
        WorldEventDirector.finish(level, settlement, outcome, null); // they walk out; never a puff
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (active.state.getBoolean("Playing")) {
            endSet(level, settlement, active);
        }
        WorldEventDirector.finish(level, settlement, active.state.contains("Outcome")
            ? active.state.getString("Outcome") : "timeout", null);
    }

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Playing") && !active.state.getBoolean("Leaving")
            && ROLE_LEAD.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("minstrel", settlement.id), "minstrel",
            Component.translatableWithFallback("hearthstead.event.minstrels.title", "Travelling minstrels"),
            Component.literal(WorldEventActors.plainName(actor)),
            List.of(Component.translatableWithFallback("hearthstead.event.minstrels.line1",
                    "\"Lute, drum and fiddle, friend! Host us tonight and your village will dance till the fire dies.\""),
                Component.translatableWithFallback("hearthstead.event.minstrels.line2",
                    "\"Or we play a short set for whatever the room will throw in the hat.\"")),
            List.of(), "tips", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("host", Component.translatableWithFallback(
                "hearthstead.event.minstrels.host", "Host the feast"), WorldEventVisitors.Cost.coins(HOST_COINS),
                WorldEventVisitors.OptionStyle.PRIMARY).withRelation(10),
            WorldEventVisitors.Option.of("tips", Component.translatableWithFallback(
                "hearthstead.event.minstrels.tips", "Play for tips"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.SECONDARY),
            WorldEventVisitors.Option.of("away", Component.translatableWithFallback(
                "hearthstead.event.minstrels.away", "Send them away"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-5));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        return switch (optionId) {
            case "host" -> {
                beginSet(level, active, true);
                yield Component.translatableWithFallback("hearthstead.event.minstrels.hosted",
                    "The minstrels strike up! Tonight the Tavern feasts; come and listen.");
            }
            case "tips" -> {
                beginSet(level, active, false);
                yield Component.translatableWithFallback("hearthstead.event.minstrels.tips_start",
                    "The minstrels pass the hat and play a short set.");
            }
            default -> {
                leave(level, settlement, active, "sent_away");
                yield Component.translatableWithFallback("hearthstead.event.minstrels.sent_away",
                    "The minstrels shrug, sling their instruments and head for the next village.");
            }
        };
    }

    /** No answer before the evening: they play a short set for tips anyway. */
    static void defaultAnswer(ServerLevel level, WorldEventSavedData.Active active) {
        WorldEventVisitors.markAnswered(active, "tips");
        beginSet(level, active, false);
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        // Nothing to re-install: roles are re-issued every second by tick().
    }

    /** Answer window of about one in-game hour, then the default (a short set for tips). */
    static final int ANSWER_TICKS = 1_000;

    /** Applies the default answer once the answer window has passed. */
    void maybeDefault(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (!WorldEventVisitors.answered(active) && active.eligibleTicks >= ANSWER_TICKS) {
            defaultAnswer(level, active);
            WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
                "hearthstead.event.minstrels.default", "Nobody came to the minstrels, so they play a short set for tips."));
        }
    }
}
