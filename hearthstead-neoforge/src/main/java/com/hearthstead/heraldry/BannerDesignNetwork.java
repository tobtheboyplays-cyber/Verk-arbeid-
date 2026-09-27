package com.hearthstead.heraldry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementBannerInteraction;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.ModBusEvents;
import com.hearthstead.network.PayloadSend;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.gear.GearGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for the Banner designer.
 *
 * <p>Placing a Banner offers its placer the designer (a placer session;
 * founding, the raise sound and the Blessing are untouched). A member at the
 * Banner can reopen it later from the Banner screen's Heraldry button. A
 * confirm is checked for: a live, non-spectator player in reach
 * ({@link #MAX_DISTANCE}) of a loaded Banner in the same level, build rights
 * there, settlement membership (or being the placer), a legal design and the
 * per-player rate. Two editors: the last valid confirm wins and every other
 * open designer on that Banner is refreshed with who changed it.
 *
 * <p>A confirm only writes {@link HearthBlockEntity#setDesign}: it never
 * creates, removes or moves a banner item.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BannerDesignNetwork {
    public static final double MAX_DISTANCE = 8.0D;
    public static final int CONFIRM_COOLDOWN_TICKS = 10;
    public static final int OPEN_COOLDOWN_TICKS = 4;

    /** Who has a designer open (or was offered one) on which Banner. */
    public record Session(BlockPos pos, ResourceKey<Level> dimension, boolean placer) {
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_CONFIRM = new HashMap<>();
    private static final Map<UUID, Long> LAST_OPEN = new HashMap<>();
    /** Other editors told to refresh by the last confirm (diagnostics and GameTests). */
    private static final java.util.List<UUID> LAST_REFRESHED = new java.util.ArrayList<>();

    public enum Result { SAVED, UNCHANGED, NO_BANNER, TOO_FAR, DENIED, INVALID, RATE_LIMITED }

    private BannerDesignNetwork() {
    }

    private static void clientOnly(java.util.function.Supplier<Runnable> action) {
        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            action.get().run();
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(BannerDesignPayloads.Action.TYPE, BannerDesignPayloads.Action.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    handle(player, payload);
                }
            }));
        registrar.playToClient(BannerDesignPayloads.Open.TYPE, BannerDesignPayloads.Open.CODEC,
            (payload, context) -> context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.heraldry.BannerDesignerClient.open(payload))));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        SESSIONS.remove(id);
        LAST_CONFIRM.remove(id);
        LAST_OPEN.remove(id);
        LAST_RENAME.remove(id);
        PENDING_OFFERS.remove(id);
    }

    // ------------------------------------------------------------ entry ---

    public static void handle(ServerPlayer player, BannerDesignPayloads.Action action) {
        switch (action.action()) {
            case BannerDesignPayloads.Action.OPEN -> requestOpen(player, action.pos());
            case BannerDesignPayloads.Action.CONFIRM -> confirm(player, action.pos(), action.design(), action.name());
            case BannerDesignPayloads.Action.CLOSE -> close(player, action.pos());
            default -> {
            }
        }
    }

    /**
     * Right after a player places a Banner: offer them the designer. The
     * screen opens once the Banner has founded its settlement (its next
     * once-a-second tick), so the name field shows the kingdom's real name;
     * if founding cannot happen, it opens after {@link #OFFER_WAIT_TICKS}
     * without a name.
     */
    public static void offerOnPlacement(ServerPlayer player, HearthBlockEntity hearth) {
        SESSIONS.put(player.getUUID(), new Session(hearth.getBlockPos(), player.level().dimension(), true));
        PENDING_OFFERS.put(player.getUUID(), new PendingOffer(hearth.getBlockPos(), player.level().dimension(),
            player.serverLevel().getGameTime() + OFFER_WAIT_TICKS));
    }

    public static final int OFFER_WAIT_TICKS = 50;

    private record PendingOffer(BlockPos pos, ResourceKey<Level> dimension, long deadline) {
    }

    private static final Map<UUID, PendingOffer> PENDING_OFFERS = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (PENDING_OFFERS.isEmpty()) {
            return;
        }
        var it = PENDING_OFFERS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            PendingOffer offer = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.level().dimension().equals(offer.dimension())
                || !player.serverLevel().isLoaded(offer.pos())
                || !(player.serverLevel().getBlockEntity(offer.pos()) instanceof HearthBlockEntity hearth)) {
                it.remove();
                continue;
            }
            if (hearth.getSettlementId() == null && player.serverLevel().getGameTime() < offer.deadline()) {
                continue;
            }
            it.remove();
            PayloadSend.toPlayer(player, new BannerDesignPayloads.Open(offer.pos(),
                BannerDesignPayloads.Open.PLACED, hearth.effectiveDesign(), "", nameOf(player.serverLevel(), hearth)));
        }
    }

    /** GameTest seam: the designer offer still waiting for founding, if any. */
    public static boolean offerPending(UUID player) {
        return PENDING_OFFERS.containsKey(player);
    }

    private static String nameOf(ServerLevel level, HearthBlockEntity hearth) {
        Settlement settlement = settlementOf(level, hearth);
        return settlement == null || settlement.name == null ? "" : settlement.name;
    }

    /** The Heraldry button: open the designer for a member at the Banner. */
    public static boolean requestOpen(ServerPlayer player, BlockPos pos) {
        long now = player.serverLevel().getGameTime();
        Long last = LAST_OPEN.get(player.getUUID());
        if (last != null && now - last < OPEN_COOLDOWN_TICKS && now >= last) {
            return false;
        }
        LAST_OPEN.put(player.getUUID(), now);
        HearthBlockEntity hearth = banner(player, pos);
        if (hearth == null || !inReach(player, pos)) {
            return false;
        }
        if (!mayEdit(player, hearth)) {
            player.displayClientMessage(Component.translatable("hearthstead.heraldry.denied"), true);
            return false;
        }
        Session previous = SESSIONS.get(player.getUUID());
        boolean placer = previous != null && previous.placer() && previous.pos().equals(pos);
        SESSIONS.put(player.getUUID(), new Session(pos, player.level().dimension(), placer));
        PayloadSend.toPlayer(player, new BannerDesignPayloads.Open(pos, BannerDesignPayloads.Open.EDIT,
            hearth.effectiveDesign(), "", nameOf(player.serverLevel(), hearth)));
        return true;
    }

    public static void close(ServerPlayer player, BlockPos pos) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.pos().equals(pos) && !session.placer()) {
            SESSIONS.remove(player.getUUID());
        }
        // A placer keeps the placer right: cancelling the first offer must
        // not lock them out of designing before they ever used the Banner.
    }

    /** Validates and saves a design (no rename). Server only. */
    public static Result confirm(ServerPlayer player, BlockPos pos, VillageDesign design) {
        return confirm(player, pos, design, "");
    }

    /**
     * Validates and saves a design and, when {@code requestedName} is not
     * empty and differs from the current name, renames the kingdom. A name
     * that is refused (shape, taken, rename cooldown) is reported and left
     * unchanged; the design is still raised. Server only.
     */
    public static Result confirm(ServerPlayer player, BlockPos pos, VillageDesign design, String requestedName) {
        lastNameProblem = null;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Long last = LAST_CONFIRM.get(player.getUUID());
        if (last != null && now - last < CONFIRM_COOLDOWN_TICKS && now >= last) {
            return Result.RATE_LIMITED;
        }
        LAST_CONFIRM.put(player.getUUID(), now);
        HearthBlockEntity hearth = banner(player, pos);
        if (hearth == null) {
            return Result.NO_BANNER;
        }
        if (!inReach(player, pos)) {
            player.displayClientMessage(Component.translatable("hearthstead.heraldry.too_far"), true);
            return Result.TOO_FAR;
        }
        if (!mayEdit(player, hearth)) {
            player.displayClientMessage(Component.translatable("hearthstead.heraldry.denied"), true);
            return Result.DENIED;
        }
        if (design == null || design == VillageDesign.INVALID
            || VillageDesign.validate(design, id -> level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN)
                .containsKey(id)) != VillageDesign.Problem.NONE) {
            player.displayClientMessage(Component.translatable("hearthstead.heraldry.invalid"), true);
            return Result.INVALID;
        }
        Settlement settlement = settlementOf(level, hearth);
        if (settlement == null) {
            // A confirm is a use of the Banner, like opening it: found now if it can.
            hearth.foundNow(level);
            settlement = settlementOf(level, hearth);
        }
        if (settlement != null && settlement.addMember(player.getUUID())) {
            SettlementSavedData.get(level).setDirty();
        }
        lastNameProblem = rename(player, settlement, requestedName);
        boolean changed = !design.equals(hearth.effectiveDesign()) || !hearth.hasSavedDesign();
        hearth.setDesign(design);
        SESSIONS.remove(player.getUUID());
        refreshWearers(level, hearth);
        level.playSound(null, pos.above(2), SettlementBannerInteraction.raiseSound(), SoundSource.BLOCKS,
            0.9F, 1.05F);
        player.displayClientMessage(Component.translatable("hearthstead.heraldry.raised"), true);
        String name = player.getGameProfile().getName();
        LAST_REFRESHED.clear();
        for (ServerPlayer other : level.players()) {
            Session session = SESSIONS.get(other.getUUID());
            if (other != player && session != null && session.pos().equals(pos)
                && session.dimension().equals(level.dimension())) {
                LAST_REFRESHED.add(other.getUUID());
                PayloadSend.toPlayer(other, new BannerDesignPayloads.Open(pos, BannerDesignPayloads.Open.REFRESH,
                    design, name, nameOf(level, hearth)));
            }
        }
        // Owner, 27 Sep: the first thing after raising the banner is the
        // Guildmaster's welcome (4 Coins once per kingdom, iron tools per player).
        com.hearthstead.settlement.guildmaster.GuildmasterWelcome.afterRaise(player, settlement);
        return changed ? Result.SAVED : Result.UNCHANGED;
    }

    // ------------------------------------------------------------ name ---

    public static final int RENAME_COOLDOWN_TICKS = 200;
    private static final Map<UUID, Long> LAST_RENAME = new HashMap<>();
    @Nullable
    private static KingdomName.Problem lastNameProblem;

    /** What the last confirm did with its name: null = no rename asked, NONE = renamed. */
    @Nullable
    public static KingdomName.Problem lastNameProblem() {
        return lastNameProblem;
    }

    @Nullable
    private static KingdomName.Problem rename(ServerPlayer player, @Nullable Settlement settlement, String requested) {
        String name = KingdomName.normalize(requested);
        if (settlement == null || name.isEmpty() || name.equals(settlement.name)) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Long last = LAST_RENAME.get(player.getUUID());
        if (last != null && now - last < RENAME_COOLDOWN_TICKS && now >= last) {
            player.displayClientMessage(Component.translatable("hearthstead.heraldry.name.wait"), false);
            return KingdomName.Problem.WAIT;
        }
        java.util.List<String> others = new java.util.ArrayList<>();
        for (ServerLevel any : level.getServer().getAllLevels()) {
            for (Settlement other : SettlementManager.data(any).settlements.values()) {
                if (!other.id.equals(settlement.id)) {
                    others.add(other.name);
                }
            }
        }
        KingdomName.Problem problem = KingdomName.validate(name, others);
        if (problem != KingdomName.Problem.NONE) {
            player.displayClientMessage(Component.translatable(KingdomName.messageKey(problem), name), false);
            return problem;
        }
        LAST_RENAME.put(player.getUUID(), now);
        settlement.name = name;
        SettlementSavedData.get(level).setDirty();
        player.displayClientMessage(Component.translatable("hearthstead.heraldry.name.renamed", name), false);
        return KingdomName.Problem.NONE;
    }

    // ---------------------------------------------------------- checks ---

    @Nullable
    private static HearthBlockEntity banner(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!player.isAlive() || player.isSpectator() || pos == null || !level.isLoaded(pos)
            || !(level.getBlockEntity(pos) instanceof HearthBlockEntity hearth)) {
            return null;
        }
        return hearth;
    }

    public static boolean inReach(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
            <= MAX_DISTANCE * MAX_DISTANCE;
    }

    /**
     * Build rights at the Banner, and either membership of its settlement or
     * the placer's own offer on this Banner (a fresh Banner may not be
     * founded yet, and its placer has not used it).
     */
    public static boolean mayEdit(ServerPlayer player, HearthBlockEntity hearth) {
        BlockPos pos = hearth.getBlockPos();
        if (!SettlementBannerInteraction.mayChangeColours(player, pos)) {
            return false;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.placer() && session.pos().equals(pos)
            && session.dimension().equals(player.level().dimension())) {
            return true;
        }
        Settlement settlement = settlementOf(player.serverLevel(), hearth);
        return settlement != null && settlement.members.contains(player.getUUID());
    }

    @Nullable
    private static Settlement settlementOf(ServerLevel level, HearthBlockEntity hearth) {
        return hearth.getSettlementId() == null ? null : SettlementManager.byId(level, hearth.getSettlementId());
    }

    /** Guards and archers of this Banner put the new colours on now, not at their next second. */
    public static int refreshWearers(ServerLevel level, HearthBlockEntity hearth) {
        BlockPos c = hearth.getBlockPos();
        Settlement settlement = settlementOf(level, hearth);
        int radius = settlement == null ? 32 : Math.max(32, settlement.radius + 16);
        List<SettlerEntity> settlers = level.getEntitiesOfClass(SettlerEntity.class, new AABB(c).inflate(radius),
            s -> s.isAlive() && s.hearth() == hearth);
        for (SettlerEntity settler : settlers) {
            settler.setGearProjection(settler.gearClearancePacked(), GearGate.heraldryPacked(settler));
        }
        return settlers.size();
    }

    /** GameTest and diagnostics seam: the session a player holds, if any. */
    @Nullable
    public static Session sessionOf(UUID player) {
        return SESSIONS.get(player);
    }

    /** Players whose open designer the last confirm refreshed. */
    public static List<UUID> lastRefreshed() {
        return List.copyOf(LAST_REFRESHED);
    }

    /** GameTest seam: forget only the design-confirm cooldown (the rename cooldown stays). */
    public static void resetConfirmCooldownForTest(UUID player) {
        LAST_CONFIRM.remove(player);
    }

    /** GameTest seam: forget rate limits and sessions of one player. */
    public static void resetForTest(UUID player) {
        SESSIONS.remove(player);
        LAST_CONFIRM.remove(player);
        LAST_OPEN.remove(player);
        LAST_RENAME.remove(player);
        PENDING_OFFERS.remove(player);
    }
}
