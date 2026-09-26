package com.hearthstead.client.ui2.handbook;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.screen.HandbookScreen;
import com.hearthstead.client.ui2.Ui2Hud;
import com.hearthstead.client.ui2.Ui2Palette;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Onboarding hooks around the handbook:
 * <ul>
 *   <li>the "Open the Handbook" key (Y by default, rebindable), which opens
 *       the page a recent hint pointed at, or the last page read;</li>
 *   <li>one-time hints: the first time a Builder's Plan, Survey Rod, guard
 *       emblem (or any item the book lists under {@code hint_items} /
 *       {@code hints}) is in the player's inventory, a Banner-styled toast
 *       names the page and the key that opens it;</li>
 *   <li>the client command {@code /handbook [page]} (for QA stills and for
 *       players who prefer typing).</li>
 * </ul>
 * Everything is client-side: the "seen" set lives in {@link HandbookState}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class HandbookHints {
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.hearthstead.handbook",
        KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.hearthstead");
    private static final long HINT_WINDOW_MS = 60_000L;
    private static Map<String, String> hintPages;
    private static String pendingPage;
    private static long pendingAt;
    private static int tick;

    private HandbookHints() {
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("handbook")
            .executes(ctx -> {
                Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new HandbookScreen()));
                return 1;
            })
            .then(Commands.argument("page", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String page = StringArgumentType.getString(ctx, "page").trim();
                    Minecraft.getInstance().tell(() -> {
                        if (page.startsWith("fj_")) HandbookScreen.openForJourney(page);
                        else HandbookScreen.openPage(page);
                    });
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null) {
                boolean fresh = pendingPage != null && System.currentTimeMillis() - pendingAt < HINT_WINDOW_MS;
                String target = fresh ? pendingPage : heldItemPage(mc);
                pendingPage = null;
                mc.setScreen(new HandbookScreen(target));
            }
        }
        if (++tick % 20 != 0 || mc.screen != null) return;
        if (hintPages == null) hintPages = loadHints();
        if (hintPages.isEmpty()) return;
        var inv = mc.player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String pageId = hintPages.get(id);
            // Catalog hints are once per FAMILY (one toast for all emblems, not thirty).
            HandbookItems.Family family = HandbookLoader.items().familyOf(id);
            String seenKey = family != null && family.hint() ? "family:" + family.id() : id;
            if (pageId != null && !HandbookState.hintSeen(seenKey) && !HandbookState.hintSeen(id)) {
                HandbookState.markHintSeen(seenKey);
                pendingPage = pageId;
                pendingAt = System.currentTimeMillis();
                mc.getToasts().addToast(new HintToast(stack.copyWithCount(1), pageTitle(pageId)));
                return;
            }
        }
    }

    private static Map<String, String> loadHints() {
        HandbookBook book = HandbookLoader.load();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var e : book.hintPages().entrySet()) out.put(e.getKey(), e.getValue().id());
        HandbookItems catalog = HandbookLoader.items();
        for (var e : catalog.items().entrySet()) {
            HandbookItems.Family f = catalog.families().get(e.getValue());
            if (f != null && f.hint() && f.page() != null && book.page(f.page()) != null) {
                out.putIfAbsent(e.getKey(), f.page());
            }
        }
        TITLES.clear();
        for (HandbookBook.Page p : book.pages()) {
            String key = p.titleKey() != null ? p.titleKey() : book.chapterOf(p).titleKey();
            TITLES.put(p.id(), key);
        }
        return out;
    }

    /** The handbook page of the documented item in the main hand, if any. */
    private static String heldItemPage(Minecraft mc) {
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) return null;
        HandbookItems.Family f = HandbookLoader.items().familyOf(
            BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        return f == null || f.isInternal() ? null : f.page();
    }

    private static final Map<String, String> TITLES = new java.util.HashMap<>();

    private static Component pageTitle(String pageId) {
        String key = TITLES.get(pageId);
        return key == null ? Component.literal(pageId) : Component.translatable(key);
    }

    /** Forget cached hint targets (resource reload / language change). */
    public static void invalidate() {
        hintPages = null;
    }

    /** Banner-styled toast: walnut plate, burgundy edge, the item, the page and the key. */
    private static final class HintToast implements Toast {
        private static final long SHOW_MS = 9000L;
        private final ItemStack icon;
        private final Component page;

        private HintToast(ItemStack icon, Component page) {
            this.icon = icon;
            this.page = page;
        }

        @Override
        public int width() {
            return 200;
        }

        @Override
        public int height() {
            return 46;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
            Font font = toasts.getMinecraft().font;
            int w = width();
            int h = height();
            // The shared HUD plate (Ui2Hud), with the handbook's burgundy bookmark.
            Ui2Hud.plate(g, 0, 0, w, h);
            g.fill(1, 1, 4, h - 1, Ui2Palette.BURGUNDY);
            g.renderItem(icon, 10, (h - 16) / 2);
            g.drawString(font, Component.translatable("hearthstead.guide.ui.hint.title"), 32, 5,
                Ui2Hud.KEY, false);
            String key = OPEN_KEY.isUnbound() ? null : OPEN_KEY.getTranslatedKeyMessage().getString();
            Component body = key == null
                ? Component.translatable("hearthstead.guide.ui.hint.body_unbound", page)
                : Component.translatable("hearthstead.guide.ui.hint.body", "[" + key + "]", page);
            List<FormattedCharSequence> lines = new ArrayList<>(font.split(body, w - 38));
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                g.drawString(font, lines.get(i), 32, 16 + i * 9, Ui2Hud.TEXT, false);
            }
            return time * toasts.getNotificationDisplayTimeMultiplier() >= SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
        }
    }
}
