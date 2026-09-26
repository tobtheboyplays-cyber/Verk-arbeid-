package com.hearthstead.client.conversation;

import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.conversation.Relations;
import com.hearthstead.conversation.net.ConvStatePayload;
import com.hearthstead.conversation.net.ConvStatePayload.OptionView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/**
 * The conversation bar (Banner screen style): a walnut board along the bottom
 * with the speaker's portrait, serif name, title and relation on the left
 * over a parchment text panel, and numbered replies on the right. The world
 * stays visible (no blur): the camera frames the speaker above it.
 *
 * <p>Owner rules: lines type out at ~55 chars/s with a Sims-style babble
 * voice and a talking head ({@link ConversationVoice}, {@link TalkingHead});
 * click or Space/Enter shows the full text. Nothing is cut off: long names
 * step down a type size, replies wrap to two lines, and a long body scrolls
 * (it follows the typing; the mouse wheel scrolls back). Keys 1-9 choose.
 *
 * <p>The first meeting opens with a short name card (about 1.2 s; a raid
 * captain 1.5 s) that shrinks into the header as the first line starts; any
 * key or click skips it. Repeat talks skip the card.
 */
public final class ConversationScreen extends Screen {
    private static final float CHARS_PER_SECOND = 55.0F;
    private static final float CARD_IN = 0.25F;
    private static final float CARD_OUT = 0.3F;
    private static final int OPTION_LINE = 9;

    private ConvStatePayload state;
    private long typingStartNanos;
    private boolean typingSkipped;
    private long flourishNanos;
    private int flourish;
    private int flourishChance;
    private boolean card;
    private final float cardTotal;
    private long cardStartNanos;
    private long cardOutNanos = -1L;
    private long openNanos = System.nanoTime();
    private int focus = 0;
    private int scrollBack;
    private int lastToneLine = -1;
    /** A reply was sent for this revision; nothing more is sent until the server answers. */
    private int pendingRevision = -1;
    private final Ui2Serif.Text nameText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text nameSmall = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private final Ui2Serif.Text cardName = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final List<int[]> optionRects = new ArrayList<>();
    private List<String> plainLines = List.of();
    private List<String> lineKeys = List.of();
    /** Reveal time per line (ms): the recording's length for voiced lines, else the typing speed. */
    private int[] lineMillis = new int[0];

    public ConversationScreen(ConvStatePayload state) {
        this(state, false);
    }

    public ConversationScreen(ConvStatePayload state, boolean nameCard) {
        super(Component.translatable("conversation.hearthstead.screen"));
        this.card = nameCard && com.hearthstead.HearthsteadClientConfig.encounterCinematics();
        this.cardTotal = state.style() == 1 ? 1.5F : 1.2F;
        this.cardStartNanos = System.nanoTime();
        if (card) com.hearthstead.client.sound.HsSound.ui("convo.name_card", null, 0.55F, 1.0F);
        update(state);
    }

    public int session() {
        return state.session();
    }

