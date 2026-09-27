package com.hearthstead.settlement.guildmaster;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.conversation.Conversation;
import com.hearthstead.conversation.ConversationActions;
import com.hearthstead.conversation.ConversationContext;
import com.hearthstead.conversation.ConversationGraphs;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.conversation.SpeakerProfile;
import com.hearthstead.entity.GuildmasterEntity;
import com.hearthstead.heraldry.BannerDesignNetwork;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Guildmaster's welcome (owner, 27 Sep): after a player raises the banner
 * of a new kingdom, the first thing that happens is a short talk with the
 * Guildmaster, who puts {@link #COINS} Coins in the Banner's stores (once per
 * settlement) and hands every player an iron tool set (once per player UUID
 * per settlement) together with {@link #BREAD} bread (owner, 27 Sep: the bread
 * moved here from the first-join kit).
 *
 * <h2>Triggers</h2>
 * <ul>
 *   <li>A successful "Raise banner" in the designer ({@link #afterRaise}).</li>
 *   <li>Right-clicking the Guildmaster before your own welcome
 *       ({@link #onInteract}); afterwards a right-click opens the emblems.</li>
 *   <li>Walking within {@link #NEAR_RADIUS} blocks of a Guildmaster whose
 *       settlement has already been welcomed (the co-op partner who joins
 *       later), checked once a second for real players only.</li>
 * </ul>
 *
 * <h2>Exactly once</h2>
 * The gift itself ({@link #grant}) is a server-side check-and-set on
 * {@link GuildmasterWelcomeData} and is delivered at the moment it is claimed,
 * before any conversation opens, so a talk that fails to open, a closed
 * screen, a relog or a restart can never lose or repeat it. The welcome
 * graph's replies also run the idempotent {@code gm_welcome.gift} action, so a
 * shared (co-op) session gives each participant their tools too. Coins go
 * into the Banner inventory (the treasury purchases read); a full Banner drops
 * the rest beside it. Tools go to the player's inventory; overflow drops at
 * their feet. Nothing is ever deleted.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GuildmasterWelcome {
    /** Coins the guild gives each new banner (owner: "4 coins", once per settlement). */
    public static final int COINS = 4;
    /** Bread each player gets with their tools (owner: "16 bread from the Guildmaster"). */
    public static final int BREAD = 16;
    public static final String GRAPH = "hearthstead:guildmaster_welcome";
    /** The co-op partner's welcome: coins already given, tools only. */
    public static final String GRAPH_TOOLS = "hearthstead:guildmaster_welcome_tools";
    public static final String ACTION_GIFT = "gm_welcome.gift";
    public static final String ACTION_EMBLEMS = "gm_welcome.emblems";
    /** A real player this close to a welcomed Guildmaster gets their own welcome. */
    static final double NEAR_RADIUS = 4.0D;
    /** How long a welcome waits for the Guildmaster to be seated or free, in ticks. */
    static final int WAIT_TICKS = 300;
    /** Delay before opening the emblems after the talk closes, in ticks. */
    static final int EMBLEMS_DELAY = 3;
    private static final String K = "conversation.hearthstead.gm_welcome.";

    /** What {@link #grant} newly delivered. */
    public record Grant(boolean coins, boolean tools) {
        public boolean any() {
            return coins || tools;
        }
    }

    private record Pending(UUID settlement, boolean coins, long deadline) {
    }

    /** player -> welcome talk still to open (Guildmaster not seated yet, or busy). */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** player -> game time at which to open the emblems (after "Show me the emblems"). */
    private static final Map<UUID, Long> EMBLEMS = new HashMap<>();
    private static boolean bootstrapped;

    private GuildmasterWelcome() {
    }

    /** Each player's gift: the iron tool set, one of each, and {@link #BREAD} bread. */
    public static List<ItemStack> toolSet() {
        return List.of(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_AXE),
            new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_HOE), new ItemStack(Items.BREAD, BREAD));
    }

    // -------------------------------------------------------------- gift ---

    /**
     * The idempotent gift: Coins once per settlement (only while its Banner is
     * loaded, so they always land physically), tools once per player. Safe to
     * call any number of times from any trigger.
     */
    public static Grant grant(ServerLevel level, @Nullable Settlement settlement, @Nullable ServerPlayer player) {
        if (level == null || settlement == null) {
            return new Grant(false, false);
        }
        GuildmasterWelcomeData data = GuildmasterWelcomeData.get(level);
        boolean coins = false;
        HearthBlockEntity banner = banner(level, settlement);
        if (banner != null && !data.coinsGiven(settlement.id) && data.claimCoins(settlement.id)) {
            ItemStack rest = banner.insertGoods(new ItemStack(ModItems.GOLD_COIN.get(), COINS));
            banner.noteContentsChanged();
            if (!rest.isEmpty()) {
                // A full Banner never loses the gift: it lands beside the stand.
                Containers.dropItemStack(level, settlement.center.getX() + 0.5D,
                    settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D, rest);
            }
            coins = true;
        }
        boolean tools = false;
        if (player != null && !(player instanceof FakePlayer) && !player.isSpectator() && player.isAlive()
            && player.serverLevel() == level && data.claimTools(settlement.id, player.getUUID())) {
            for (ItemStack tool : toolSet()) {
                // Fills the inventory and drops whatever does not fit at the player's feet.
                player.getInventory().placeItemBackInInventory(tool);
            }
            player.inventoryMenu.broadcastChanges();
            tools = true;
        }
        return new Grant(coins, tools);
    }

    /** Whether this player still has a welcome waiting at this settlement. */
    public static boolean owed(ServerLevel level, @Nullable Settlement settlement, @Nullable ServerPlayer player) {
        if (level == null || settlement == null || player == null) {
            return false;
        }
        GuildmasterWelcomeData data = GuildmasterWelcomeData.get(level);
        return !data.toolsGiven(settlement.id, player.getUUID()) || !data.coinsGiven(settlement.id);
    }

    // ---------------------------------------------------------- triggers ---

    /** After a successful "Raise banner": the Guildmaster welcomes the kingdom. */
    public static void afterRaise(ServerPlayer player, @Nullable Settlement settlement) {
        if (player == null || settlement == null) {
            return;
        }
        try {
            welcome(player, settlement, null);
        } catch (RuntimeException failure) {
            // Never let the welcome break the designer confirm.
            Hearthstead.LOGGER.error("Guildmaster welcome after raise failed", failure);
        }
    }

    /**
     * Right-click on the Guildmaster. Returns true when the welcome handled
     * the click (this player's first talk); false to open the emblems as usual.
     */
    public static boolean onInteract(ServerPlayer player, GuildmasterEntity guildmaster) {
        if (player == null || guildmaster == null || player.isSpectator()
            || !(player.level() instanceof ServerLevel level) || guildmaster.level() != level
            || guildmaster.settlementId() == null) {
            return false;
        }
        Settlement settlement = SettlementManager.byId(level, guildmaster.settlementId());
        if (settlement == null || !owed(level, settlement, player)
            || player.distanceToSqr(guildmaster) > GuildmasterTrade.REACH_SQUARED) {
            return false;
        }
        return welcome(player, settlement, guildmaster);
    }

    /**
     * Gives what is owed and opens the welcome talk (now, or as soon as the
     * Guildmaster is seated and free). Returns true when anything was given.
     */
    static boolean welcome(ServerPlayer player, Settlement settlement, @Nullable GuildmasterEntity known) {
        ServerLevel level = player.serverLevel();
        Grant grant = grant(level, settlement, player);
        if (!grant.any()) {
            return false;
        }
        player.sendSystemMessage(Component.translatable(grant.coins() && grant.tools() ? K + "chat.both"
            : grant.coins() ? K + "chat.coins" : K + "chat.tools", COINS, kingdomName(settlement), BREAD));
        GuildmasterEntity guildmaster = known != null ? known : GuildmasterService.ensure(level, settlement);
        if (!openTalk(player, settlement, guildmaster, grant.coins())) {
            PENDING.put(player.getUUID(), new Pending(settlement.id, grant.coins(),
                level.getGameTime() + WAIT_TICKS));
        }
        return true;
    }

    private static boolean openTalk(ServerPlayer player, Settlement settlement,
                                    @Nullable GuildmasterEntity guildmaster, boolean coins) {
        if (guildmaster == null || !guildmaster.isAlive() || guildmaster.level() != player.level()
            || ConversationService.isTalking(guildmaster) || ConversationService.isTalking(player)) {
            return false;
        }
        bootstrap();
        guildmaster.greet(player);
        SpeakerProfile profile = new SpeakerProfile(guildmaster.getUUID(), "", K + "title", "guildmaster");
        boolean opened = ConversationService.open(player, guildmaster, coins ? GRAPH : GRAPH_TOOLS, profile,
            Map.of("coins", COINS));
        if (opened) {
            JourneyServerHooks.noteGuildmasterMet(player, settlement, guildmaster.getUUID());
        }
        return opened;
    }

    // ------------------------------------------------------------ ticking ---

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        if (!EMBLEMS.isEmpty()) {
            Iterator<Map.Entry<UUID, Long>> it = EMBLEMS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> e = it.next();
                ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                if (player == null) {
                    it.remove();
                } else if (player.serverLevel().getGameTime() >= e.getValue()) {
                    it.remove();
                    openEmblems(player);
                }
            }
        }
        if (PENDING.isEmpty() || tick % 10L != 0L) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending pending = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || player.serverLevel().getGameTime() > pending.deadline()) {
                it.remove(); // the gift was already delivered; only the talk is skipped
                continue;
            }
            ServerLevel level = player.serverLevel();
            Settlement settlement = SettlementManager.byId(level, pending.settlement());
            if (settlement == null) {
                it.remove();
                continue;
            }
            if (BannerDesignNetwork.offerPending(player.getUUID())) {
                continue; // never cover the designer
            }
            GuildmasterEntity guildmaster = GuildmasterService.ensure(level, settlement);
            if (guildmaster != null && player.distanceTo(guildmaster) <= ConversationService.OPEN_REACH
                && openTalk(player, settlement, guildmaster, pending.coins())) {
                it.remove();
            }
        }
    }

    /** The co-op partner (or anyone) who walks up to a welcomed Guildmaster gets their own welcome. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
            || player.isSpectator() || !player.isAlive() || (player.tickCount + 11) % 20 != 0
            || PENDING.containsKey(player.getUUID())) {
            return;
        }
        ServerLevel level = player.serverLevel();
        List<GuildmasterEntity> near = level.getEntitiesOfClass(GuildmasterEntity.class,
            player.getBoundingBox().inflate(NEAR_RADIUS), g -> g.isAlive() && g.settlementId() != null);
        if (near.isEmpty()) {
            return;
        }
        GuildmasterEntity guildmaster = near.get(0);
        Settlement settlement = SettlementManager.byId(level, guildmaster.settlementId());
        GuildmasterWelcomeData data = GuildmasterWelcomeData.get(level);
        // Only once the kingdom itself has been welcomed (its banner raised): a
        // founder who is still designing is welcomed by the Raise itself.
        if (settlement == null || !data.coinsGiven(settlement.id)
            || data.toolsGiven(settlement.id, player.getUUID())
            || BannerDesignNetwork.offerPending(player.getUUID())
            || BannerDesignNetwork.sessionOf(player.getUUID()) != null
            || ConversationService.isTalking(player)
            // He is mid-talk (maybe a shared one this player can join): wait until he is free.
            || ConversationService.isTalking(guildmaster)) {
            return;
        }
        welcome(player, settlement, guildmaster);
    }

    // ------------------------------------------------------ conversation ---

    /** Registers the welcome graphs and actions (idempotent). */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        ConversationActions.register(ACTION_GIFT, GuildmasterWelcome::giftFromTalk);
        ConversationActions.register(ACTION_EMBLEMS, ctx -> {
            giftFromTalk(ctx);
            EMBLEMS.put(ctx.player().getUUID(), ctx.player().serverLevel().getGameTime() + EMBLEMS_DELAY);
        });
        registerGraph(GRAPH, K + "line", K + "line2");
        registerGraph(GRAPH_TOOLS, K + "line_tools", K + "line_tools2");
        // Co-op: a partner who joins the shared welcome talk gets their own gift at once.
        ConversationService.onJoin((level, npcUuid, player, graphId) -> {
            if (!GRAPH.equals(graphId) && !GRAPH_TOOLS.equals(graphId)) {
                return;
            }
            if (!(level.getEntity(npcUuid) instanceof GuildmasterEntity g) || g.settlementId() == null) {
                return;
            }
            Settlement settlement = SettlementManager.byId(level, g.settlementId());
            if (settlement == null) {
                return;
            }
            Grant grant = grant(level, settlement, player);
            if (grant.any()) {
                player.sendSystemMessage(Component.translatable(grant.coins() && grant.tools() ? K + "chat.both"
                    : grant.coins() ? K + "chat.coins" : K + "chat.tools", COINS, kingdomName(settlement), BREAD));
            }
        });
    }

    private static void registerGraph(String id, String greeting, String gift) {
        ConversationGraphs.register(Conversation.graph(id).noEncounter()
            .node("start", n -> n.line(greeting).line(gift).line(K + "line_end")
                .option("thanks", o -> o.text(K + "thanks").action(ACTION_GIFT).reply(K + "reply").end())
                .option("emblems", o -> o.text(K + "emblems").action(ACTION_EMBLEMS).end()))
            .build());
    }

    /** Reply action: the same idempotent gift for whoever chose (and so for each player in a shared talk). */
    private static void giftFromTalk(ConversationContext ctx) {
        // His own Banner first; the talk's settlement only as a fallback.
        Settlement settlement = ctx.speaker() instanceof GuildmasterEntity g && g.settlementId() != null
            ? SettlementManager.byId(ctx.player().serverLevel(), g.settlementId()) : null;
        if (settlement == null) {
            settlement = ctx.settlement();
        }
        // Once per reply, for everyone in the (co-op) talk: Coins once per settlement, gifts per player.
        for (ServerPlayer participant : ctx.participants()) {
            grant(ctx.player().serverLevel(), settlement, participant);
        }
    }

    private static void openEmblems(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<GuildmasterEntity> near = level.getEntitiesOfClass(GuildmasterEntity.class,
            player.getBoundingBox().inflate(ConversationService.OPEN_REACH), GuildmasterEntity::isAlive);
        GuildmasterEntity nearest = null;
        for (GuildmasterEntity g : near) {
            if (nearest == null || player.distanceToSqr(g) < player.distanceToSqr(nearest)) {
                nearest = g;
            }
        }
        if (nearest != null && !ConversationService.isTalking(player)) {
            GuildmasterService.openTrade(player, nearest);
        }
    }

    // ------------------------------------------------------------ helpers ---

    @Nullable
    private static HearthBlockEntity banner(ServerLevel level, Settlement settlement) {
        return level.isLoaded(settlement.center)
            && level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth
            && settlement.id.equals(hearth.getSettlementId()) ? hearth : null;
    }

    private static String kingdomName(Settlement settlement) {
        return settlement.name == null ? "" : settlement.name;
    }

    /** GameTest seam: forget queued talks and emblem openings (never the saved gift record). */
    static void resetTransientForTest() {
        PENDING.clear();
        EMBLEMS.clear();
    }

    /** GameTest seam: whether a welcome talk is still waiting for this player. */
    static boolean talkPending(UUID player) {
        return PENDING.containsKey(player);
    }
}
