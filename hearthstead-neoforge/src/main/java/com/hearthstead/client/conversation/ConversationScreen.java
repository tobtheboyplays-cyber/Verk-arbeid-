package com.hearthstead.client.conversation;

import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.entity.SettlerEntity;
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
        int wanted = Math.max(Math.min(Math.round(height * 0.44F), 200), optionsH + 20);
        int cap = Math.min(Math.round(height * 0.6F), com.hearthstead.client.ui2.BannerSheetLayout.MAX_HEIGHT);
        return Mth.clamp(wanted, 120, Math.max(120, cap));
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
            // Size check (27 Sep): the talk bar keeps the Banner's footprint on big
            // windows instead of spanning the whole screen.
            int w = Math.min(width - 16, com.hearthstead.client.ui2.BannerSheetLayout.MAX_WIDTH);
            int x = (width - w) / 2;
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
            Runnable drawPortrait = () -> InventoryScreen.renderEntityInInventoryFollowsAngle(g,
                x + 2, y + 2, x + ps + 2, y + ps + 2, 30, offset, 0.18F, -0.05F, living);
            if (living instanceof SettlerEntity settler) {
                SettlerRenderer.withoutPortraitLabels(settler, drawPortrait);
            } else {
                drawPortrait.run();
            }
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
        if (distinctTitle()) {
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

    /** QA U7: a subtitle that only repeats the name ("BRUTE CHIEF" / "Brute chief") is not shown. */
    private boolean distinctTitle() {
        String title = state.title().getString().trim();
        return !title.isEmpty() && !title.equalsIgnoreCase(state.name().getString().trim());
    }

    /**
     * The intro name banner (owner-approved reference, 26 Sep: the Caravan
     * Master card, "much cooler, and it matches the person"): a cloth banner
     * in the lower left with a brass medallion carrying the speaker's emblem,
     * a serif name, the title under it, stitched hems, brass fittings, a
     * folded swallowtail end and a pennant under the medallion. Its cloth and
     * emblem follow the speaker (settler: burgundy and job icon; raider:
     * charcoal with red stitching and their weapon; visitor: plum and their
     * ware). It sits under the face, never over it, then fades down as the
     * talk bar comes in.
     */
    private void renderCard(GuiGraphics g) {
        Font font = Minecraft.getInstance().font;
        float t = cardSeconds();
        float in = Mth.clamp(t / CARD_IN, 0.0F, 1.0F);
        float out = cardOutNanos < 0 ? 0.0F : Mth.clamp((System.nanoTime() - cardOutNanos) / 1.0e9F / CARD_OUT, 0.0F, 1.0F);
        float ease = out * out * (3.0F - 2.0F * out);
        float slide = 1.0F - (1.0F - in) * (1.0F - in);
        float a = slide * (1.0F - ease);
        if (a < 0.02F) return;

        Entity npc = speaker();
        int cloth;
        int stitch;
        if (npc instanceof com.hearthstead.entity.RaiderEntity || npc instanceof net.minecraft.world.entity.monster.Enemy) {
            cloth = 0x3B3431;
            stitch = 0xB0483A;
        } else if (npc instanceof com.hearthstead.entity.SettlerEntity) {
            cloth = Ui2Palette.BURGUNDY & 0xFFFFFF;
            stitch = Ui2Palette.GOLD_SOFT & 0xFFFFFF;
        } else {
            cloth = 0x56304C;
            stitch = Ui2Palette.GOLD_SOFT & 0xFFFFFF;
        }
        int dark = shade(cloth, 0.62F);
        int light = shade(cloth, 1.28F);

        cardName.set(font, state.name().getString());
        float nameScale = 1.6F;
        boolean title = distinctTitle();
        int textW = Math.max(Math.round(cardName.width() * nameScale), title ? font.width(state.title()) : 0);
        int bandH = 56;
        int radius = 26;
        int bandW = Mth.clamp(textW + radius * 2 + 40, 200, Math.max(200, Math.min(340, width - 24)));
        int x = Math.round(10 - (1.0F - slide) * 40);
        int y = Math.round(height * 0.64F + ease * 10);
        int mcx = x + radius;
        int mcy = y + bandH / 2;

        // Pennant under the medallion, drawn first so the medallion overlaps it.
        int px0 = mcx - 8;
        int px1 = mcx + 8;
        int pTop = mcy + radius - 6;
        int pBot = pTop + 22;
        for (int yy = pTop; yy < pBot; yy++) {
            int notch = Math.max(0, 6 - (pBot - yy));
            int from = px0 + (yy >= pBot - 6 ? 0 : 0);
            g.fill(from, yy, mcx - notch, yy + 1, col(dark, a));
            g.fill(mcx + notch, yy, px1, yy + 1, col(dark, a));
        }
        g.fill(px0 + 2, pTop + 8, px0 + 3, pTop + 14, col(stitch, a * 0.8F));
        g.fill(px1 - 3, pTop + 8, px1 - 2, pTop + 14, col(stitch, a * 0.8F));
        g.fill(mcx - 1, pTop + 9, mcx + 1, pTop + 11, col(stitch, a));

        // Cloth band with a swallowtail end.
        int bx0 = mcx;
        int bx1 = x + bandW;
        int mid = y + bandH / 2;
        for (int yy = y; yy < y + bandH; yy++) {
            int cut = 9 - Math.round(Math.abs(yy + 0.5F - mid) * 9.0F / (bandH / 2.0F));
            int right = bx1 - Math.max(0, cut);
            int c = yy < y + 2 ? light : yy >= y + bandH - 3 ? dark : cloth;
            g.fill(bx0, yy, right, yy + 1, col(c, a * 0.96F));
        }
        // Woven texture: sparse darker and lighter threads, fixed per position.
        for (int yy = y + 3; yy < y + bandH - 3; yy += 2) {
            for (int xx = bx0 + 2; xx < bx1 - 10; xx += 3) {
                int hsh = (xx * 73856093) ^ (yy * 19349663);
                if ((hsh & 7) == 0) g.fill(xx, yy, xx + 1, yy + 1, col(dark, a * 0.45F));
                else if ((hsh & 15) == 5) g.fill(xx, yy, xx + 1, yy + 1, col(light, a * 0.35F));
            }
        }
        // Stitched hems.
        for (int xx = bx0 + radius - 2; xx < bx1 - 12; xx += 5) {
            g.fill(xx, y + 4, xx + 3, y + 5, col(stitch, a * 0.9F));
            g.fill(xx, y + bandH - 5, xx + 3, y + bandH - 4, col(stitch, a * 0.9F));
        }
        // Folded drape hanging from the right third.
        int fx0 = bx1 - 58;
        int fx1 = bx1 - 20;
        for (int yy = y + bandH; yy < y + bandH + 12; yy++) {
            int k = yy - (y + bandH);
            int l2 = fx0 + k;
            g.fill(l2, yy, fx1 - k / 2, yy + 1, col(k < 2 ? shade(dark, 0.8F) : dark, a * 0.95F));
        }
        for (int xx = fx0 + 6; xx < fx1 - 8; xx += 5) {
            g.fill(xx, y + bandH + 6, xx + 3, y + bandH + 7, col(stitch, a * 0.7F));
        }
        // Brass fittings with rivets.
        fitting(g, bx0 + radius + 2, y - 2, a);
        fitting(g, bx1 - 22, y - 2, a);
        fitting(g, bx1 - 22, y + bandH - 3, a);

        // Medallion: iron rim, brass ring, dark face, the speaker's emblem.
        disc(g, mcx, mcy, radius, col(0x2A2320, a));
        disc(g, mcx, mcy, radius - 1, col(Ui2Palette.GOLD & 0xFFFFFF, a));
        disc(g, mcx, mcy, radius - 3, col(0xE1C58A, a));
        disc(g, mcx, mcy, radius - 4, col(Ui2Palette.GOLD & 0xFFFFFF, a));
        disc(g, mcx, mcy, radius - 7, col(0x6B5226, a));
        disc(g, mcx, mcy, radius - 8, col(0x2E2A27, a));
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0D;
            int rx = mcx + (int) Math.round(Math.cos(ang) * (radius - 2.5D));
            int ry = mcy + (int) Math.round(Math.sin(ang) * (radius - 2.5D));
            g.fill(rx, ry, rx + 1, ry + 1, col(0x3A2A12, a));
        }
        g.fill(mcx - 12, mcy - 15, mcx - 7, mcy - 14, col(0xF2DFAE, a * 0.7F));
        if (a > 0.5F) {
            g.pose().pushPose();
            g.pose().translate(mcx - 14, mcy - 14, 0);
            g.pose().scale(1.75F, 1.75F, 1.0F);
            if (npc instanceof com.hearthstead.entity.SettlerEntity settler) {
                com.hearthstead.client.ui2.JobIcons.draw(g, settler.getProfession(), 0, 0, 16);
            } else {
                g.renderItem(emblemItem(npc), 0, 0);
            }
            g.pose().popPose();
        }

        // Name, title and a small ornament.
        int textAlpha = Math.max(4, Math.round(a * 255)) << 24;
        int tx = bx0 + radius + 8;
        g.pose().pushPose();
        g.pose().translate(tx + 1, y + 8 + 1, 0);
        g.pose().scale(nameScale, nameScale, 1.0F);
        g.drawString(font, cardName.component(), 0, 0, textAlpha | 0x1E1410, false);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(tx, y + 8, 0);
        g.pose().scale(nameScale, nameScale, 1.0F);
        g.drawString(font, cardName.component(), 0, 0, textAlpha | 0xF4E9D8, false);
        g.pose().popPose();
        if (title) {
            g.drawString(font, state.title(), tx, y + 32, textAlpha | (Ui2Palette.GOLD_SOFT & 0xFFFFFF), false);
        }
        int oy = y + bandH - 11;
        int ow = Math.min(textW, 90);
        g.fill(tx, oy, tx + ow / 2 - 3, oy + 1, col(stitch, a * 0.6F));
        g.fill(tx + ow / 2 + 3, oy, tx + ow, oy + 1, col(stitch, a * 0.6F));
        g.fill(tx + ow / 2 - 1, oy - 1, tx + ow / 2 + 1, oy + 2, col(stitch, a));

        Component extra = !state.record().getString().isEmpty() ? state.record() : state.memory();
        if (!extra.getString().isEmpty() && ease < 0.3F) {
            g.drawString(font, extra, tx, y + bandH + 16, textAlpha | 0xE8DCC4, true);
        }
    }

    /** The speaker's emblem when it is not a settler: what they carry, else their kind's sign. */
    private static net.minecraft.world.item.ItemStack emblemItem(Entity npc) {
        if (npc instanceof LivingEntity living && !living.getMainHandItem().isEmpty()) {
            return living.getMainHandItem();
        }
        if (npc instanceof com.hearthstead.entity.RaiderEntity
            || npc instanceof net.minecraft.world.entity.monster.Enemy) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE);
        }
        return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD);
    }

    private static void fitting(GuiGraphics g, int x, int y, float a) {
        g.fill(x, y, x + 8, y + 5, col(0x3A2A12, a));
        g.fill(x + 1, y + 1, x + 7, y + 4, col(Ui2Palette.GOLD & 0xFFFFFF, a));
        g.fill(x + 1, y + 1, x + 7, y + 2, col(0xE1C58A, a));
        g.fill(x + 3, y + 2, x + 5, y + 3, col(0x2A2320, a));
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.floor(Math.sqrt(r * r - dy * dy + 0.5D));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int gg = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return (r << 16) | (gg << 8) | b;
    }

    private static int col(int rgb, float a) {
        return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (rgb & 0xFFFFFF);
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
