package com.hearthstead.event.worldevent;

import com.hearthstead.conversation.RelationSavedData;
import com.hearthstead.conversation.SpeakerProfile;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Rival envoy (owner-approved diplomacy teaser): an envoy of a neighbouring
 * lord comes to the Banner. Negotiate a trade pact (a persuasion roll),
 * befriend them with a gift, insult them, or send them off politely. The
 * lord's standing with this settlement is a relation value in the
 * conversation lane's relation memory (identity = the lord, stable per
 * settlement), kept for the future diplomacy system; each answer also has a
 * small reward or cost now.
 */
final class RivalEnvoyEvent implements WorldEventHandler {
    static final String ROLE_ENVOY = "rival_envoy";
    static final String ROLE_ATTENDANT = "envoy_attendant";
    static final int GIFT_FOOD = 8;
    static final int PACT_COINS = 6;
    static final int PACT_CHANCE = 40;
    static final Map<String, Integer> RELATION = Map.of(
        "pact", 10, "pact_failed", -5, "befriend", 20, "insult", -25, "dismiss", 0);
    private static final String[] LORDS = {
        "Lord Aldric of Greywater", "Lady Maren of Ashford", "Lord Osric of Thornhill",
        "Lady Ysolde of Redmere", "Lord Bertram of Coldharbour", "Lady Edith of Wolfden"};

    /** The rival lord's stable identity for this settlement. */
    static UUID lordId(Settlement settlement) {
        return WorldEventActors.stableId("rival_lord", settlement.id);
    }

    static String lordName(Settlement settlement) {
        return LORDS[Math.floorMod(lordId(settlement).hashCode(), LORDS.length)];
    }

    static SpeakerProfile lordProfile(Settlement settlement) {
        return new SpeakerProfile(lordId(settlement), lordName(settlement), "conversation.hearthstead.title.envoy", "rival_lord");
    }

    /** Current standing with the rival lord (0 = never met). */
    public static int relation(ServerLevel level, Settlement settlement) {
        return RelationSavedData.get(level.getServer()).relation(settlement.id, lordId(settlement));
    }

