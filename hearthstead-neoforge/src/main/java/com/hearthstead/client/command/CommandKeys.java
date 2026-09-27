package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
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
import net.minecraft.network.chat.Component;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Two command keys (owner, 27 Sep: "2 keys + handbook"), both rebindable in
 * the "Bannerhold" controls category:
 * <ul>
 *   <li>R = the melee troops (Knights, Spearmen, Longswordsmen, with the
 *       Healers behind them);</li>
 *   <li>G = the ranged troops (Archers, Rune Mages).</li>
 * </ul>
 * Tap = order from what you look at: a raider = focus it (after it falls they
 * keep attacking the next raider until a new order), the ground = form a line
 * there facing your view, a wall or tower = ranged climb it (melee hold its
 * foot), your own feet = follow me, a settler = summon that settler to you.
 * Hold (0.25 s) = a small order strip for that group, picked with the number
 * keys, or the mouse wheel then right-click (or a tap of the same key).
 * Sneak + key = that group back to posts. Nothing here pauses the game or
 * takes the mouse. The melee key first asks {@link CommandKeyHooks} (the
 * finisher), so one press never does both.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CommandKeys {
    public static final String CATEGORY = "key.categories.hearthstead";
    public static final int HOLD_TICKS = 5;
    private static final long MENU_TIMEOUT_MS = 6000L;

    public static final int MELEE_DEFAULT = com.hearthstead.client.KeyDefaults.COMMAND_MELEE;
    public static final int RANGED_DEFAULT = com.hearthstead.client.KeyDefaults.COMMAND_RANGED;
    public static final KeyMapping MELEE_KEY = key("command_melee", MELEE_DEFAULT);
    public static final KeyMapping RANGED_KEY = key("command_ranged", RANGED_DEFAULT);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.hearthstead." + name, KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    enum Phase { IDLE, PRESSED, CONSUMED }

    static final class Channel {
        final Group group;
        final KeyMapping key;
        Phase phase = Phase.IDLE;
        int heldTicks;
        boolean wasDown;

        Channel(Group group, KeyMapping key) {
            this.group = group;
            this.key = key;
        }

        boolean active() {
            return phase == Phase.PRESSED;
        }
    }

    static final Channel MELEE = new Channel(Group.MELEE, MELEE_KEY);
    static final Channel RANGED = new Channel(Group.RANGED, RANGED_KEY);
    static final List<Channel> CHANNELS = List.of(MELEE, RANGED);

    /** Registered by {@link CommandClientSetup}: exactly the two command keys. */
    public static final List<KeyMapping> ALL_MAPPINGS = List.of(MELEE_KEY, RANGED_KEY);

    /** One chip of a group's order strip. */
    public enum MenuItem {
        HOLD_FIRE, FOLLOW, RETURN, RESUPPLY;

        public String labelKey() {
            return switch (this) {
                case HOLD_FIRE -> holdingFire() ? "hearthstead.command.menu.fire_at_will"
                    : "hearthstead.command.menu.hold_fire";
                case FOLLOW -> "hearthstead.command.menu.follow";
                case RETURN -> "hearthstead.command.menu.return";
                case RESUPPLY -> "hearthstead.command.menu.resupply";
            };
        }
    }

    /** The strip: R follow / posts; G hold fire / follow / posts / resupply. */
    public static List<MenuItem> menuItems(Group group) {
        return group == Group.RANGED
            ? List.of(MenuItem.HOLD_FIRE, MenuItem.FOLLOW, MenuItem.RETURN, MenuItem.RESUPPLY)
            : List.of(MenuItem.FOLLOW, MenuItem.RETURN);
    }

    @Nullable private static Channel menu;
    private static int menuHighlight;
    private static long menuOpenedAt;

    // ------------------------------------------------------------------ state

    /** Ground-dot preview; the hold now opens the order strip, so there is none. */
    public record Preview(CommandAim.Target target, int soldiers, int width) {
    }

    @Nullable
    public static Preview preview(float partialTick) {
        return null;
    }

    /** True while a command key is held or a strip is open: show active dots. */
    public static boolean commanding() {
        if (menu != null) return true;
        for (Channel ch : CHANNELS) if (ch.active()) return true;
        return false;
    }

    /** The group whose key is held or whose strip is open (for the command outline), or null. */
    @Nullable
    public static Group activeGroup() {
        if (menu != null) return menu.group;
        for (Channel ch : CHANNELS) if (ch.active()) return ch.group;
        return null;
    }

    public static boolean menuOpen() {
        return menu != null;
    }

    /** The group of the open strip, or null. */
    @Nullable
    public static Group menuGroup() {
        return menu == null ? null : menu.group;
    }

    public static int menuHighlight() {
        return menuHighlight;
    }

    // ------------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (menu == null || mc.player == null) return;
        // Strip picks use the number keys; consume them before vanilla turns
        // them into hotbar selection. Only while a strip is open.
        int items = menuItems(menu.group).size();
        for (int i = 0; i < items; i++) {
            KeyMapping slot = mc.options.keyHotbarSlots[i];
            boolean picked = false;
            while (slot.consumeClick()) picked = true;
            if (picked) {
                pickMenu(mc, i);
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
            menu = null;
        }
        for (Channel ch : CHANNELS) tickChannel(mc, ch);
        if (menu != null && System.currentTimeMillis() - menuOpenedAt > MENU_TIMEOUT_MS) menu = null;
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
            if (++ch.heldTicks >= HOLD_TICKS) {
                // Held: open this group's order strip instead of giving the aimed order.
                ch.phase = Phase.CONSUMED;
                menu = ch;
                menuHighlight = 0;
                menuOpenedAt = System.currentTimeMillis();
                mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, 1.4F);
            }
        } else if (!down && ch.wasDown) {
            onRelease(mc, ch);
        }
        ch.wasDown = down;
    }

    private static void onPress(Minecraft mc, Channel ch) {
        if (menu != null) {
            // A tap of the strip's own key confirms the highlighted chip; another key closes it.
            boolean same = menu == ch;
            Channel open = menu;
            if (same && !Screen.hasShiftDown()) {
                pickMenu(mc, menuHighlight);
                ch.phase = Phase.CONSUMED;
                return;
            }
            menu = null;
            if (open == ch) {
                ch.phase = Phase.CONSUMED;
                return;
            }
        }
        if (com.hearthstead.client.builder.BuilderPlacement.mode()
            != com.hearthstead.client.builder.BuilderPlacement.Mode.NONE) {
            // Placing a blueprint or a wall line: G toggles the gate there, R/G give no orders.
            ch.phase = Phase.CONSUMED;
            return;
        }
        for (Channel other : CHANNELS) {
            if (other != ch && other.active()) {
                ch.phase = Phase.CONSUMED; // one call at a time
                return;
            }
        }
        if (ch == MELEE && CommandKeyHooks.knightsPressConsumed(mc)) {
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
        if (phase != Phase.PRESSED) return;
        // A settler under the crosshair (nearer than any enemy): summon them to you.
        SettlerEntity friend = CommandAim.aimedSettler(mc, 1.0F);
        if (friend != null) {
            Optional<Component> reason = SummonClient.unavailableReason(friend);
            if (reason.isPresent()) mc.player.displayClientMessage(reason.get(), true);
            else SummonClient.request(friend);
            return;
        }
        CommandAim.Target target = CommandAim.resolve(mc, ch.group, 1.0F);
        if (!target.valid()) {
            mc.player.displayClientMessage(target.problem(), true);
            mc.player.playSound(SoundEvents.VILLAGER_NO, 0.4F, 1.2F);
            return;
        }
        send(ch.group, target.kind(), target.pos(), target.octant(), 1, target.enemyId());
    }

    static void send(Group group, Kind kind, @Nullable BlockPos pos, int octant, int width, int enemyId) {
        PacketDistributor.sendToServer(new FieldOrderRequestPayload(group.wireId(), kind.wireId(),
            pos == null ? FieldOrderRequestPayload.NO_POS : pos, octant,
            Math.max(FormationMath.MIN_WIDTH, Math.min(FormationMath.MAX_WIDTH, width)), enemyId));
        CommandClientState.issued(group, kind);
    }

    private static void pickMenu(Minecraft mc, int index) {
        Channel open = menu;
        menu = null;
        if (open == null) return;
        List<MenuItem> items = menuItems(open.group);
        if (index < 0 || index >= items.size()) return;
        int octant = FormationMath.octant(mc.player.getYRot());
        switch (items.get(index)) {
            case HOLD_FIRE -> send(open.group, holdingFire() ? Kind.FIRE_AT_WILL : Kind.HOLD_FIRE, null, octant, 1, -1);
            case FOLLOW -> send(open.group, Kind.FOLLOW, mc.player.blockPosition(), octant, 1, -1);
            case RETURN -> send(open.group, Kind.RETURN, null, octant, 1, -1);
            case RESUPPLY -> send(Group.ARCHERS, Kind.RESUPPLY, null, octant, 1, -1);
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

    /** True when an archer in earshot is out of arrows (the Resupply chip lights up). */
    public static boolean archerDry() {
        List<SettlerEntity> archers = new ArrayList<>(CommandClientState.inEarshot(Group.ARCHERS));
        for (SettlerEntity archer : archers) {
            if (archer.getActivity() == SettlerActivity.OUT_OF_AMMO) return true;
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
        menu = null;
    }

    // ------------------------------------------------------------------ input

    /** While a strip is open the mouse wheel moves its highlight (not the hotbar). */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (menu == null) return;
        event.setCanceled(true);
        int size = menuItems(menu.group).size();
        int step = event.getScrollDeltaY() > 0 ? -1 : event.getScrollDeltaY() < 0 ? 1 : 0;
        menuHighlight = Math.floorMod(menuHighlight + step, size);
        menuOpenedAt = System.currentTimeMillis();
    }

    /** Attack cancels a strip; use (right-click) picks the highlighted chip. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack()) {
            cancelAll();
            menu = null;
            return;
        }
        if (event.isUseItem() && menu != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            pickMenu(Minecraft.getInstance(), menuHighlight);
        }
    }

    /** Escape closes a strip instead of opening the pause menu. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof PauseScreen)) return;
        if (commanding()) {
            event.setCanceled(true);
            cancelAll();
            menu = null;
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private CommandKeys() {
    }
}
