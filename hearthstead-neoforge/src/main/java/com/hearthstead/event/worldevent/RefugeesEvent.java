package com.hearthstead.event.worldevent;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Refugees ask for shelter: a family (two adults and a child) waits at the
 * Banner. Taking them in makes them real settlers at once (more hands, more
 * mouths); giving food sends them on grateful; sending them away is free.
 * A persuasion answer ("we can only take you if you work") may win them
 * plus their tools, or offend them. No answer by the end of the window:
 * they move on.
 */
final class RefugeesEvent implements WorldEventHandler {
    static final String ROLE_LEADER = "refugee_leader";
    static final String ROLE_REFUGEE = "refugee";
    static final int FAMILY = 3;
    static final int FOOD_GIFT = 6;

    @Override
    public WorldEventType type() {
        return WorldEventType.REFUGEES;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return settlement.population() >= 2;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 3, 6, 1.4F, 2.0F, level.random);
        if (spot == null) return false;
        List<SettlerEntity> family = new ArrayList<>();
        for (int i = 0; i < FAMILY; i++) {
            BlockPos feet = WorldEventCreatures.nearFeet(level, spot.offset(i - 1, 0, (i % 2)), 0.6F, 1.95F);
            if (feet == null) feet = spot;
            SettlerEntity refugee = WorldEventActors.spawnVisitor(level, settlement, active, feet,
                i == 0 ? ROLE_LEADER : ROLE_REFUGEE, i == FAMILY - 1 ? 0.65D : 1.0D);
            if (refugee == null) return false;
            family.add(refugee);
        }
        WorldEventActors.markWaiting(family.get(0), Component.translatableWithFallback(
            "hearthstead.event.refugees.who", "refugee"), true);
        WorldEventConversations.bind(family.get(0), settlement, WorldEventConversations.REFUGEES, "refugee",
            "conversation.hearthstead.title.refugee", java.util.Map.of("family", FAMILY));
        active.state.putLong("Spot", spot.asLong());
        holdRoles(level, active);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.refugees.arrive",
                "A weary family of refugees waits at the Banner, asking for shelter."),
            Component.translatableWithFallback("hearthstead.event.refugees.cta",
                "Talk to them (right-click) to take them in or send them on."), spot);
        return true;
    }

    private static void holdRoles(ServerLevel level, WorldEventSavedData.Active active) {
        BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
        long until = level.getGameTime() + 60;
        int i = 0;
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (actor instanceof SettlerEntity refugee) {
                WorldEventDirector.assign(refugee, new WorldEventDirector.Role(WorldEventDirector.RoleKind.HOLD,
                    active.id, null, spot.offset(i - 1, 0, i % 2), until, null, 0.6D));
                i++;
            }
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (active.state.getBoolean("Leaving")) {
            if (active.eligibleTicks - active.state.getInt("LeaveAt") >= 300
                || WorldEventActors.actors(level, active).isEmpty()) {
                WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
            } else {
                walkOffRoles(level, settlement, active);
            }
            return;
        }
        holdRoles(level, active);
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (!WorldEventVisitors.answered(active)) {
            WorldEventVisitors.markAnswered(active, "moved_on");
            WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
                "hearthstead.event.refugees.moved_on", "Nobody answered. The refugees shoulder their bundles and move on."));
        }
        WorldEventDirector.finish(level, settlement, "moved_on", null);
    }

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Leaving") && ROLE_LEADER.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        int freeBeds = Math.max(0, settlement.capacity() - settlement.population());
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("refugee", settlement.id), "refugee",
            Component.translatableWithFallback("hearthstead.event.refugees.title", "Refugees at the Banner"),
            Component.literal(WorldEventActors.plainName(actor)),
            List.of(
                Component.translatableWithFallback("hearthstead.event.refugees.line1",
                    "\"Raiders burned our farm. We have walked for three days.\""),
                Component.translatableWithFallback("hearthstead.event.refugees.line2",
                    "\"Let us stay. We will work, we only need a roof and bread.\""),
                Component.translatableWithFallback("hearthstead.event.refugees.beds",
                    "Free places: %s. They would be %s more mouths to feed.", freeBeds, FAMILY)),
            List.of(), "moved_on", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("accept", Component.translatableWithFallback(
                "hearthstead.event.refugees.accept", "Take them in"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.PRIMARY).withRelation(15),
            WorldEventVisitors.Option.of("work_for_keep", Component.translatableWithFallback(
                "hearthstead.event.refugees.persuade", "\"Only if you bring your tools and work\""),
                WorldEventVisitors.Cost.FREE, WorldEventVisitors.OptionStyle.SECONDARY)
                .persuade(new WorldEventVisitors.Persuasion(50, "work_yes", "work_no")),
            WorldEventVisitors.Option.of("feed", Component.translatableWithFallback(
                "hearthstead.event.refugees.feed", "Give food and send them on"),
                WorldEventVisitors.Cost.food(FOOD_GIFT), WorldEventVisitors.OptionStyle.SECONDARY).withRelation(8),
            WorldEventVisitors.Option.of("decline", Component.translatableWithFallback(
                "hearthstead.event.refugees.decline", "Send them on"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-10));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        switch (optionId) {
            case "accept", "work_yes" -> {
                List<String> names = accept(level, settlement, active);
                if ("work_yes".equals(optionId)) {
                    // They came with what they could carry.
                    net.minecraft.world.entity.item.ItemEntity tools = new net.minecraft.world.entity.item.ItemEntity(
                        level, settlement.center.getX() + .5, settlement.center.getY() + 1, settlement.center.getZ() + .5,
                        new ItemStack(Items.IRON_HOE));
                    level.addFreshEntity(tools);
                    level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
                        settlement.center.getX() + .5, settlement.center.getY() + 1, settlement.center.getZ() + .5,
                        new ItemStack(Items.WHEAT_SEEDS, 12)));
                }
                WorldEventDirector.finish(level, settlement, "accepted", null);
                return Component.translatableWithFallback("hearthstead.event.refugees.accepted",
                    "%s join %s. More hands, and more mouths to feed: find them beds and bread.",
                    String.join(", ", names), settlement.name);
            }
            case "feed" -> {
                leave(level, settlement, active, "fed");
                return Component.translatableWithFallback("hearthstead.event.refugees.fed",
                    "The refugees thank you for the food and walk on, blessing your village.");
            }
            case "work_no" -> {
                leave(level, settlement, active, "offended");
                return Component.translatableWithFallback("hearthstead.event.refugees.offended",
                    "\"We are not beggars.\" The family turns away, offended.");
            }
            default -> {
                leave(level, settlement, active, "declined");
                return Component.translatableWithFallback("hearthstead.event.refugees.declined",
                    "The refugees lower their heads and walk on.");
            }
        }
    }

    /** Binds every loaded family member as a real settler. Returns their names. */
    static List<String> accept(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<String> names = new ArrayList<>();
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (!(actor instanceof SettlerEntity refugee)) continue;
            String name = WorldEventActors.plainName(refugee);
            WorldEventConversations.unbind(refugee, null);
            WorldEventDirector.release(refugee, active);
            refugee.setSettlerName(name);
            refugee.bindTo(settlement.id, settlement.center);
            settlement.putRecord(refugee.getUUID(), name, Profession.NONE);
            names.add(name);
        }
        SettlementManager.data(level).setDirty();
        return names;
    }

    private static void leave(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", outcome);
        for (Entity actor : WorldEventActors.actors(level, active)) {
            WorldEventConversations.unbind(actor, null);
            WorldEventActors.markWaiting(actor, Component.translatableWithFallback(
                "hearthstead.event.refugees.who", "refugee"), false);
            actor.setCustomNameVisible(false);
        }
        walkOffRoles(level, settlement, active);
        WorldEventDirector.finish(level, settlement, outcome, null); // they walk out; never a puff
    }

    private static void walkOffRoles(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
        double dx = spot.getX() - settlement.center.getX(), dz = spot.getZ() - settlement.center.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        BlockPos away = BlockPos.containing(settlement.center.getX() + dx / len * (settlement.radius + 24),
            spot.getY(), settlement.center.getZ() + dz / len * (settlement.radius + 24));
        long until = level.getGameTime() + 60;
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (actor instanceof SettlerEntity refugee) {
                WorldEventDirector.assign(refugee, new WorldEventDirector.Role(WorldEventDirector.RoleKind.WALK_OFF,
                    active.id, null, away, until, null, 0.7D));
            }
        }
    }
}
