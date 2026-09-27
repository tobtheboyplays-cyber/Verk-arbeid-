package com.hearthstead.client.conversation;

import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/**
 * The talk panel (27 Sep, owner: "prettier and sized to the space it needs"):
 * a compact walnut-and-brass frame bottom-centre above the hotbar, dark ink on
 * parchment inside, its width fitted to the content (260-440 GUI px) and its
 * height to the text (at most three speech lines, paged with a small "▸").
 * A 32x32 portrait sits in a brass-rimmed square with the serif name and the
 * role under it; replies are stacked full-width framed buttons numbered 1-9;
 * shared-talk notices go in a thin strip on top. No dimming: the camera
 * frames the speaker above it. Layout only lives here ({@link #layoutFor}).
 *
 * <p>Owner rules: lines type out at ~55 chars/s with a Sims-style babble
 * voice and a talking head ({@link ConversationVoice}, {@link TalkingHead});
 * click or Space/Enter shows the full text. Nothing is cut off: long names
 * step down a type size, replies wrap to two lines, and a long body scrolls
 * (it follows the typing; the mouse wheel scrolls back). Keys 1-9 choose.
 *
 * <p>The first meeting opens with a short name card (about 1.2 s; a raid
 * captain 1.5 s) that shrinks into the header as the first line starts; any
 * key or click skips it. Repeat talks skip the card. The Guildmaster's founding
 * welcome after Raise banner holds its name banner about 3.5 s while the camera
 * turns to him (Esc, a click or any key skips it), then the panel comes in.
 */
public final class ConversationScreen extends Screen {
    private static final float CHARS_PER_SECOND = 55.0F;
    private static final float CARD_IN = 0.25F;
    private static final float CARD_OUT = 0.3F;
    /** The Guildmaster's founding welcome: a longer intro (never more than 4 s of held input). */
    static final float WELCOME_SECONDS = 3.5F;
    static final String SHARED_PREFIX = "conversation.hearthstead.shared.";

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
    /** Per line: a shared-talk notice shown in the strip at the top instead of the speech. */
    private boolean[] stripFlags = new boolean[0];
    private final boolean welcome;
    /** Reveal time per line (ms): the recording's length for voiced lines, else the typing speed. */
    private int[] lineMillis = new int[0];

    public ConversationScreen(ConvStatePayload state) {
        this(state, false);
    }

    public ConversationScreen(ConvStatePayload state, boolean nameCard) {
        this(state, nameCard, false);
    }

