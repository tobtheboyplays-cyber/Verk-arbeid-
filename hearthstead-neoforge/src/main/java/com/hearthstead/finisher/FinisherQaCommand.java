package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Op-only QA/filming for executions:
 * <pre>
 *   /hsfinisher spawn skirmisher|brute|captain|goblin [blocks]   a frozen, finishable enemy in front of you
 *   /hsfinisher next &lt;variant_id&gt;                               your next solo execution plays that move
 *   /hsfinisher double [blocks]                                  a brute captain + a scripted partner who joins
 *   /hsfinisher open                                             re-open the nearest enemy's finish window
 *   /hsfinisher cleanup                                          remove scripted partners
 * </pre>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FinisherQaCommand {
    private static final List<ServerPlayer> PARTNERS = new ArrayList<>();

    private FinisherQaCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hsfinisher")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("spawn")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), 2.4D))
                    .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0D, 6.0D))
                        .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "kind"),
                            DoubleArgumentType.getDouble(ctx, "blocks"))))))
            .then(Commands.literal("next")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .executes(ctx -> next(ctx.getSource(), StringArgumentType.getString(ctx, "variant")))))
            .then(Commands.literal("double")
                .executes(ctx -> dbl(ctx.getSource(), 2.2D))
                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0D, 6.0D))
                    .executes(ctx -> dbl(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "blocks")))))
            .then(Commands.literal("reel").executes(ctx -> {
                int ticks = FinisherReel.start(ctx.getSource().getPlayerOrException());
                return ticks;
            }))
            .then(Commands.literal("open").executes(ctx -> open(ctx.getSource())))
            .then(Commands.literal("cleanup").executes(ctx -> cleanup(ctx.getSource()))));
    }

    private static RaiderEntity spawnEnemy(ServerPlayer player, String kind, double blocks) {
        ServerLevel level = player.serverLevel();
        RaiderEntity raider = ModEntities.RAIDER.get().create(level);
        if (raider == null) {
            return null;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        boolean goblin = k.equals("goblin");
        boolean captain = k.equals("captain") || k.equals("brute_captain");
        raider.setVariant(k.startsWith("brute") ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER);
        raider.assign(UUID.randomUUID(), UUID.randomUUID(), com.hearthstead.settlement.raid.RaidObjective.BLOD,
            1.0F, captain);
        if (goblin) {
            raider.setGoblinThiefPresentation(true, 0);
            raider.getAttribute(Attributes.SCALE).setBaseValue(0.65D);
        }
        Vec3 look = Vec3.directionFromRotation(0.0F, player.getYRot());
        Vec3 at = player.position().add(look.scale(blocks));
        float yaw = FinisherService.yawToward(look.reverse());
        raider.moveTo(at.x, player.getY(), at.z, yaw, 0.0F);
        raider.setYHeadRot(yaw);
        raider.setYBodyRot(yaw);
        raider.setNoAi(true);
        raider.setPersistenceRequired();
        // Owner rule: below 10% health AND off balance (forceOpenWindow knocks it off balance).
        raider.setHealth(raider.getMaxHealth() * 0.08F);
        level.addFreshEntity(raider);
        FinisherService.forceOpenWindow(raider);
        return raider;
    }

    private static int spawn(CommandSourceStack source, String kind, double blocks)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaiderEntity raider = spawnEnemy(player, kind, blocks);
        source.sendSuccess(() -> Component.literal(raider == null ? "spawn failed"
            : "Finishable " + kind + " spawned"), false);
        return raider == null ? 0 : 1;
    }

    private static int next(CommandSourceStack source, String id) {
        for (FinisherVariant v : FinisherVariant.values()) {
            if (v.id().equals(id) || v.name().equalsIgnoreCase(id)) {
                FinisherService.qaNextVariant = v;
                source.sendSuccess(() -> Component.literal("Next finisher: " + v.id()), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("Unknown finisher " + id));
        return 0;
    }

    private static int dbl(CommandSourceStack source, double blocks)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaiderEntity raider = spawnEnemy(player, "brute_captain", blocks);
        if (raider == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "Sir_Partner"), false);
        ServerPlayer partner = new ServerPlayer(level.getServer(), level, cookie.gameProfile(),
            cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, partner, cookie);
        partner.setGameMode(GameType.SURVIVAL);
        // In front of the captain, off to the executor's right, looking at it.
        Vec3 look = Vec3.directionFromRotation(0.0F, player.getYRot());
        Vec3 right = new Vec3(-look.z, 0.0D, look.x);
        Vec3 spot = raider.position().add(right.scale(1.9D)).add(look.scale(-0.6D));
        float yaw = FinisherService.yawToward(raider.position().subtract(spot));
        partner.teleportTo(level, spot.x, raider.getY(), spot.z, yaw, 5.0F);
        partner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        PARTNERS.add(partner);
        FinisherService.QA_AUTO_JOIN.put(raider.getUUID(), partner);
        source.sendSuccess(() -> Component.literal("Double ready: press the finish key on the captain"), false);
        return 1;
    }

    private static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaiderEntity nearest = player.serverLevel().getNearestEntity(RaiderEntity.class,
            net.minecraft.world.entity.ai.targeting.TargetingConditions.forNonCombat(), player,
            player.getX(), player.getY(), player.getZ(), player.getBoundingBox().inflate(8.0D));
        boolean ok = nearest != null && FinisherService.forceOpenWindow(nearest);
        source.sendSuccess(() -> Component.literal(ok ? "Window open" : "No window"), false);
        return ok ? 1 : 0;
    }

    private static int cleanup(CommandSourceStack source) {
        var list = source.getServer().getPlayerList();
        for (ServerPlayer p : PARTNERS) {
            if (list.getPlayer(p.getUUID()) == p) {
                list.remove(p);
            }
        }
        PARTNERS.clear();
        FinisherService.QA_AUTO_JOIN.clear();
        source.sendSuccess(() -> Component.literal("Finisher QA partners removed"), false);
        return 1;
    }
}
