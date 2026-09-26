package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.parley.RaidParley;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.mojang.brigadier.CommandDispatcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * QA / film helpers for conversations (op 2):
 * <pre>
 *   /hstalk traveller   spawn a traveller bound to the JSON graph hearthstead:traveller
 *   /hstalk peddler     spawn a demo peddler whose "wares" reply opens the barter table
 *   /hstalk raid        start a recurring raid at the nearest settlement and halt it for a parley
 *   /hstalk relation    list what the nearest settlement's people think of it
 * </pre>
 * The raid helper completes a missing first raid on the settlement first
 * (QA worlds only).
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ConversationCommand {
    private static final String DEMO_PEDDLER = "hearthstead:demo_peddler";
    private static final Map<UUID, ListBarterStock> DEMO_STOCKS = new HashMap<>();
    private static boolean registered;

    private ConversationCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hstalk").requires(src -> src.hasPermission(2))
            .then(Commands.literal("traveller").executes(ctx -> traveller(ctx.getSource())))
            .then(Commands.literal("peddler").executes(ctx -> peddler(ctx.getSource())))
            .then(Commands.literal("raid").executes(ctx -> raid(ctx.getSource())))
            .then(Commands.literal("relation").executes(ctx -> relation(ctx.getSource()))));
    }

    private static Vec3 inFront(ServerPlayer player, double distance) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z).normalize();
        return player.position().add(flat.scale(distance));
    }

    private static int traveller(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Vec3 at = inFront(player, 9.0D);
        Villager villager = EntityType.VILLAGER.spawn(player.serverLevel(), net.minecraft.core.BlockPos.containing(at),
            MobSpawnType.COMMAND);
        if (villager == null) return 0;
        villager.lookAt(player, 180.0F, 180.0F);
        ConversationService.bind(villager, "hearthstead:traveller", new SpeakerProfile(UUID.nameUUIDFromBytes(
            "hearthstead:traveller:edda".getBytes()), "Edda", "conversation.hearthstead.title.traveller", "traveller"));
        source.sendSuccess(() -> Component.literal("Traveller Edda waits 9 blocks ahead. Walk up to her."), false);
        return 1;
    }

    private static void ensureDemoPeddler() {
        if (ConversationGraphs.get(DEMO_PEDDLER) != null) return;
        ConversationGraphs.register(Conversation.graph(DEMO_PEDDLER)
            .encounter(6, "eye", "conversation.hearthstead.demo_peddler.intro")
            .node("start", n -> n.line("conversation.hearthstead.demo_peddler.greet")
                .option("wares", o -> o.text("conversation.hearthstead.demo_peddler.wares").barter("demo.peddler").goTo("start"))
                .option("haggle", o -> o.text("conversation.hearthstead.demo_peddler.haggle").persuade(40, "trade")
                    .success(s -> s.relation(10).reply("conversation.hearthstead.demo_peddler.haggle_yes").goTo("start"))
                    .failure(f -> f.relation(-5).reply("conversation.hearthstead.demo_peddler.haggle_no").goTo("start")))
                .option("leave", o -> o.text("conversation.hearthstead.leave").end()))
            .build());
        ListBarterStock.register("demo.peddler", ctx -> DEMO_STOCKS.computeIfAbsent(ctx.speaker().getUUID(),
            id -> new ListBarterStock(List.of(new ItemStack(Items.LANTERN, 3), new ItemStack(Items.EMERALD, 4),
                new ItemStack(Items.IRON_PICKAXE, 1), new ItemStack(Items.BOOK, 4), new ItemStack(Items.STRING, 16),
                new ItemStack(Items.LEATHER, 8)))));
    }

    private static int peddler(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ensureDemoPeddler();
        Vec3 at = inFront(player, 7.0D);
        WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(player.serverLevel(),
            net.minecraft.core.BlockPos.containing(at), MobSpawnType.COMMAND);
        if (trader == null) return 0;
        trader.setDespawnDelay(0);
        ConversationService.bind(trader, DEMO_PEDDLER, new SpeakerProfile(UUID.nameUUIDFromBytes(
            "hearthstead:peddler:aldric".getBytes()), "Aldric", "conversation.hearthstead.title.peddler", "peddler"));
        source.sendSuccess(() -> Component.literal("Peddler Aldric has set up 7 blocks ahead."), false);
        return 1;
    }

    private static int raid(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Settlement settlement = SettlementManager.at(level, player.blockPosition());
        if (settlement == null) {
            source.sendFailure(Component.literal("Stand inside a settlement."));
            return 0;
        }
        if (!settlement.recurringRaidRun.isActive()) {
            if (settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED) {
                RaidLifecycle lifecycle = new RaidLifecycle();
                RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
                UUID participant = UUID.randomUUID();
                if (!(lifecycle.initializeAtFounding(0L, 4, 2) && lifecycle.queueFirstPlan(first)
                    && lifecycle.beginFirstRaid(first) && lifecycle.recordParticipant(participant)
                    && lifecycle.sealParticipants() && lifecycle.recordTerminalParticipant(participant)
                    && lifecycle.completeFirstRaid(false))) {
                    source.sendFailure(Component.literal("Could not complete the first raid fixture."));
                    return 0;
                }
                settlement.raidLifecycle = lifecycle;
                // QA worlds only: the recurring cadence waits for the Journey to author the first-raid chapter;
                // a skipped Journey (as in a fresh test settlement) lets the fixture stand in for it.
                settlement.journeyState = com.hearthstead.settlement.journey.JourneyState.skipped(settlement.id);
            }
            if (!settlement.recurringRaidRun.isQueued()) {
                RaidCaptain captain = RaidDirector.pickCaptain(settlement, level.getRandom());
                RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, RaidDirector.nightOf(level.getDayTime()));
                if (!settlement.recurringRaidRun.queue(plan)) {
                    source.sendFailure(Component.literal("Raid queue refused: " + settlement.recurringRaidRun.stage()));
                    return 0;
                }
            }
            List<RaiderEntity> band = RaidDirector.startQueuedRecurringRaid(level, settlement);
            SettlementSavedData.get(level).setDirty();
            if (band.isEmpty()) {
                source.sendFailure(Component.literal("The band could not spawn (queue refused the start, or no footing outside the claim)."));
                return 0;
            }
        }
        boolean started = RaidParley.tryStart(level, settlement) || RaidParley.holding(settlement.id);
        source.sendSuccess(() -> Component.literal(started ? "The raid captain halts for a parley."
            : "No parley possible (config off, captain inside the claim, or already parleyed)."), false);
        return started ? 1 : 0;
    }

    private static int relation(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Settlement settlement = SettlementManager.at(player.serverLevel(), player.blockPosition());
        UUID id = settlement == null ? null : settlement.id;
        RelationSavedData data = RelationSavedData.get(player.server);
        source.sendSuccess(() -> Component.literal("Reputation with raiders: " + data.reputation(id, "raider")
            + ", peddlers: " + data.reputation(id, "peddler") + ", travellers: " + data.reputation(id, "traveller")), false);
        return 1;
    }
}
