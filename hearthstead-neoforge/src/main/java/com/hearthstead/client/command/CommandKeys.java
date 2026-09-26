package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FormationMath;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One call per battle role (all rebindable, category "Bannerhold"):
 * R = Knights, G = Archers, J = Spearmen, K = Longswordsmen, N = Rune Mages,
 * H = Healers, and an unbound "all soldiers" key.
 *
 * <p>Tap = order from what you look at. Hold (0.25 s) = ground-dot preview;
 * scroll changes the line width; release confirms; attack or Escape cancels.
 * Shift + key = back to posts. B opens a tiny strip for rarer orders (hold
 * fire / fire at will, all follow me, all back to posts), picked with 1-3.
 * Nothing here pauses the game or takes the mouse. The Knights key first asks
 * {@link CommandKeyHooks} (the finisher), so one press never does both.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CommandKeys {
    public static final String CATEGORY = "key.categories.hearthstead";
    public static final int HOLD_TICKS = 5;
    private static final long MENU_TIMEOUT_MS = 6000L;

    public static final KeyMapping KNIGHTS_KEY = key("command_knights", GLFW.GLFW_KEY_R);
    public static final KeyMapping ARCHERS_KEY = key("command_archers", GLFW.GLFW_KEY_G);
    public static final KeyMapping SPEARMEN_KEY = key("command_spearmen", GLFW.GLFW_KEY_J);
    public static final KeyMapping LONGSWORDSMEN_KEY = key("command_longswordsmen", GLFW.GLFW_KEY_K);
    public static final KeyMapping MAGES_KEY = key("command_mages", GLFW.GLFW_KEY_N);
    public static final KeyMapping HEALERS_KEY = key("command_healers", GLFW.GLFW_KEY_H);
    public static final KeyMapping ALL_KEY = key("command_all", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping MENU_KEY = key("command_menu", GLFW.GLFW_KEY_B);
    /** Look at any settler and press: "come to me" (O = over here). */
    public static final KeyMapping SUMMON_KEY = key("summon", GLFW.GLFW_KEY_O);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.hearthstead." + name, KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    enum Phase { IDLE, PRESSED, PREVIEW, CONSUMED }

    static final class Channel {
        final Group group;
        final KeyMapping key;
        Phase phase = Phase.IDLE;
        int heldTicks;
        boolean wasDown;
        /** Remembered line width; -1 = default for the soldier count. */
        int width = -1;

        Channel(Group group, KeyMapping key) {
            this.group = group;
            this.key = key;
        }

        boolean active() {
            return phase == Phase.PRESSED || phase == Phase.PREVIEW;
        }
    }

    static final Channel KNIGHTS = new Channel(Group.KNIGHTS, KNIGHTS_KEY);
    static final List<Channel> CHANNELS = List.of(
        KNIGHTS,
        new Channel(Group.ARCHERS, ARCHERS_KEY),
        new Channel(Group.SPEARMEN, SPEARMEN_KEY),
        new Channel(Group.LONGSWORDSMEN, LONGSWORDSMEN_KEY),
        new Channel(Group.MAGES, MAGES_KEY),
        new Channel(Group.HEALERS, HEALERS_KEY),
        new Channel(Group.ALL, ALL_KEY));

    public static final List<KeyMapping> ALL_MAPPINGS = List.of(KNIGHTS_KEY, ARCHERS_KEY, SPEARMEN_KEY,
        LONGSWORDSMEN_KEY, MAGES_KEY, HEALERS_KEY, ALL_KEY, MENU_KEY, SUMMON_KEY);

    private static boolean menuOpen;
    private static long menuOpenedAt;

    // ------------------------------------------------------------------ state

    /** Preview being shown this frame, or null. */
    public record Preview(CommandAim.Target target, int soldiers, int width) {
    }

    @Nullable
    public static Preview preview(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Channel channel = previewing();
        if (channel == null || mc.player == null || mc.level == null) return null;
        CommandAim.Target target = CommandAim.resolve(mc, channel.group, partialTick);
        int soldiers = CommandClientState.inEarshot(channel.group).size();
        return new Preview(target, soldiers, widthFor(channel, soldiers));
    }

    @Nullable
    static Channel previewing() {
        for (Channel ch : CHANNELS) if (ch.phase == Phase.PREVIEW) return ch;
        return null;
    }

    /** True while a command key is held or the strip is open: show active dots. */
    public static boolean commanding() {
        if (menuOpen) return true;
        for (Channel ch : CHANNELS) if (ch.active()) return true;
        return false;
    }

    /** The group whose key is currently held (for the command outline), or null. */
    @Nullable
    public static Group activeGroup() {
        for (Channel ch : CHANNELS) if (ch.active()) return ch.group;
        return null;
    }

    public static boolean menuOpen() {
        return menuOpen;
    }

    static int widthFor(Channel channel, int soldiers) {
        int base = channel.width > 0 ? channel.width
            : FormationMath.defaultWidth(channel.group == Group.ALL ? Group.KNIGHTS : channel.group,
                Math.max(1, soldiers));
        return FormationMath.clampWidth(base, Math.max(1, soldiers));
    }

    // ------------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!menuOpen || mc.player == null) return;
        // Strip picks use the number keys; consume them before vanilla turns
        // them into hotbar selection. Only while the strip is open.
        for (int i = 0; i < 3; i++) {
            KeyMapping slot = mc.options.keyHotbarSlots[i];
            boolean picked = false;
            while (slot.consumeClick()) picked = true;
            if (picked) {
                pickMenu(mc, i + 1);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.screen != null || !mc.player.isAlive() || mc.player.isSpectator()) {
            cancelAll();
            menuOpen = false;
        }
        for (Channel ch : CHANNELS) tickChannel(mc, ch);
        while (MENU_KEY.consumeClick()) {
            if (mc.screen == null) {
                menuOpen = !menuOpen;
                menuOpenedAt = System.currentTimeMillis();
                if (menuOpen) cancelAll();
            }
        }
        if (menuOpen && System.currentTimeMillis() - menuOpenedAt > MENU_TIMEOUT_MS) menuOpen = false;
        // Presses are read from isDown edges; drop queued clicks so they never pile up.
        for (Channel ch : CHANNELS) {
            while (ch.key.consumeClick()) { }
        }
    }

    private static void tickChannel(Minecraft mc, Channel ch) {
        boolean down = !ch.key.isUnbound() && ch.key.isDown() && mc.screen == null;
        if (down && !ch.wasDown) {
            onPress(mc, ch);
        } else if (down && ch.phase == Phase.PRESSED) {
            if (++ch.heldTicks >= HOLD_TICKS) ch.phase = Phase.PREVIEW;
        } else if (!down && ch.wasDown) {
            onRelease(mc, ch);
        }
        ch.wasDown = down;
    }

    private static void onPress(Minecraft mc, Channel ch) {
        menuOpen = false;
        for (Channel other : CHANNELS) {
            if (other != ch && other.active()) {
                ch.phase = Phase.CONSUMED; // one call at a time
                return;
            }
        }
        if (ch == KNIGHTS && CommandKeyHooks.knightsPressConsumed(mc)) {
            ch.phase = Phase.CONSUMED; // e.g. a finisher on an enemy in reach
            return;
        }
        if (Screen.hasShiftDown()) {
            send(ch.group, Kind.RETURN, null, FormationMath.octant(mc.player.getYRot()), 1, -1);
            ch.phase = Phase.CONSUMED;
            return;
        }
        ch.phase = Phase.PRESSED;
        ch.heldTicks = 0;
    }

    private static void onRelease(Minecraft mc, Channel ch) {
        Phase phase = ch.phase;
        ch.phase = Phase.IDLE;
        ch.heldTicks = 0;
        if (phase != Phase.PRESSED && phase != Phase.PREVIEW) return;
        CommandAim.Target target = CommandAim.resolve(mc, ch.group, 1.0F);
        if (!target.valid()) {
            mc.player.displayClientMessage(target.problem(), true);
            mc.player.playSound(SoundEvents.VILLAGER_NO, 0.4F, 1.2F);
            return;
        }
        int soldiers = CommandClientState.inEarshot(ch.group).size();
        send(ch.group, target.kind(), target.pos(), target.octant(), widthFor(ch, soldiers), target.enemyId());
    }

    static void send(Group group, Kind kind, @Nullable BlockPos pos, int octant, int width, int enemyId) {
        PacketDistributor.sendToServer(new FieldOrderRequestPayload(group.wireId(), kind.wireId(),
            pos == null ? FieldOrderRequestPayload.NO_POS : pos, octant,
            Math.max(FormationMath.MIN_WIDTH, Math.min(FormationMath.MAX_WIDTH, width)), enemyId));
    }

    private static void pickMenu(Minecraft mc, int choice) {
        menuOpen = false;
        int octant = FormationMath.octant(mc.player.getYRot());
        switch (choice) {
            case 1 -> send(Group.ALL, holdingFire() ? Kind.FIRE_AT_WILL : Kind.HOLD_FIRE, null, octant, 1, -1);
            case 2 -> send(Group.ALL, Kind.FOLLOW, mc.player.blockPosition(), octant, 1, -1);
            case 3 -> send(Group.ALL, Kind.RETURN, null, octant, 1, -1);
            default -> { }
        }
    }

    /** True if any ranged role currently holds fire (the strip then offers "fire at will"). */
    static boolean holdingFire() {
        for (Group role : Group.ROLES) {
            if (!role.ranged()) continue;
            var line = CommandClientState.line(role);
            if (line != null && line.holdFire()) return true;
        }
        return false;
    }

    private static void cancelAll() {
        for (Channel ch : CHANNELS) {
            if (ch.active()) ch.phase = Phase.CONSUMED;
        }
    }

    private static void reset() {
        for (Channel ch : CHANNELS) {
            ch.phase = Phase.IDLE;
            ch.wasDown = false;
            ch.heldTicks = 0;
        }
        menuOpen = false;
    }

    // ------------------------------------------------------------------ input

    /** Scroll widens (up) or narrows (down) the previewed line. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Channel ch = previewing();
        if (ch == null) return;
        event.setCanceled(true); // keep the hotbar selection where it is
        int soldiers = CommandClientState.inEarshot(ch.group).size();
        int current = widthFor(ch, soldiers);
        int step = event.getScrollDeltaY() > 0 ? 1 : event.getScrollDeltaY() < 0 ? -1 : 0;
        ch.width = FormationMath.clampWidth(current + step, Math.max(1, soldiers));
    }

    /** Attacking cancels a preview or the strip instantly; the attack still happens. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        cancelAll();
        menuOpen = false;
    }

    /** Escape cancels the preview or strip instead of opening the pause menu. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof PauseScreen)) return;
        if (commanding()) {
            event.setCanceled(true);
            cancelAll();
            menuOpen = false;
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private CommandKeys() {
    }
}