    public void update(ConvStatePayload next) {
        boolean newText = state == null || !state.lines().equals(next.lines());
        state = next;
        List<String> plain = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Component line : next.lines()) {
            plain.add(ConversationVoice.stripTone(line.getString()));
            keys.add(VoiceLines.keyOf(line));
        }
        plainLines = plain;
        lineKeys = keys;
        lineMillis = new int[plain.size()];
        for (int i = 0; i < plain.size(); i++) {
            int voiced = ConversationVoice.voicedMillis(speaker(), keys.get(i));
            lineMillis[i] = voiced > 0 ? voiced : Math.round(plain.get(i).length() * 1000.0F / CHARS_PER_SECOND);
        }
        if (newText) {
            typingStartNanos = System.nanoTime();
            typingSkipped = false;
            scrollBack = 0;
            lastToneLine = -1;
            Entity npc = speaker();
            if (npc != null && !card) ConversationVoice.begin(npc);
        }
        if (next.flourish() != 0) {
            // Sound pass: the wax seal lands with a warm or a dull cue, once per roll.
            if (next.flourish() != flourish || System.nanoTime() - flourishNanos > 1_500_000_000L) {
                com.hearthstead.client.sound.HsSound.ui(next.flourish() == 1 ? "convo.persuade_ok" : "convo.persuade_fail",
                    null, 0.7F, 1.0F);
            }
            flourish = next.flourish();
            flourishChance = next.flourishChance();
            flourishNanos = System.nanoTime();
        }
        focus = Math.max(0, Math.min(focus, next.options().size() - 1));
    }

    private Entity speaker() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getEntity(state.npcId());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No blur: the framed speaker is the point. Only a soft shade under the bar.
        int h = height;
        for (int i = 0; i < 24; i++) {
            int alpha = (int) (i * 3.2F);
            g.fill(0, h - 24 * 7 + i * 7, width, h - 24 * 7 + (i + 1) * 7, alpha << 24);
        }
    }

    @Override
    public void tick() {
        Entity npc = speaker();
        if (npc != null) {
            String clip = !typingDone() ? "settler/village_chat" : "settler/village_listen";
            MotionOverrides.overlay(npc.getId(), clip, npc.tickCount);
        }
        if (card && cardOutNanos < 0 && cardSeconds() >= cardTotal - CARD_OUT) endCard();
    }

    @Override
    public void removed() {
        Entity npc = speaker();
        if (npc != null) {
            MotionOverrides.stopOverlay(npc.getId(), npc.tickCount);
        }
        // By the stored id, even when the speaker died or unloaded first (T32): its
        // playing clip and Voice must never outlive the screen.
        ConversationVoice.stop(state.npcId());
        ConversationVoice.end(state.npcId());
        super.removed();
    }

    @Override
    public void onClose() {
        ConversationClient.leave(state.session());
        super.onClose();
    }

    private float cardSeconds() {
        return (System.nanoTime() - cardStartNanos) / 1.0e9F;
    }

    /** The card starts shrinking into the header; the bar slides in and the first line starts. */
    private void endCard() {
        if (!card || cardOutNanos >= 0) return;
        cardOutNanos = System.nanoTime();
        ConversationCamera.introDone();
        typingStartNanos = System.nanoTime();
        openNanos = System.nanoTime();
        Entity npc = speaker();
        if (npc != null) ConversationVoice.begin(npc);
    }

    private boolean cardShowing() {
        if (!card) return false;
        if (cardOutNanos >= 0 && (System.nanoTime() - cardOutNanos) / 1.0e9F > CARD_OUT) {
            card = false;
            return false;
        }
        return true;
    }

    /** Before the card starts leaving, only the card shows. */
    private boolean cardOnly() {
        return card && cardOutNanos < 0;
    }

    // --------------------------------------------------------------- text ---

    private int totalChars() {
        int n = 0;
        for (String line : plainLines) n += line.length();
        return n;
    }

    private int shownChars() {
        if (typingSkipped) return Integer.MAX_VALUE;
        // Each line reveals over its own time: a voiced line over its recording, others at the typing speed.
        float elapsed = (System.nanoTime() - typingStartNanos) / 1.0e6F;
        int shown = 0;
        for (int i = 0; i < plainLines.size(); i++) {
            int chars = plainLines.get(i).length();
            int ms = i < lineMillis.length ? Math.max(1, lineMillis[i]) : 1;
            if (elapsed < ms) return shown + (int) (chars * elapsed / ms);
            shown += chars;
            elapsed -= ms;
        }
        return shown;
    }

    private boolean typingDone() {
        return !cardOnly() && shownChars() >= totalChars();
    }

    /** Encounters hold their replies until the opening line has been said. */
    private boolean optionsVisible() {
        return !cardOnly() && (state.mode() != 1 || typingDone());
    }

    private void skipTyping() {
        typingSkipped = true;
        ConversationVoice.stop(state.npcId());
    }

    // ------------------------------------------------------------- layout ---

    private int optionColumnWidth(int w) {
        return w - Math.round(w * 0.5F) - 18;
    }

    private List<List<FormattedCharSequence>> wrappedOptions(Font font, int columnW) {
        List<List<FormattedCharSequence>> out = new ArrayList<>();
        for (OptionView option : state.options()) {
            int costW = 0;
            for (ConvStatePayload.CostView cost : option.costs()) costW += font.width(String.valueOf(cost.amount())) + 18;
            int textW = columnW - 20 - costW - 14 - (option.enabled() ? 0 : 10);
            List<FormattedCharSequence> lines = font.split(optionText(option), Math.max(40, textW));
            if (lines.size() > 2) {
                // Never cut a word: a third line is folded into the second by shrinking the wrap once more.
                lines = font.split(optionText(option), Math.max(40, textW + 12));
                if (lines.size() > 2) lines = List.of(lines.get(0), lines.get(1));
            }
            out.add(lines);
        }
        return out;
    }

    private static Component optionText(OptionView option) {
        Component text = option.text();
        if (option.chance() >= 0) {
            text = Component.translatable("conversation.hearthstead.persuade_prefix", option.chance()).append(" ").append(text);
        }
        return text;
    }

    private int barHeight(Font font, int w) {
        int optionsH = 0;
        for (List<FormattedCharSequence> lines : wrappedOptions(font, optionColumnWidth(w))) {
            optionsH += rowHeight(lines.size()) + 3;
        }
        int wanted = Math.max(Math.round(height * 0.44F), optionsH + 20);
        return Mth.clamp(wanted, 120, Math.max(120, Math.round(height * 0.6F)));
    }

    private static int rowHeight(int lines) {
        return lines <= 1 ? 17 : 8 + lines * OPTION_LINE + 1;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        boolean showingCard = cardShowing();
        if (!cardOnly()) {
            Font font = Minecraft.getInstance().font;
            float slide = Mth.clamp((System.nanoTime() - openNanos) / 1.0e9F / 0.25F, 0.0F, 1.0F);
            slide = 1.0F - (1.0F - slide) * (1.0F - slide);
            int x = 8;
            int w = width - 16;
            int barH = barHeight(font, w);
            int y = height - barH - 6 + Math.round((1.0F - slide) * (barH + 12));
            BannerChrome.panel(g, x, y, w, barH);
            int leftW = Math.round(w * 0.5F);
            renderSpeaker(g, x + 10, y + 9, leftW - 16, barH - 18);
            g.fill(x + leftW, y + 8, x + leftW + 1, y + barH - 8, BannerChrome.PLATE_SHADOW);
            g.fill(x + leftW + 1, y + 8, x + leftW + 2, y + barH - 8, BannerChrome.PLATE_HIGHLIGHT);
            renderOptions(g, x + leftW + 8, y + 10, optionColumnWidth(w), barH - 20, mouseX, mouseY);
        }
        if (showingCard) renderCard(g);
    }

    private void renderSpeaker(GuiGraphics g, int x, int y, int w, int h) {
        Font font = Minecraft.getInstance().font;
        int ps = 40;
        g.fill(x, y, x + ps + 4, y + ps + 4, BannerChrome.PLATE_SHADOW);
        BannerChrome.outline(g, x + 1, y + 1, ps + 2, ps + 2, BannerChrome.GOLD_EDGE);
        g.fill(x + 2, y + 2, x + ps + 2, y + ps + 2, BannerChrome.INSET_DARK);
        Entity npc = speaker();
        if (npc instanceof LivingEntity living) {
            float offset = living.getEyeHeight() - living.getBbHeight() / 2.0F + 0.08F;
            InventoryScreen.renderEntityInInventoryFollowsAngle(g, x + 2, y + 2, x + ps + 2, y + ps + 2, 30,
                offset, 0.18F, -0.05F, living);
        }
        int tx = x + ps + 10;
        int relationW = 64;
        int nameW = w - ps - 10 - relationW - 6;
        String name = state.name().getString();
        // Names never cut: the title size, then the heading size, then (rarely) the heading fitted.
        nameText.set(font, name);
        if (nameText.width() <= nameW) {
            nameText.draw(g, font, tx, y + 3, BannerChrome.TEXT_ON_WOOD);
        } else {
            nameSmall.set(font, name);
            if (nameSmall.width() > nameW) nameSmall.fit(font, name, nameW);
            nameSmall.draw(g, font, tx, y + 5, BannerChrome.TEXT_ON_WOOD);
        }
        int infoW = w - ps - 12;
        if (!state.title().getString().isEmpty()) {
            List<FormattedCharSequence> title = font.split(state.title(), infoW - relationW + 60);
            if (!title.isEmpty()) g.drawString(font, title.get(0), tx, y + 20, BannerChrome.TEXT_ON_WOOD_MUTED, false);
        }
        if (!state.memory().getString().isEmpty()) {
            List<FormattedCharSequence> memory = font.split(state.memory(), infoW);
            for (int i = 0; i < Math.min(2, memory.size()); i++) {
                g.drawString(font, memory.get(i), tx, y + 31 + i * 9, Ui2Palette.GOLD_SOFT, false);
            }
        }
        renderRelation(g, font, x + w - relationW, y + 2, relationW);
        int py = y + ps + 10;
        int ph = h - ps - 10;
        BannerChrome.parchment(g, x, py, w, ph);
        renderBody(g, font, x, py, w, ph);
        renderFlourish(g, font, x + w - 40, py + 4);
    }

    /** Typed body text: wraps at word boundaries, follows the typing, scrolls back with the wheel. */
    private void renderBody(GuiGraphics g, Font font, int x, int py, int w, int ph) {
        int budget = cardOnly() ? 0 : shownChars();
        List<FormattedCharSequence> rows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Entity npc = speaker();
        for (int i = 0; i < plainLines.size(); i++) {
            String text = plainLines.get(i);
            if (budget <= 0) break;
            if (i > lastToneLine && npc != null) {
                lastToneLine = i;
                String tone = ConversationVoice.toneTag(state.lines().get(i).getString());
                if (tone != null) ConversationVoice.tone(npc, i, tone);
            }
            String shown = budget >= text.length() ? text : text.substring(0, budget);
            if (budget < text.length()) current.append(shown);
            budget -= text.length();
            // Wrap the whole line, then keep only the typed part, so words never jump between rows.
            int typed = shown.length();
            int used = 0;
            for (FormattedCharSequence part : font.split(Component.literal(text), w - 14)) {
                StringBuilder sb = new StringBuilder();
                part.accept((index, style, cp) -> {
                    sb.appendCodePoint(cp);
                    return true;
                });
                String row = sb.toString();
                if (used >= typed) break;
                int take = Math.min(row.length(), typed - used);
                rows.add(Component.literal(row.substring(0, take)).getVisualOrderText());
                used += row.length();
                while (used < text.length() && text.charAt(used) == ' ') used++;
            }
        }
        // Voice follows the characters being revealed.
        if (npc != null && !typingDone()) {
            ConversationVoice.reveal(npc, plainLines, lineKeys, Math.min(shownChars(), totalChars()));
        }
        int capacity = Math.max(1, (ph - 8) / 10);
        int maxBack = Math.max(0, rows.size() - capacity);
        if (!typingDone()) scrollBack = 0;
        scrollBack = Mth.clamp(scrollBack, 0, maxBack);
        int first = Math.max(0, rows.size() - capacity - scrollBack);
        int ly = py + 5;
        for (int i = first; i < Math.min(rows.size(), first + capacity); i++) {
            g.drawString(font, rows.get(i), x + 7, ly, Ui2Palette.INK, false);
            ly += 10;
        }
        if (first > 0) g.drawString(font, "▴", x + w - 10, py + 3, Ui2Palette.INK_MUTED, false);
        if (first + capacity < rows.size()) g.drawString(font, "▾", x + w - 10, py + ph - 11, Ui2Palette.INK_MUTED, false);
        else if (!typingDone() && (System.nanoTime() / 400_000_000L) % 2 == 0) {
            g.drawString(font, "▸", x + w - 10, py + ph - 11, Ui2Palette.INK_MUTED, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) scrollBack++;
        else if (scrollY < 0) scrollBack = Math.max(0, scrollBack - 1);
        return true;
    }

    private void renderRelation(GuiGraphics g, Font font, int x, int y, int w) {
        Relations.Tier tier = Relations.tier(state.relation());
        Component label = Component.translatable(tier.langKey());
        int color = switch (tier) {
            case HOSTILE -> Ui2Palette.DANGER_HIGHLIGHT;
            case WARY -> Ui2Palette.AMBER;
            case NEUTRAL -> BannerChrome.TEXT_ON_WOOD_MUTED;
            case FRIENDLY, LOYAL -> com.hearthstead.client.ui2.Ui2Hud.GOOD;
        };
        BannerChrome.counterBox(g, x, y, w, 20);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, color, false);
        int barX = x + 5;
        int barW = w - 10;
        g.fill(barX, y + 14, barX + barW, y + 16, Ui2Palette.IRON_DARK);
        int mid = barX + barW / 2;
        int pos = barX + Math.round(Relations.barFraction(state.relation()) * barW);
        g.fill(Math.min(mid, pos), y + 14, Math.max(mid, pos), y + 16, color);
        g.fill(mid, y + 13, mid + 1, y + 17, BannerChrome.TEXT_ON_WOOD_MUTED);
    }

    /** The persuasion seal: a wax disc stamps in, green check or red cross, with the rolled chance. */
    private void renderFlourish(GuiGraphics g, Font font, int x, int y) {
        if (flourish == 0) return;
        float t = (System.nanoTime() - flourishNanos) / 1.0e9F;
        if (t > 3.2F) {
            flourish = 0;
            return;
        }
        float scale = t < 0.25F ? 2.0F - t / 0.25F : 1.0F;
        int color = flourish == 1 ? Ui2Palette.FOREST : Ui2Palette.BURGUNDY;
        g.pose().pushPose();
        g.pose().translate(x + 16, y + 16, 200);
        g.pose().scale(scale, scale, 1.0F);
        for (int r = 12; r >= 0; r--) {
            int half = (int) Math.round(Math.sqrt(12 * 12 - r * r));
            g.fill(-half, -r, half, -r + 1, color);
            g.fill(-half, r, half, r + 1, color);
        }
        int mark = Ui2Palette.ON_BURGUNDY;
        if (flourish == 1) {
            Ui2Surface.checkGlyph(g, -4, -5, mark);
        } else {
            for (int i = -3; i <= 3; i++) {
                g.fill(i, i - 2, i + 1, i - 1, mark);
                g.fill(i, -i - 2, i + 1, -i - 1, mark);
            }
        }
        String pct = flourishChance + "%";
        g.drawString(font, pct, -font.width(pct) / 2, 3, mark, false);
        g.pose().popPose();
    }

    private void renderOptions(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        optionRects.clear();
        if (!optionsVisible()) return;
        Font font = Minecraft.getInstance().font;
        List<OptionView> options = state.options();
        List<List<FormattedCharSequence>> wrapped = wrappedOptions(font, w);
        int gap = 3;
        Component tooltip = null;
        int ry = y;
        for (int i = 0; i < options.size(); i++) {
            OptionView option = options.get(i);
            List<FormattedCharSequence> lines = wrapped.get(i);
            int rowH = rowHeight(lines.size());
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
            if (hover) focus = i;
            boolean selected = i == focus && option.enabled();
            BannerChrome.navPlate(g, x, ry, w, rowH, selected, hover ? 1.0F : 0.0F);
            int textColor = option.enabled() ? BannerChrome.TEXT_ON_WOOD : Ui2Palette.INK_DISABLED;
            int ty = ry + (rowH - lines.size() * OPTION_LINE) / 2 + 1;
            g.drawString(font, (i + 1) + ".", x + 6, ty, option.enabled() ? Ui2Palette.GOLD_SOFT : Ui2Palette.INK_DISABLED, false);
            int right = x + w - 6;
            int midY = ry + rowH / 2;
            if (selected) {
                g.drawString(font, ">", right - 5, midY - 4, BannerChrome.GOLD_EDGE, false);
                right -= 10;
            }
            for (int c = option.costs().size() - 1; c >= 0; c--) {
                ConvStatePayload.CostView cost = option.costs().get(c);
                String amount = String.valueOf(cost.amount());
                int aw = font.width(amount);
                g.drawString(font, amount, right - aw, midY - 4, cost.have() >= cost.amount() ? BannerChrome.GOLD_EDGE
                    : Ui2Palette.DANGER_HIGHLIGHT, false);
                right -= aw + 2;
                g.pose().pushPose();
                g.pose().translate(right - 12, midY - 6, 0);
                g.pose().scale(0.75F, 0.75F, 1.0F);
                g.renderItem(cost.icon(), 0, 0);
                g.pose().popPose();
                right -= 16;
            }
            if (!option.enabled()) Ui2Surface.lockGlyph(g, right - 7, midY - 3, Ui2Palette.INK_DISABLED);
            for (int l = 0; l < lines.size(); l++) {
                g.drawString(font, lines.get(l), x + 20, ty + l * OPTION_LINE, textColor, false);
            }
            optionRects.add(new int[] {x, ry, w, rowH});
            if (hover && !option.enabled()) {
                tooltip = option.reason().getString().isEmpty() ? option.text() : option.reason();
                if (!option.costs().isEmpty()) {
                    ConvStatePayload.CostView cost = option.costs().get(0);
                    tooltip = Component.translatable("conversation.hearthstead.need_have", cost.amount(),
                        cost.icon().getHoverName(), cost.have());
                }
            }
            ry += rowH + gap;
        }
        if (tooltip != null) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    // --------------------------------------------------------------- card ---

    /** Short name card: slides in 0.25 s, holds, then shrinks into the header while the line starts. */
    private void renderCard(GuiGraphics g) {
        Font font = Minecraft.getInstance().font;
        float t = cardSeconds();
        float in = Mth.clamp(t / CARD_IN, 0.0F, 1.0F);
        float out = cardOutNanos < 0 ? 0.0F : Mth.clamp((System.nanoTime() - cardOutNanos) / 1.0e9F / CARD_OUT, 0.0F, 1.0F);
        float ease = out * out * (3.0F - 2.0F * out);
        float a = in * (1.0F - ease);
        if (a < 0.02F) return;
        int bandH = Math.round(Mth.lerp(ease, 50, 16));
        int by = Math.round(Mth.lerp(ease, height * 0.3F, height - barHeight(font, width - 16) - 6));
        int alpha = Math.round(a * 0xC8);
        int bandW = Math.round(width * in * (1.0F - 0.5F * ease));
        int bx = Math.round(Mth.lerp(ease, (width - bandW) / 2.0F, 8));
        g.fill(bx, by, bx + bandW, by + bandH, (alpha << 24) | (Ui2Palette.BURGUNDY_DARK & 0xFFFFFF));
        int hem = (Math.round(a * 255) << 24) | (Ui2Palette.GOLD_SOFT & 0xFFFFFF);
        g.fill(bx, by + 2, bx + bandW, by + 3, hem);
        g.fill(bx, by + bandH - 3, bx + bandW, by + bandH - 2, hem);
        int textAlpha = Math.max(4, Math.round(a * 255)) << 24;
        cardName.set(font, state.name().getString());
        float scale = Mth.lerp(ease, 2.0F, 1.0F);
        g.pose().pushPose();
        float nameX = Mth.lerp(ease, width / 2.0F - cardName.width() * scale / 2.0F, 60.0F);
        g.pose().translate(nameX, by + Mth.lerp(ease, 6.0F, 2.0F), 0);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, cardName.component(), 0, 0, textAlpha | 0xF4E9D8, false);
        g.pose().popPose();
        if (ease < 0.3F) {
            int ly = by + 30;
            if (!state.title().getString().isEmpty()) {
                g.drawCenteredString(font, state.title(), width / 2, ly, textAlpha | (Ui2Palette.GOLD_SOFT & 0xFFFFFF));
            }
            Component extra = !state.record().getString().isEmpty() ? state.record() : state.memory();
            if (!extra.getString().isEmpty()) g.drawCenteredString(font, extra, width / 2, by + bandH + 4, textAlpha | 0xE8DCC4);
        }
    }

    // -------------------------------------------------------------- input ---

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (cardOnly()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(key, scan, modifiers);
            // Keys still held from walking up never skip the card, and nothing skips it in its first 0.3 s.
            boolean movement = key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S
                || key == GLFW.GLFW_KEY_D || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_LEFT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL;
            if (!movement && cardSeconds() > 0.3F) endCard();
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            choose(key - GLFW.GLFW_KEY_1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_9) {
            choose(key - GLFW.GLFW_KEY_KP_1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) {
            focus = Math.min(state.options().size() - 1, focus + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) {
            focus = Math.max(0, focus - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!typingDone()) skipTyping();
            else if (key != GLFW.GLFW_KEY_SPACE) choose(focus);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (cardOnly()) {
            endCard();
            return true;
        }
        if (!typingDone() && state.mode() == 1) {
            skipTyping();
            return true;
        }
        for (int i = 0; i < optionRects.size(); i++) {
            int[] r = optionRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                choose(i);
                return true;
            }
        }
        if (!typingDone()) {
            skipTyping();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void choose(int index) {
        if (!optionsVisible()) {
            skipTyping();
            return;
        }
        if (index < 0 || index >= state.options().size()) return;
        OptionView option = state.options().get(index);
        if (!option.enabled()) return;
        if (pendingRevision == state.revision()) return;
        pendingRevision = state.revision();
        skipTyping();
        ConversationClient.choose(state.session(), state.revision(), index, option.id());
    }
}
