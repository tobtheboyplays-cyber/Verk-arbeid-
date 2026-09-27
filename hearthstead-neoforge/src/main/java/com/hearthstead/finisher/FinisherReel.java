package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Filming only ({@code /hsfinisher reel}): a server-timed showcase so nothing
 * is typed while the camera records.
 *
 * <ol>
 *   <li>t=0: a finishable skirmisher glows in front of the filming player; the
 *       film script presses the real finish key (R) at ~1.2 s.</li>
 *   <li>t=4.5 s: the filming player becomes a spectator camera beside a stage
 *       and scripted knights ("Sir_*" mock players) execute an axe, a Brute, a
 *       goblin and a mace victim, then two of them do the co-op double on a
 *       Brute captain. Every execution goes through the normal
 *       {@link FinisherService#request} path.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FinisherReel {
    private record Step(long at, Runnable action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<ServerPlayer> ACTORS = new ArrayList<>();
    private static long clock = -1L;

    private FinisherReel() {
    }

    public static int start(ServerPlayer camera) {
        STEPS.clear();
        cleanup(camera.serverLevel());
        ServerLevel level = camera.serverLevel();
        Vec3 origin = camera.position();
        float yaw0 = camera.getYRot();
        Vec3 fwd = Vec3.directionFromRotation(0.0F, yaw0);
        Vec3 right = new Vec3(-fwd.z, 0.0D, fwd.x);
        clock = 0L;
        // 1) the player's own finisher (R pressed by the film script ~24 ticks in)
        FinisherService.qaNextVariant = FinisherVariant.SWORD_PARRY_THRUST;
        camera.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        camera.teleportTo(level, origin.x, origin.y, origin.z, EnumSet.noneOf(RelativeMovement.class),
            yaw0, 26.0F);
        spawnEnemy(level, origin, fwd, "skirmisher", 2.4D);
        // 2) the stage: 8 blocks ahead-right; the camera looks at it from the side
        // Close, on the same ground the player stands on, framed from the side.
        Vec3 stage = origin.add(fwd.scale(5.0D));
        Vec3 cam = stage.add(right.scale(-4.2D)).add(fwd.scale(1.1D)).add(0.0D, 1.1D, 0.0D);
        at(90, () -> {
            camera.setGameMode(GameType.SPECTATOR);
            float camYaw = FinisherService.yawToward(right);
            camera.teleportTo(level, cam.x, cam.y, cam.z, EnumSet.noneOf(RelativeMovement.class), camYaw, 12.0F);
        });
        ServerPlayer knight = actor(level, "Sir_Aldric", stage, yaw0);
        long t = 100;
        t = solo(level, knight, stage, fwd, yaw0, t, FinisherVariant.AXE_HOOK_CHOP, Items.IRON_AXE, "skirmisher");
        t = solo(level, knight, stage, fwd, yaw0, t, FinisherVariant.BRUTE_KNEE_BUCKLE, Items.IRON_SWORD, "brute");
        t = solo(level, knight, stage, fwd, yaw0, t, FinisherVariant.GOBLIN_SCRUFF_SLAM, Items.IRON_SWORD, "goblin");
        t = solo(level, knight, stage, fwd, yaw0, t, FinisherVariant.MACE_GUT_SLAM, Items.MACE, "skirmisher");
        // 3) co-op double: a second knight joins within half a second
        ServerPlayer partner = actor(level, "Sir_Bryn", stage.add(fwd.scale(2.2D)).add(right.scale(1.9D)), yaw0);
        final long td = t;
        at(td, () -> {
            place(level, knight, stage, yaw0);
            equip(level, knight, Items.IRON_SWORD);
            equip(level, partner, Items.IRON_SWORD);
            RaiderEntity captain = spawnEnemy(level, stage, fwd, "brute_captain", 2.2D);
            Vec3 pSpot = captain.position().add(right.scale(1.9D)).add(fwd.scale(-0.4D));
            place(level, partner, pSpot, FinisherService.yawToward(captain.position().subtract(pSpot)));
            FinisherService.QA_AUTO_JOIN.put(captain.getUUID(), partner);
            at(td + 24, () -> FinisherService.request(knight, captain));
        });
        at(td + 110, () -> {
            camera.setGameMode(GameType.CREATIVE);
            camera.teleportTo(level, origin.x, origin.y, origin.z, EnumSet.noneOf(RelativeMovement.class),
                yaw0, 12.0F);
            cleanup(level);
        });
        return (int) (td + 110);
    }

    private static long solo(ServerLevel level, ServerPlayer knight, Vec3 stage, Vec3 fwd, float yaw,
                             long t, FinisherVariant variant, Item weapon, String kind) {
        at(t, () -> {
            place(level, knight, stage, yaw);
            equip(level, knight, weapon);
            RaiderEntity victim = spawnEnemy(level, stage, fwd, kind, variant.actorDistance() + 0.4D);
            at(t + 22, () -> {
                FinisherService.qaNextVariant = variant;
                FinisherService.request(knight, victim);
            });
        });
        return t + 22 + variant.lockTicks() + 34;
    }

    private static void at(long tick, Runnable action) {
        STEPS.add(new Step(tick, action));
    }

    static RaiderEntity spawnEnemy(ServerLevel level, Vec3 from, Vec3 fwd, String kind, double blocks) {
        RaiderEntity raider = ModEntities.RAIDER.get().create(level);
        boolean goblin = kind.equals("goblin");
        boolean captain = kind.contains("captain");
        raider.setVariant(kind.startsWith("brute") ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER);
        raider.assign(UUID.randomUUID(), UUID.randomUUID(), com.hearthstead.settlement.raid.RaidObjective.BLOD,
            1.0F, captain);
        if (goblin) {
            raider.setGoblinThiefPresentation(true, 0);
            raider.getAttribute(Attributes.SCALE).setBaseValue(0.65D);
        }
        Vec3 at = from.add(fwd.scale(blocks));
        float yaw = FinisherService.yawToward(fwd.reverse());
        raider.moveTo(at.x, from.y, at.z, yaw, 0.0F);
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

    private static ServerPlayer actor(ServerLevel level, String name, Vec3 at, float yaw) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.setGameMode(GameType.SURVIVAL);
        place(level, p, at, yaw);
        ACTORS.add(p);
        return p;
    }

    private static void place(ServerLevel level, ServerPlayer p, Vec3 at, float yaw) {
        p.teleportTo(level, at.x, at.y, at.z, EnumSet.noneOf(RelativeMovement.class), yaw, 0.0F);
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
    }

    /** Mock players are never ticked, so their equipment is pushed to watchers by hand. */
    private static void equip(ServerLevel level, ServerPlayer p, Item item) {
        ItemStack stack = new ItemStack(item);
        p.setItemSlot(EquipmentSlot.MAINHAND, stack);
        level.getChunkSource().broadcast(p, new ClientboundSetEquipmentPacket(p.getId(),
            List.of(Pair.of(EquipmentSlot.MAINHAND, stack.copy()))));
    }

    static void cleanup(ServerLevel level) {
        var list = level.getServer().getPlayerList();
        for (ServerPlayer p : ACTORS) {
            if (list.getPlayer(p.getUUID()) == p) {
                list.remove(p);
            }
        }
        ACTORS.clear();
        FinisherService.QA_AUTO_JOIN.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (clock < 0L) {
            return;
        }
        long now = clock++;
        List<Step> due = new ArrayList<>();
        STEPS.removeIf(step -> {
            if (step.at() <= now) {
                due.add(step);
                return true;
            }
            return false;
        });
        for (Step step : due) {
            try {
                step.action().run();
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.warn("Finisher reel step failed", failure);
            }
        }
        if (STEPS.isEmpty()) {
            clock = -1L;
        }
    }
}