    @Override
    public WorldEventType type() {
        return WorldEventType.RIVAL_ENVOY;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return settlement.population() >= 4;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 4, 8, 1.4F, 2.0F, level.random);
        if (spot == null) return false;
        SettlerEntity envoy = WorldEventActors.spawnVisitor(level, settlement, active, spot, ROLE_ENVOY, 1.0D);
        if (envoy == null) return false;
        // A herald's fanfare announces the rival lord's envoy (sound pass).
        level.playSound(null, spot, com.hearthstead.registry.ModSounds.EVENT_ENVOY_FANFARE.get(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 2.0F, 1.0F);
        envoy.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", "Envoy of " + lordName(settlement));
        envoy.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.WRITABLE_BOOK));
        envoy.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
        BlockPos side = WorldEventCreatures.nearFeet(level, spot.offset(1, 0, 1), 0.6F, 1.95F);
        if (side != null) {
            SettlerEntity attendant = WorldEventActors.spawnVisitor(level, settlement, active, side, ROLE_ATTENDANT, 1.0D);
            if (attendant != null) {
                attendant.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHITE_BANNER));
                attendant.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
            }
        }
        WorldEventActors.markWaiting(envoy, Component.translatableWithFallback("hearthstead.event.envoy.who", "envoy"), true);
        active.state.putLong("Spot", spot.asLong());
        holdRoles(level, active);
        WorldEventConversations.bindAs(envoy, settlement, WorldEventConversations.RIVAL_ENVOY, lordProfile(settlement),
            Map.of("coins", PACT_COINS, "gift", GIFT_FOOD));
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.envoy.arrive",
                "An envoy of %s has come to the Banner under a white banner.", lordName(settlement)),
            Component.translatableWithFallback("hearthstead.event.envoy.cta",
                "Talk to them: negotiate, befriend or insult. The lord will remember."), spot);
        return true;
    }

    private static void holdRoles(ServerLevel level, WorldEventSavedData.Active active) {
        BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
        boolean leaving = active.state.getBoolean("Leaving");
        BlockPos target = leaving ? BlockPos.of(active.state.getLong("LeavePos")) : spot;
        int i = 0;
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (actor instanceof SettlerEntity settler) {
                WorldEventDirector.assign(settler, new WorldEventDirector.Role(
                    leaving ? WorldEventDirector.RoleKind.WALK_OFF : WorldEventDirector.RoleKind.HOLD,
                    active.id, null, leaving ? target : target.offset(i, 0, i), level.getGameTime() + 60, null, 0.6D));
                i++;
            }
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        holdRoles(level, active);
        if (active.state.getBoolean("Leaving") && (active.eligibleTicks - active.state.getInt("LeaveAt") >= 300
            || WorldEventActors.actors(level, active).isEmpty())) {
            WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
        }
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (!WorldEventVisitors.answered(active)) {
            RelationSavedData.get(level.getServer()).change(settlement.id, lordProfile(settlement), -5, 0,
                "hearthstead.event.envoy.memory.ignored", level.getGameTime());
            WorldEventDirector.notice(level, settlement, Component.translatableWithFallback("hearthstead.event.envoy.ignored",
                "Nobody received the envoy. They ride home to %s, unimpressed.", lordName(settlement)));
        }
        WorldEventDirector.finish(level, settlement, "ignored", null);
    }

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Leaving") && ROLE_ENVOY.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(), lordId(settlement), "rival_lord",
            Component.translatableWithFallback("hearthstead.event.envoy.title", "Envoy of %s", lordName(settlement)),
            Component.literal(WorldEventActors.plainName(actor)),
            List.of(Component.translatableWithFallback("conversation.hearthstead.rival_envoy.line1",
                    "\"My lord watches your village grow. He wonders whether you will be a friend or a problem.\""),
                Component.translatableWithFallback("hearthstead.event.envoy.standing",
                    "Standing with the lord: %s", relation(level, settlement))),
            List.of(), "ignored", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("negotiate", Component.translatableWithFallback(
                "conversation.hearthstead.rival_envoy.negotiate", "Propose a trade pact"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.PRIMARY).persuade(new WorldEventVisitors.Persuasion(PACT_CHANCE, "pact", "pact_failed")),
            WorldEventVisitors.Option.of("befriend", Component.translatableWithFallback(
                "conversation.hearthstead.rival_envoy.befriend", "Send a gift to the lord"),
                WorldEventVisitors.Cost.food(GIFT_FOOD), WorldEventVisitors.OptionStyle.SECONDARY).withRelation(20),
            WorldEventVisitors.Option.of("insult", Component.translatableWithFallback(
                "conversation.hearthstead.rival_envoy.insult", "Insult the envoy"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-25),
            WorldEventVisitors.Option.of("dismiss", Component.translatableWithFallback(
                "conversation.hearthstead.rival_envoy.dismiss", "Thank them and send them home"),
                WorldEventVisitors.Cost.FREE, WorldEventVisitors.OptionStyle.SECONDARY));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        WorldEventConversations.unbind(actor, null);
        // The talk UI applies the relation itself; the chat fallback records it here.
        if (!active.state.getBoolean("ViaTalk") && RELATION.containsKey(optionId)) {
            RelationSavedData.get(level.getServer()).change(settlement.id, lordProfile(settlement),
                RELATION.get(optionId), 0, "hearthstead.event.envoy.memory." + optionId, level.getGameTime());
        }
        Component outcome = switch (optionId) {
            case "pact" -> {
                give(level, player, new ItemStack(ModItems.GOLD_COIN.get(), PACT_COINS));
                yield Component.translatableWithFallback("hearthstead.event.envoy.pact",
                    "A trade pact! The envoy pays %s Coins for the right to trade here.", PACT_COINS)
                    .withStyle(ChatFormatting.GREEN);
            }
            case "pact_failed" -> Component.translatableWithFallback("hearthstead.event.envoy.pact_failed",
                "The envoy smiles thinly: \"My lord will consider it.\" He will not.");
            case "befriend" -> {
                give(level, player, new ItemStack(Items.GOLD_INGOT, 2));
                yield Component.translatableWithFallback("hearthstead.event.envoy.befriend",
                    "The envoy bows deeply and leaves a token of friendship: 2 gold ingots.").withStyle(ChatFormatting.GREEN);
            }
            case "insult" -> {
                for (SettlerEntity settler : WorldEventActors.members(level, settlement, s -> true)) settler.addMorale(2.0F);
                yield Component.translatableWithFallback("hearthstead.event.envoy.insult",
                    "The envoy goes white and rides off. Your people cheer, but %s will remember this.", lordName(settlement))
                    .withStyle(ChatFormatting.RED);
            }
            default -> Component.translatableWithFallback("hearthstead.event.envoy.dismiss",
                "The envoy bows and rides home.");
        };
        leave(level, settlement, active, optionId);
        return outcome;
    }

    private static void give(ServerLevel level, ServerPlayer player, ItemStack stack) {
        // BH-28: Inventory.add returns true on a PARTIAL add and the rest was
        // lost; placeItemBackInInventory drops whatever does not fit.
        player.getInventory().placeItemBackInInventory(stack.copy());
    }

    private static void leave(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", outcome);
        BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
        double dx = spot.getX() - settlement.center.getX(), dz = spot.getZ() - settlement.center.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        active.state.putLong("LeavePos", BlockPos.containing(settlement.center.getX() + dx / len * (settlement.radius + 24),
            spot.getY(), settlement.center.getZ() + dz / len * (settlement.radius + 24)).asLong());
        for (Entity actor : WorldEventActors.actors(level, active)) {
            WorldEventActors.markWaiting(actor, Component.translatableWithFallback("hearthstead.event.envoy.who", "envoy"), false);
            actor.setCustomNameVisible(false);
        }
        holdRoles(level, active);
        WorldEventDirector.finish(level, settlement, outcome, null); // they ride out; never a puff
    }
}