    /**
     * @param welcome the founding welcome after Raise banner: the name banner holds
     *                about 3.5 s while the camera turns to the Guildmaster, with a soft
     *                chime; Esc, a click or any key skips it.
     */
    public ConversationScreen(ConvStatePayload state, boolean nameCard, boolean welcome) {
        super(Component.translatable("conversation.hearthstead.screen"));
        this.card = (nameCard || welcome) && com.hearthstead.HearthsteadClientConfig.encounterCinematics();
        this.welcome = welcome && card;
        this.cardTotal = this.welcome ? WELCOME_SECONDS : state.style() == 1 ? 1.5F : 1.2F;
        this.cardStartNanos = System.nanoTime();
        if (card) com.hearthstead.client.sound.HsSound.ui("convo.name_card", null, 0.55F, 1.0F);
        if (this.welcome) {
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                .forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), 0.9F, 0.35F));
        }
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
        stripFlags = new boolean[next.lines().size()];
        for (int i = 0; i < next.lines().size(); i++) stripFlags[i] = stripLine(next.lines().get(i));
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
        // No dimming or blur: the framed speaker stays in full view above the compact panel.
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
        // The panel comes in once the card has faded, so the two never overlap.
        long outNanos = (long) (CARD_OUT * 1.0e9F);
        typingStartNanos = System.nanoTime() + outNanos;
        openNanos = System.nanoTime() + outNanos;
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

    /** Talk panel width limits (GUI px): the panel is fitted to its content between these. */
    static final int MIN_W = 260;
    static final int MAX_W = 440;
    /** The walnut frame around the whole panel. */
    static final int FRAME = 5;
    /** The even gutter used everywhere inside the panel (zones, padding, button spacing). */
    static final int GUTTER = 4;
    /** Walnut frame (5) plus one gutter on each side. */
    static final int INSET = FRAME + GUTTER;
    static final int STRIP_LINE = 9;
    /** Zone 1: the dark walnut header bar (portrait, name and role, relation badge). */
    static final int HEADER_H = 32 + 4 + 2 * GUTTER;
    static final int PORTRAIT = 32;
    /** Zone 2: the parchment text box: inset border, padding, dark ink, at most three lines. */
    static final int TEXT_PAD = GUTTER;
    static final int BODY_LINE = 10;
    static final int BODY_LINES = 3;
    /** Zone 3: the answer area under a thin divider, buttons stacked at an even spacing. */
    static final int ANSWER_PAD = GUTTER;
    static final int BUTTON_H = 14;
    static final int BUTTON_LINE = 9;
    static final int GAP = GUTTER;
    /** Space between the zones. */
    static final int ZONE_GAP = GUTTER;
    /** The relation pill: icon, tier word and the signed standing ("Friendly +12"). */
    static final int RELATION_W = 84;
    static final int BADGE_H = 13;
    /** Clearance kept free above the hotbar, hearts and XP bar. */
    static final int HOTBAR_CLEAR = 42;

    /**
     * Where every part of the talk panel goes. Pure, so the layout test checks it without a window.
     * The three zones are {@code headerBar}, {@code body} (the text box) and {@code answers}; the
     * portrait, name block and relation badge sit inside the header bar, the buttons inside answers.
     */
    record PanelLayout(Rect panel, Rect strip, Rect headerBar, Rect portrait, Rect header, Rect relation, Rect body,
                       int bodyLines, Rect answers, List<Rect> options) {
    }

    static int panelWidth(int screenW, int contentW) {
        int max = Math.min(MAX_W, screenW - 16);
        return Math.max(Math.min(MIN_W, max), Math.min(max, contentW + 2 * INSET));
    }

    /** Wrap width of the speech inside a panel of width {@code panelW} (padding and room for the page arrow). */
    static int wrapWidth(int panelW) {
        return panelW - 2 * INSET - 2 * TEXT_PAD - 9;
    }

    static int buttonHeight(int lines) {
        return BUTTON_H + Math.max(0, lines - 1) * BUTTON_LINE;
    }

    /**
     * @param contentW    the widest part without insets (name block, a reply, a speech line)
     * @param bodyRows    wrapped rows of the whole speech at {@link #wrapWidth}; at most 3 show at once
     * @param optionLines 1 or 2 per reply (a reply never loses a word)
     * @param stripLines  0-2 rows of the shared-talk strip
     */
    static PanelLayout layoutFor(int screenW, int screenH, int contentW, int bodyRows, int[] optionLines, int stripLines) {
        int w = panelWidth(screenW, contentW);
        int innerW = w - 2 * INSET;
        int bodyLines = Math.max(1, Math.min(BODY_LINES, bodyRows));
        int stripH = stripLines > 0 ? Math.min(2, stripLines) * STRIP_LINE + 3 : 0;
        int bodyH = bodyLines * BODY_LINE + 2 * TEXT_PAD - 2;
        int buttonsH = 0;
        for (int i = 0; i < optionLines.length; i++) buttonsH += (i > 0 ? GAP : 0) + buttonHeight(optionLines[i]);
        int answersH = optionLines.length > 0 ? buttonsH + 2 * ANSWER_PAD : 0;
        int top = FRAME + (stripH > 0 ? 1 + stripH + 2 : 0);
        int h = top + HEADER_H + ZONE_GAP + bodyH + ZONE_GAP + (answersH > 0 ? 1 + answersH : 0) + FRAME;
        int x = (screenW - w) / 2;
        int y = Math.max(4, screenH - HOTBAR_CLEAR - h);
        int fx = x + FRAME;
        int fw = w - 2 * FRAME;
        int cx = x + INSET;
        Rect strip = new Rect(fx + 1, y + FRAME + 1, fw - 2, stripH);
        int cy = y + top;
        Rect headerBar = new Rect(fx, cy, fw, HEADER_H);
        Rect portrait = new Rect(cx, cy + (HEADER_H - PORTRAIT - 4) / 2, PORTRAIT + 4, PORTRAIT + 4);
        Rect relation = new Rect(cx + innerW - RELATION_W, cy + (HEADER_H - BADGE_H - 5) / 2, RELATION_W, BADGE_H + 5);
        Rect header = new Rect(portrait.right() + 6, cy + 3, relation.x() - portrait.right() - 12, HEADER_H - 6);
        cy += HEADER_H + ZONE_GAP;
        Rect body = new Rect(cx, cy, innerW, bodyH);
        cy += bodyH + ZONE_GAP;
        Rect answers = new Rect(fx, cy + 1, fw, answersH);
        List<Rect> options = new ArrayList<>();
        int oy = answers.y() + ANSWER_PAD;
        for (int lines : optionLines) {
            int bh = buttonHeight(lines);
            options.add(new Rect(cx, oy, innerW, bh));
            oy += bh + GAP;
        }
        return new PanelLayout(new Rect(x, y, w, h), strip, headerBar, portrait, header, relation, body, bodyLines,
            answers, options);
    }

    private static int costWidth(Font font, OptionView option) {
        int costW = 0;
        for (ConvStatePayload.CostView cost : option.costs()) costW += font.width(String.valueOf(cost.amount())) + 18;
        return costW;
    }

    private List<List<FormattedCharSequence>> wrappedOptions(Font font, int innerW) {
        List<List<FormattedCharSequence>> out = new ArrayList<>();
        for (OptionView option : state.options()) {
            int textW = innerW - 20 - costWidth(font, option) - 14 - (option.enabled() ? 0 : 10);
            List<FormattedCharSequence> lines = font.split(optionText(option), Math.max(40, textW));
            if (lines.size() > 2) {
                // Never cut a word: a third line is folded into the second by widening the wrap once more.
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

    /** Shared-talk notices ("Waiting for …", "… chose: …") go in the strip, not the speech. */
    static boolean stripLine(Component line) {
        return line.getContents() instanceof TranslatableContents t && t.getKey().startsWith(SHARED_PREFIX);
    }

    /** The widest part of the panel's content: name block, a reply, or a speech line. */
    private int contentWidth(Font font) {
        int info = nameText.width();
        if (distinctTitle()) info = Math.max(info, font.width(state.title()));
        if (!state.memory().getString().isEmpty()) info = Math.max(info, Math.min(200, font.width(state.memory())));
        int best = PORTRAIT + 4 + 6 + info + 12 + RELATION_W;
        for (OptionView option : state.options()) {
            best = Math.max(best, 20 + font.width(optionText(option)) + costWidth(font, option) + 16);
        }
        for (int i = 0; i < plainLines.size(); i++) {
            if (!stripFlags[i]) best = Math.max(best, font.width(plainLines.get(i)) + 10);
        }
        return best;
    }

    private int bodyRowCount(Font font, int wrapW) {
        int rows = 0;
        for (int i = 0; i < plainLines.size(); i++) {
            if (!stripFlags[i]) rows += font.split(Component.literal(plainLines.get(i)), wrapW).size();
        }
        return rows;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No dimming and no overlap: the intro card and the panel never show together.
        if (cardShowing()) {
            optionRects.clear();
            renderCard(g);
            return;
        }
        Font font = Minecraft.getInstance().font;
        nameText.set(font, state.name().getString());
        int contentW = contentWidth(font);
        int panelW = panelWidth(width, contentW);
        int innerW = panelW - 2 * INSET;
        List<List<FormattedCharSequence>> wrapped = wrappedOptions(font, innerW);
        int[] optionLines = new int[wrapped.size()];
        for (int i = 0; i < optionLines.length; i++) optionLines[i] = wrapped.get(i).size();
        List<FormattedCharSequence> stripRows = new ArrayList<>();
        for (int i = 0; i < plainLines.size(); i++) {
            if (stripFlags[i]) stripRows.addAll(font.split(state.lines().get(i), panelW - 20));
        }
        if (stripRows.size() > 2) stripRows = stripRows.subList(0, 2);
        PanelLayout l = layoutFor(width, height, contentW, bodyRowCount(font, wrapWidth(panelW)), optionLines,
            stripRows.size());

        float slide = Mth.clamp((System.nanoTime() - openNanos) / 1.0e9F / 0.25F, 0.0F, 1.0F);
        slide = 1.0F - (1.0F - slide) * (1.0F - slide);
        int dy = Math.round((1.0F - slide) * (l.panel().height() + HOTBAR_CLEAR));
        g.pose().pushPose();
        g.pose().translate(0, dy, 0);
        Rect p = l.panel();
        BannerChrome.panel(g, p.x(), p.y(), p.width(), p.height());
        BannerChrome.parchment(g, p.x() + FRAME, p.y() + FRAME, p.width() - 2 * FRAME, p.height() - 2 * FRAME);
        renderZones(g, l);
        if (!stripRows.isEmpty()) {
            Rect s = l.strip();
            g.fill(s.x(), s.y(), s.right(), s.bottom(), Ui2Palette.WALNUT_DARK);
            g.fill(s.x(), s.bottom() - 1, s.right(), s.bottom(), BannerChrome.GOLD_EDGE);
            for (int i = 0; i < stripRows.size(); i++) {
                FormattedCharSequence row = stripRows.get(i);
                g.drawString(font, row, s.x() + (s.width() - font.width(row)) / 2, s.y() + 2 + i * STRIP_LINE,
                    Ui2Palette.GOLD_SOFT, false);
            }
        }
        renderSpeaker(g, font, l);
        renderBody(g, font, l.body(), l.bodyLines());
        renderFlourish(g, font, l.relation().x() - 36, l.header().y() + 2);
        renderOptions(g, font, l, wrapped, mouseX, mouseY - dy);
        g.pose().popPose();
    }

    /**
     * The three zones, drawn before their content so each reads as its own place: a dark walnut
     * header bar with a brass rule under it, an inset parchment text box, and a tinted answer
     * area under a thin divider.
     */
    private static void renderZones(GuiGraphics g, PanelLayout l) {
        Rect hb = l.headerBar();
        g.fill(hb.x(), hb.y(), hb.right(), hb.bottom(), Ui2Palette.WALNUT_DARK);
        g.fill(hb.x(), hb.y(), hb.right(), hb.y() + 1, Ui2Palette.WALNUT);
        for (int gx = hb.x() + 3; gx < hb.right() - 3; gx += 7) {
            int gy = hb.y() + 5 + ((gx * 31) & 7) * 4;
            if (gy < hb.bottom() - 3) g.fill(gx, gy, gx + 4, gy + 1, Ui2Palette.WALNUT_GRAIN);
        }
        g.fill(hb.x(), hb.bottom() - 2, hb.right(), hb.bottom() - 1, BannerChrome.PLATE_SHADOW);
        g.fill(hb.x(), hb.bottom() - 1, hb.right(), hb.bottom(), BannerChrome.GOLD_EDGE);

        Rect b = l.body();
        g.fill(b.x(), b.y(), b.right(), b.bottom(), Ui2Palette.FRAME_INNER);
        g.fill(b.x(), b.y(), b.right(), b.y() + 1, Ui2Palette.INK_MUTED);
        g.fill(b.x(), b.y(), b.x() + 1, b.bottom(), Ui2Palette.INK_MUTED);
        g.fill(b.x() + 1, b.y() + 1, b.right() - 1, b.y() + 2, Ui2Palette.RULE_STRONG);
        g.fill(b.x() + 1, b.y() + 1, b.x() + 2, b.bottom() - 1, Ui2Palette.RULE_STRONG);
        g.fill(b.x(), b.bottom() - 1, b.right(), b.bottom(), Ui2Palette.RULE);
        g.fill(b.right() - 1, b.y(), b.right(), b.bottom(), Ui2Palette.RULE);

        Rect a = l.answers();
        if (a.height() <= 0) return;
        g.fill(a.x(), a.y() - 1, a.right(), a.y(), Ui2Palette.RULE_STRONG);
        g.fill(a.x(), a.y(), a.right(), a.bottom(), 0x2A6E4B32);
        g.fill(a.x(), a.y(), a.right(), a.y() + 1, 0x33FFFFFF);
    }

    /** A 32x32 portrait in a brass-rimmed square; the name in the title font, the role small below. */
    private void renderSpeaker(GuiGraphics g, Font font, PanelLayout l) {
        Rect pr = l.portrait();
        g.fill(pr.x(), pr.y(), pr.right(), pr.bottom(), BannerChrome.PLATE_SHADOW);
        BannerChrome.outline(g, pr.x() + 1, pr.y() + 1, pr.width() - 2, pr.height() - 2, BannerChrome.GOLD_EDGE);
        g.fill(pr.x() + 2, pr.y() + 2, pr.right() - 2, pr.bottom() - 2, BannerChrome.INSET_DARK);
        Entity npc = speaker();
        if (npc instanceof LivingEntity living) {
            float offset = living.getEyeHeight() - living.getBbHeight() / 2.0F + 0.08F;
            Runnable drawPortrait = () -> InventoryScreen.renderEntityInInventoryFollowsAngle(g,
                pr.x() + 2, pr.y() + 2, pr.right() - 2, pr.bottom() - 2, 24, offset, 0.18F, -0.05F, living);
            if (living instanceof SettlerEntity settler) {
                SettlerRenderer.withoutPortraitLabels(settler, drawPortrait);
            } else {
                drawPortrait.run();
            }
        }
        Rect hd = l.header();
        String name = state.name().getString();
        // Names never cut: the title size, then the heading size, then (rarely) the heading fitted.
        if (nameText.width() <= hd.width()) {
            nameText.draw(g, font, hd.x(), hd.y() + 2, BannerChrome.TEXT_ON_WOOD);
        } else {
            nameSmall.set(font, name);
            if (nameSmall.width() > hd.width()) nameSmall.fit(font, name, hd.width());
            nameSmall.draw(g, font, hd.x(), hd.y() + 3, BannerChrome.TEXT_ON_WOOD);
        }
        int ry = hd.y() + 16;
        if (distinctTitle()) {
            List<FormattedCharSequence> title = font.split(state.title(), hd.width());
            if (!title.isEmpty()) g.drawString(font, title.get(0), hd.x(), ry, Ui2Palette.GOLD_SOFT, false);
            ry += 9;
        }
        if (!state.memory().getString().isEmpty() && ry + 8 <= hd.bottom()) {
            List<FormattedCharSequence> memory = font.split(state.memory(), hd.width());
            if (!memory.isEmpty()) g.drawString(font, memory.get(0), hd.x(), ry, BannerChrome.TEXT_ON_WOOD_MUTED, false);
        }
        renderRelation(g, font, l.relation());
    }

    /**
     * Typed speech, at most three lines at once: it wraps at word boundaries, pages
     * with the typing (a small "▸" says more follows), and the wheel pages back.
     */
    private void renderBody(GuiGraphics g, Font font, Rect body, int capacity) {
        int budget = Math.max(0, shownChars());
        List<FormattedCharSequence> rows = new ArrayList<>();
        Entity npc = speaker();
        int wrapW = body.width() - 2 * TEXT_PAD - 9;
        for (int i = 0; i < plainLines.size(); i++) {
            String text = plainLines.get(i);
            if (budget <= 0) break;
            if (i > lastToneLine && npc != null) {
                lastToneLine = i;
                String tone = ConversationVoice.toneTag(state.lines().get(i).getString());
                if (tone != null) ConversationVoice.tone(npc, i, tone);
            }
            int typed = Math.min(budget, text.length());
            budget -= text.length();
            if (stripFlags[i]) continue;
            // Wrap the whole line, then keep only the typed part, so words never jump between rows.
            int used = 0;
            for (FormattedCharSequence part : font.split(Component.literal(text), wrapW)) {
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
            ConversationVoice.reveal(npc, plainLines, lineKeys, Math.max(0, Math.min(shownChars(), totalChars())));
        }
        int lastPage = Math.max(0, (rows.size() - 1) / capacity);
        if (!typingDone()) scrollBack = 0;
        scrollBack = Mth.clamp(scrollBack, 0, lastPage);
        int page = lastPage - scrollBack;
        int first = page * capacity;
        int ly = body.y() + TEXT_PAD;
        for (int i = first; i < Math.min(rows.size(), first + capacity); i++) {
            g.drawString(font, rows.get(i), body.x() + TEXT_PAD + 1, ly, Ui2Palette.INK, false);
            ly += BODY_LINE;
        }
        int ax = body.right() - TEXT_PAD - 6;
        if (page > 0) g.drawString(font, "▴", ax, body.y() + 3, Ui2Palette.INK_MUTED, false);
        if (page < lastPage) {
            g.drawString(font, "▸", ax, body.bottom() - 11, Ui2Palette.INK_SOFT, false);
        } else if (!typingDone() && (System.nanoTime() / 400_000_000L) % 2 == 0) {
            g.drawString(font, "▸", ax, body.bottom() - 11, Ui2Palette.INK_MUTED, false);
        }
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) scrollBack++;
        else if (scrollY < 0) scrollBack = Math.max(0, scrollBack - 1);
        return true;
    }

    /** Badge colour per relation tier: green friendly, amber-grey neutral, red hostile. */
    static int relationColor(Relations.Tier tier) {
        return switch (tier) {
            case HOSTILE -> Ui2Palette.BURGUNDY;
            case WARY -> 0xFF8A5A14;
            case NEUTRAL -> 0xFF7A6C52;
            case FRIENDLY -> Ui2Palette.FOREST;
            case LOYAL -> Ui2Palette.FOREST_DARK;
        };
    }

    /**
     * The relation badge on the right of the header bar: a pill with a small icon and the tier
     * (Hostile, Wary, Neutral, Friendly, Loyal) and a thin bar under it from the centre.
     */
    private void renderRelation(GuiGraphics g, Font font, Rect r) {
        Relations.Tier tier = Relations.tier(state.relation());
        Component label = Component.translatable(tier.langKey());
        int color = relationColor(tier);
        int edge = tier == Relations.Tier.LOYAL ? BannerChrome.GOLD_EDGE : shadeArgb(color, 1.35F);
        int x0 = r.x();
        int x1 = r.right();
        int y0 = r.y();
        int y1 = r.y() + BADGE_H;
        g.fill(x0 + 1, y0, x1 - 1, y1, edge);
        g.fill(x0, y0 + 1, x1, y1 - 1, edge);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, color);
        g.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, shadeArgb(color, 1.18F));
        String number = relationNumber(state.relation());
        int numberW = number.isEmpty() ? 0 : font.width(number) + 4;
        int textW = font.width(label) + numberW;
        int ix = x0 + (r.width() - textW - 10) / 2;
        relationIcon(g, tier, ix, y0 + 3, Ui2Palette.ON_BURGUNDY);
        int tx = g.drawString(font, label, ix + 10, y0 + 3, Ui2Palette.ON_BURGUNDY, false);
        if (!number.isEmpty()) g.drawString(font, number, tx + 3, y0 + 3, 0xFFE2D3B4, false);
        int barX = x0 + 4;
        int barW = r.width() - 8;
        int by = y1 + 2;
        g.fill(barX, by, barX + barW, by + 2, Ui2Palette.WALNUT_LIGHT);
        int mid = barX + barW / 2;
        int pos = barX + Math.round(Relations.barFraction(state.relation()) * barW);
        g.fill(Math.min(mid, pos), by, Math.max(mid, pos), by + 2,
            tier == Relations.Tier.NEUTRAL ? Ui2Palette.GOLD_SOFT : shadeArgb(color, 1.5F));
        g.fill(mid, by - 1, mid + 1, by + 3, BannerChrome.GOLD_EDGE);
    }

    /** The signed standing shown next to the tier word ("+12", "-40"); nothing at exactly 0. */
    static String relationNumber(int relation) {
        int r = Relations.clamp(relation);
        return r == 0 ? "" : (r > 0 ? "+" : "") + r;
    }

    /** 7x7 pixel icons: heart (friendly), heart with a gold dot (loyal), a level bar (neutral), "!" (wary), a cross (hostile). */
    private static void relationIcon(GuiGraphics g, Relations.Tier tier, int x, int y, int c) {
        switch (tier) {
            case FRIENDLY, LOYAL -> {
                g.fill(x + 1, y, x + 3, y + 1, c);
                g.fill(x + 4, y, x + 6, y + 1, c);
                g.fill(x, y + 1, x + 7, y + 3, c);
                g.fill(x + 1, y + 3, x + 6, y + 4, c);
                g.fill(x + 2, y + 4, x + 5, y + 5, c);
                g.fill(x + 3, y + 5, x + 4, y + 6, c);
                if (tier == Relations.Tier.LOYAL) g.fill(x + 3, y + 1, x + 4, y + 2, BannerChrome.GOLD_EDGE);
            }
            case NEUTRAL -> g.fill(x, y + 3, x + 7, y + 5, c);
            case WARY -> {
                g.fill(x + 3, y, x + 5, y + 4, c);
                g.fill(x + 3, y + 5, x + 5, y + 7, c);
            }
            case HOSTILE -> {
                for (int i = 0; i < 6; i++) {
                    g.fill(x + i, y + i, x + i + 2, y + i + 1, c);
                    g.fill(x + 5 - i, y + i, x + 7 - i, y + i + 1, c);
                }
            }
        }
    }

    private static int shadeArgb(int argb, float f) {
        return (argb & 0xFF000000) | shade(argb & 0xFFFFFF, f);
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

    /** Replies: stacked full-width framed buttons (14 px a line), numbered 1-9 for the number keys. */
    private void renderOptions(GuiGraphics g, Font font, PanelLayout l, List<List<FormattedCharSequence>> wrapped,
                               int mouseX, int mouseY) {
        optionRects.clear();
        if (!optionsVisible()) return;
        List<OptionView> options = state.options();
        Component tooltip = null;
        for (int i = 0; i < options.size() && i < l.options().size(); i++) {
            OptionView option = options.get(i);
            List<FormattedCharSequence> lines = wrapped.get(i);
            Rect r = l.options().get(i);
            int x = r.x();
            int ry = r.y();
            int w = r.width();
            int rowH = r.height();
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
            if (hover) focus = i;
            boolean selected = i == focus && option.enabled();
            BannerChrome.navPlate(g, x, ry, w, rowH, selected, hover ? 1.0F : 0.0F);
            int textColor = option.enabled() ? BannerChrome.TEXT_ON_WOOD : Ui2Palette.INK_DISABLED;
            int ty = ry + (rowH - lines.size() * BUTTON_LINE) / 2 + 1;
            numberChip(g, font, i + 1, x + 3, ry + (rowH - 11) / 2, option.enabled(), selected);
            int right = x + w - 6;
            int midY = ry + rowH / 2;
            if (selected) {
                // The arrow marker on the hovered or selected answer.
                for (int k = 0; k < 4; k++) g.fill(right - 4 + k, midY - 3 + k, right - 3 + k, midY + 4 - k, BannerChrome.GOLD_EDGE);
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
            for (int k = 0; k < lines.size(); k++) {
                g.drawString(font, lines.get(k), x + 20, ty + k * BUTTON_LINE, textColor, false);
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
        }
        if (tooltip != null) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }


    /** A small inset chip with the answer's number key (1-9). */
    private static void numberChip(GuiGraphics g, Font font, int n, int x, int y, boolean enabled, boolean selected) {
        int w = 13;
        int h = 11;
        g.fill(x, y, x + w, y + h, selected ? BannerChrome.GOLD_EDGE : BannerChrome.PLATE_SHADOW);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, selected ? Ui2Palette.BURGUNDY_DARK : BannerChrome.INSET_DARK);
        String label = String.valueOf(n);
        g.drawString(font, label, x + (w - font.width(label) + 1) / 2, y + 2,
            enabled ? (selected ? BannerChrome.GOLD_EDGE : Ui2Palette.GOLD_SOFT) : Ui2Palette.INK_DISABLED, false);
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
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                // Esc skips the Guildmaster's intro into his welcome; elsewhere it leaves as before.
                if (!welcome) return super.keyPressed(key, scan, modifiers);
                endCard();
                return true;
            }
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
