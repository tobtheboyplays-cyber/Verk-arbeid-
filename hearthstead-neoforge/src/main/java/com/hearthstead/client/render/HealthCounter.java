package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.client.finisher.FinisherClient;
import com.hearthstead.client.revive.DownedClient;
import com.hearthstead.client.ui2.Ui2Hud;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The heart counter (owner's pick, 26 Sep): a small pixel heart and
 * "14 / 20" at the very top centre of the screen for the settler, visitor,
 * raider, brute, captain or hostile mob you look at, with its name in a
 * quieter ink. No box and no panel, only a text shadow; just below any
 * vanilla boss bar. It fades out over about a second after you look away,
 * and turns gold and pulses only while the target can really be finished.
 * Rules live in {@link HealthCounterRules} (unit tested).
 *
 * <p>Hidden with F1, in spectator, behind screens and while you are downed.
 * Nothing is drawn in the world and nothing is sent to a server. It replaces
 * the old red enemy bars and the retired hotbar plate.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class HealthCounter {
    private static final int OUTLINE = 0xFF2B1A16;
    private static final int HEART_RED = 0xC8403A;
    private static final int GOLD = 0xD9A441;
    private static final int INK = 0xFFF1E5CB;
    private static final int NAME_INK = 0xFFBFAE8F;
    private static final int ENEMY_NAME_INK = 0xFFD29A8C;
    /** The approved 9x9 "lite hjerte": R = tint, D = shade, W = highlight, k = outline. */
    private static final String[] HEART = {
        "..kk.kk..",
        ".kRRkRRk.",
        "kRWRRRRDk",
        "kRRRRRRDk",
        ".kRRRRDk.",
        "..kRRDk..",
        "...kDk...",
        "....k....",
    };

    private static int shownId = -1;
    private static float shown;
    private static long lastFrameMs = -1L;
    /** Lowest vanilla boss bar drawn in the current frame, or -1. */
    private static int bossBarY = -1;
    // Text cache: rebuilt only when the shown entity, its name or its health changes.
    private static int textKey = Integer.MIN_VALUE;
    private static Component name = Component.empty();
    private static String hp = "";

    private HealthCounter() {
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, Hearthstead.id("heart_counter"), HealthCounter::render);
    }

    /** Remember where the boss bars end this frame so the counter sits under them. */
    @SubscribeEvent
    public static void onBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        bossBarY = Math.max(bossBarY, event.getY());
    }

    static HealthCounterRules.Mode mode() {
        try {
            return HealthCounterRules.modeOf(HearthsteadClientConfig.HEALTH_COUNTER.get());
        } catch (RuntimeException notLoadedYet) {
            return HealthCounterRules.Mode.TOP;
        }
    }

    /** Settlers, visitors, raiders (brutes, captain) and hostile mobs; never players. */
    static boolean eligible(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        boolean npc = entity instanceof RaiderEntity raider
            ? !(raider.isGoblinThiefDemo() && raider.isShiftKeyDown())
            : living instanceof SettlerEntity || living instanceof Enemy;
        return HealthCounterRules.showsFor(entity instanceof Player, entity.isInvisible(),
            living.isAlive(), npc);
    }

    /** What you aim at within 16 blocks, stopping at blocks; vanilla's pick only reaches arm's length. */
    static LivingEntity aimed(Minecraft mc, float partial) {
        if (mc.crosshairPickEntity != null && eligible(mc.crosshairPickEntity)) {
            return (LivingEntity) mc.crosshairPickEntity;
        }
        Entity camera = mc.getCameraEntity();
        if (camera == null) {
            return null;
        }
        double range = HealthCounterRules.LOOK_RANGE;
        Vec3 eye = camera.getEyePosition(partial);
        Vec3 look = camera.getViewVector(partial);
        Vec3 end = eye.add(look.scale(range));
        double max = eye.distanceToSqr(camera.pick(range, partial, false).getLocation());
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end,
            camera.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D),
            HealthCounter::eligible, max);
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        int bossY = bossBarY;
        bossBarY = -1; // consumed: boss bars report again next frame
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        long elapsed = lastFrameMs < 0 ? 0L : Math.min(100L, now - lastFrameMs);
        lastFrameMs = now;
        if (mc.player == null || mc.level == null) {
            shown = 0.0F;
            shownId = -1;
            return;
        }
        boolean allowed = HealthCounterRules.hudAllowed(mc.options.hideGui, mc.player.isSpectator(),
            mc.screen != null, DownedClient.localDowned() != null);
        float partial = delta.getGameTimeDeltaPartialTick(false);
        LivingEntity aimed = allowed && mode() != HealthCounterRules.Mode.OFF ? aimed(mc, partial) : null;
        int target = aimed == null ? -1 : HealthCounterRules.target(mode(), aimed.getId(),
            mc.player.distanceTo(aimed));
        if (target >= 0) {
            shownId = target; // a new subject replaces the old one at once; only on/off fades
        }
        shown = HealthCounterRules.approach(shown, target >= 0, elapsed);
        if (!allowed || shown <= 0.02F) {
            if (shown <= 0.02F) {
                shownId = -1;
            }
            return;
        }
        Entity subject = mc.level.getEntity(shownId);
        if (!(subject instanceof LivingEntity living) || !eligible(subject)) {
            shownId = -1;
            shown = 0.0F;
            return;
        }
        paint(g, mc.font, living, shown, HealthCounterRules.topY(bossY), partial);
    }

    private static void paint(GuiGraphics g, Font font, LivingEntity living, float alpha, int y, float partial) {
        float health = living.getHealth();
        float maximum = living.getMaxHealth();
        if (!(maximum > 0.0F) || !Float.isFinite(health)) {
            return;
        }
        int key = living.getId() * 31 + Math.round(health * 10.0F) * 7 + Math.round(maximum * 10.0F)
            + living.getName().getString().hashCode();
        if (key != textKey) {
            textKey = key;
            name = living.getName();
            hp = HealthCounterRules.text(health, maximum);
        }
        float pulse = HealthCounterRules.finisherPulse(FinisherClient.isFinishable(living),
            (living.tickCount + partial) / 20.0F);
        int hpWidth = font.width(hp);
        int nameWidth = font.width(name);
        int total = 9 + 3 + hpWidth + 8 + nameWidth;
        int x = (g.guiWidth() - total) / 2;
        int heart = pulse > 0.0F ? scale(GOLD, pulse) : HEART_RED;
        int numbers = pulse > 0.0F ? 0xFF000000 | scale(GOLD, 0.25F + 0.75F * pulse) : INK;
        sprite(g, x, y, alpha, 0xFF000000 | heart);
        g.drawString(font, hp, x + 12, y + 1, Ui2Hud.fade(numbers, alpha), true);
        g.drawString(font, name, x + 12 + hpWidth + 8, y + 1,
            Ui2Hud.fade(living instanceof SettlerEntity ? NAME_INK : ENEMY_NAME_INK, alpha), true);
    }

    private static void sprite(GuiGraphics g, int x, int y, float alpha, int tint) {
        for (int row = 0; row < HEART.length; row++) {
            String line = HEART[row];
            for (int col = 0; col < line.length(); col++) {
                int argb = switch (line.charAt(col)) {
                    case 'k' -> OUTLINE;
                    case 'R' -> tint;
                    case 'D' -> 0xFF000000 | scale(tint & 0xFFFFFF, 0.72F);
                    case 'W' -> 0xFF000000 | lighten(tint & 0xFFFFFF, 70);
                    default -> 0;
                };
                if (argb != 0) {
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, Ui2Hud.fade(argb, alpha));
                }
            }
        }
    }

    private static int scale(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int gg = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return (r << 16) | (gg << 8) | b;
    }

    private static int lighten(int rgb, int add) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + add);
        int gg = Math.min(255, ((rgb >> 8) & 0xFF) + add);
        int b = Math.min(255, (rgb & 0xFF) + add);
        return (r << 16) | (gg << 8) | b;
    }
}
