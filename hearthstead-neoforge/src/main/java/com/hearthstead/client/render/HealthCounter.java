package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.client.finisher.FinisherClient;
import com.hearthstead.client.revive.DownedClient;
import com.hearthstead.client.ui2.JobIcons;
import com.hearthstead.client.ui2.Ui2Hud;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.ChatFormatting;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * The health counter (owner, 26 Sep; replaces the old red enemy bars and
 * never draws anything above heads): a small plate just above the hotbar for
 * the settler, visitor, raider, brute, captain or hostile mob you look at,
 * with its name (and job icon for settlers), a pixel heart and "18/24", and
 * the gold finisher dagger while it can be finished. It lingers 1.5 s after
 * you look away, shows 3 s after you hit something, and fades over 0.2 s.
 * Rules and layout live in {@link HealthCounterRules} (unit tested).
 *
 * <p>Hidden with F1, in spectator, behind screens and while you are downed
 * (the revive HUD owns that space). Nothing is sent to a server.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class HealthCounter {
    private static final int OUTLINE = 0xFF2B1A16;
    private static final int GOLD = 0xFFD9A441;
    private static final int GOLD_DARK = 0xFF6A462A;
    private static final int ENEMY_NAME = Ui2Hud.WARN;
    /** 9x9 heart; R = tint, D = shade, W = highlight, k = outline. */
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
    /** 7x9 dagger: the finisher-ready glyph. */
    private static final String[] DAGGER = {
        "...k...",
        "..kGk..",
        "..kGk..",
        "..kGk..",
        ".kkkkk.",
        "kGGGGGk",
        ".kkBkk.",
        "...Bk..",
        "...k...",
    };

    private static int lookId = -1;
    private static long lookMs;
    private static int hitId = -1;
    private static long hitMs;
    private static int shownId = -1;
    private static float shown;
    private static long lastFrameMs = -1L;
    // Text cache: rebuilt only when the shown entity, its name or its health changes.
    private static int textKey = Integer.MIN_VALUE;
    private static Component name = Component.empty();
    private static String hp = "";

    private HealthCounter() {
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.SELECTED_ITEM_NAME, Hearthstead.id("health_counter"),
            HealthCounter::render);
    }

    static HealthCounterRules.Mode mode() {
        try {
            return HealthCounterRules.modeOf(HearthsteadClientConfig.HEALTH_COUNTER.get());
        } catch (RuntimeException notLoadedYet) {
            return HealthCounterRules.Mode.LOOK_AT;
        }
    }

    /** Settlers, visitors, raiders (brutes, captain) and hostile mobs; never players. */
    static boolean eligible(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity instanceof Player || entity.isInvisible()
            || !living.isAlive()) {
            return false;
        }
        if (entity instanceof RaiderEntity raider) {
            return !(raider.isGoblinThiefDemo() && raider.isShiftKeyDown());
        }
        return living instanceof SettlerEntity || living instanceof Enemy;
    }

    /** Remember what the local player just hit (shown for 3 s). */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && event.getEntity() == mc.player && event.getEntity().level().isClientSide
            && eligible(event.getTarget())) {
            hitId = event.getTarget().getId();
            hitMs = Util.getMillis();
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            lookId = hitId = shownId = -1;
            shown = 0.0F;
            return;
        }
        Entity looked = mc.crosshairPickEntity;
        if (looked != null && eligible(looked)
            && mc.player.distanceTo(looked) <= HealthCounterRules.LOOK_RANGE) {
            lookId = looked.getId();
            lookMs = Util.getMillis();
        }
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        long elapsed = lastFrameMs < 0 ? 0L : Math.min(100L, now - lastFrameMs);
        lastFrameMs = now;
        if (mc.player == null || mc.level == null) {
            return;
        }
        boolean allowed = HealthCounterRules.hudAllowed(mc.options.hideGui, mc.player.isSpectator(),
            mc.screen != null, DownedClient.localDowned() != null);
        Entity looked = mc.crosshairPickEntity;
        int lookedNow = looked != null && eligible(looked) ? looked.getId() : -1;
        double lookedDistance = lookedNow < 0 ? Double.MAX_VALUE : mc.player.distanceTo(looked);
        int target = allowed ? HealthCounterRules.target(mode(), lookedNow, lookedDistance,
            lookId, now - lookMs, hitId, now - hitMs) : -1;
        Entity entity = target < 0 ? null : mc.level.getEntity(target);
        if (entity != null && !eligible(entity)) {
            entity = null;
        }
        if (entity != null && entity.getId() != shownId) {
            // A new subject replaces the old one at once; only on/off fades.
            shownId = entity.getId();
        }
        shown = HealthCounterRules.approach(shown, entity != null, elapsed);
        if (shown <= 0.02F || !allowed) {
            if (shown <= 0.02F) {
                shownId = -1;
            }
            return;
        }
        Entity subject = entity != null ? entity : mc.level.getEntity(shownId);
        if (!(subject instanceof LivingEntity living) || !eligible(subject)) {
            return;
        }
        paint(g, mc, living, shown, delta);
    }

    private static void paint(GuiGraphics g, Minecraft mc, LivingEntity living, float alpha, DeltaTracker delta) {
        Font font = mc.font;
        float health = living.getHealth();
        float maximum = living.getMaxHealth();
        if (!(maximum > 0.0F) || !Float.isFinite(health)) {
            return;
        }
        int key = living.getId() * 31 + Math.round(health * 10.0F) * 7 + Math.round(maximum * 10.0F)
            + living.getName().getString().hashCode();
        if (key != textKey) {
            textKey = key;
            name = living.getName().copy().withStyle(ChatFormatting.RESET);
            hp = HealthCounterRules.text(health, maximum);
        }
        Profession profession = living instanceof SettlerEntity settler ? settler.getProfession() : null;
        boolean enemy = !(living instanceof SettlerEntity);
        boolean finishable = FinisherClient.isFinishable(living);
        int nameWidth = font.width(name) + (profession != null ? 10 : 0);
        int hpWidth = font.width(hp);
        int line2 = 9 + 2 + hpWidth + (finishable ? 3 + 7 : 0);
        int stack = Math.max(mc.gui.leftHeight, mc.gui.rightHeight);
        int h = HealthCounterRules.plateHeight(g.guiHeight(), stack);
        boolean withName = h == HealthCounterRules.PLATE_H_FULL;
        int w = (withName ? Math.max(nameWidth, line2) : line2) + 10;
        int[] r = HealthCounterRules.plate(g.guiWidth(), g.guiHeight(), w, h, stack);
        int x = r[0];
        int y = r[1];
        Ui2Hud.plate(g, x, y, w, h, alpha);

        if (withName) {
            int nx = x + (w - nameWidth) / 2;
            if (profession != null) {
                JobIcons.draw(g, profession, nx, y + 3, 8);
                nx += 10;
            }
            g.drawString(font, name, nx, y + 3, Ui2Hud.fade(enemy ? ENEMY_NAME : Ui2Hud.TEXT, alpha), false);
        }

        float ratio = Math.max(0.0F, Math.min(1.0F, health / maximum));
        float seconds = (living.tickCount + delta.getGameTimeDeltaPartialTick(false)) / 20.0F;
        int tint = scaleRgb(HealthCounterRules.heartColour(ratio), HealthCounterRules.pulse(ratio, seconds));
        int lx = x + (w - line2) / 2;
        int ly = withName ? y + 14 : y + 3;
        sprite(g, HEART, lx, ly, alpha, 0xFF000000 | tint);
        g.drawString(font, hp, lx + 11, ly + 1, Ui2Hud.fade(0xFF000000 | 0xF1E5CB, alpha), true);
        if (finishable) {
            sprite(g, DAGGER, lx + 11 + hpWidth + 3, ly, alpha, GOLD);
        }
    }

    private static void sprite(GuiGraphics g, String[] rows, int x, int y, float alpha, int tint) {
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                int argb = switch (line.charAt(col)) {
                    case 'k' -> OUTLINE;
                    case 'R', 'G' -> tint;
                    case 'D' -> 0xFF000000 | scaleRgb(tint, 0.72F);
                    case 'W' -> 0xFF000000 | lighten(tint, 70);
                    case 'B' -> GOLD_DARK;
                    default -> 0;
                };
                if (argb != 0) {
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, Ui2Hud.fade(argb, alpha));
                }
            }
        }
    }

    private static int scaleRgb(int rgb, float k) {
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
