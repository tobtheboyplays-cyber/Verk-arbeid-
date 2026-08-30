package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.ResearchActionPayload;
import com.hearthstead.network.ResearchSnapshotPayload;
import com.hearthstead.settlement.research.ResearchProject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Prøvebenken's own screen: the study's one active project as a hero, the
 * six it could take up below.
 *
 * <p>Drawn entirely from the server's snapshot and from {@link
 * ResearchProject}'s own constants — the same split {@link PlaqueScreen}
 * uses, and for the same reason: what a project needs and what it does are
 * common code both sides already load, so the wire only ever carries what
 * genuinely varies (what has been gathered, what is finished, what is under
 * way). Every click sends back the revision this screen drew, so a press
 * made against a view the world has already moved past is refused rather
 * than applied.
 *
 * <h2>D-014</h2>
 *
 * <p>A project's Choose button is disabled with the reason in its tooltip
 * whenever it cannot be pressed right now — already researched, a project
 * already under way, or the study (and the hearth behind it) simply does not
 * hold enough of what it costs. Cancel is drawn only while a project exists
 * to cancel, and its own tooltip states the refund plainly before anyone
 * presses it.
 */
public class ResearchScreen extends Screen {

    // 280 clipped a project's effect sentence -- "Farmed crops: grow 15%
    // more often." measured 183px against the 150px box that width derived
    // (verified with tools/ui_preview.py against research_{en,nb}.json,
    // the offline-preview workflow tools/mcfont.py backs). 320 carries that
    // box to 190px, and the Norwegian worst case (row1_effect,
    // "Vaktene: trener styrke 10 % raskere.") clears it with margin too.
    private static final int PANEL_W = 320;
    private static final int PAD = 8;
    private static final int SCROLL_W = HsUiTokens.SCROLL_W;
    private static final int CARD_X = PAD;
    private static final int CARD_W = PANEL_W - 2 * PAD - SCROLL_W - 2;
    private static final int BTN_W = 64;
    private static final int BTN_X = CARD_X + CARD_W - BTN_W - 8;

    private static final int HERO_TOP = 54;
    private static final int HERO_H = 58;

    private static final int CARD_H = 50;
    private static final int CARD_STEP = CARD_H + 4;
    /** Three keeps the panel inside a reasonable GUI height, the same
     *  discipline {@link PlaqueScreen} uses for its own hire list. */
    private static final int ROWS = 3;
    private static final int LIST_TOP = HERO_TOP + HERO_H + 12;
    private static final int LIST_H = ROWS * CARD_STEP - 4;
    private static final int FOOT = LIST_TOP + LIST_H + 6;
    private static final int PANEL_H = FOOT + 20 + HsUiTokens.BUTTON_H + 10;

    private static final int ICON_X = CARD_X + 6;
    private static final int TEXT_X = ICON_X + 16 + 6;
    private static final int TEXT_BOX = BTN_X - TEXT_X - 6;
    private static final int HERO_TEXT_X = CARD_X + 10;
    private static final int HERO_TEXT_BOX = CARD_W - 20;

    private ResearchSnapshotPayload snapshot;
    /** Immutable presentation for the current snapshot/font/language/layout. */
    private ResearchRenderView renderView;
    private int scroll;
    private int left;
    private int top;
    private boolean uiSoundActive;
    public ResearchScreen(ResearchSnapshotPayload snapshot) {
        super(Component.translatable("hearthstead.research.title"));
        this.snapshot = snapshot;
    }

    /** A fresh snapshot from the server replaces what is on screen. */
    public void update(ResearchSnapshotPayload fresh) {
        this.snapshot = fresh;
        this.renderView = null;
        rebuild();
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        renderView = null;
        rebuild();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    @Override
    public void removed() {
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        if (snapshot == null) {
            return;
        }
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, ResearchProject.BY_ORDINAL.length - ROWS)));

        if (snapshot.activeOrdinal() >= 0) {
            HsButton cancel = HsButton.danger(left + BTN_X, top + HERO_TOP + 6, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.research.cancel"),
                () -> act(ResearchActionPayload.Kind.CANCEL, 0));
            cancel.active = snapshot.mayManage();
            cancel.setTooltip(Tooltip.create(
                Component.translatable("hearthstead.research.cancel.tip")));
            addRenderableWidget(cancel);
        }

        for (int row = 0; row < ROWS; row++) {
            int ordinal = row + scroll;
            if (ordinal >= ResearchProject.BY_ORDINAL.length) {
                break;
            }
            ResearchProject project = ResearchProject.BY_ORDINAL[ordinal];
            int y = top + LIST_TOP + row * CARD_STEP;
            String blocked = blockedReason(project);
            HsButton choose = HsButton.normal(left + BTN_X, y + (CARD_H - HsUiTokens.BUTTON_H) / 2,
                BTN_W, HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.research.choose"),
                () -> act(ResearchActionPayload.Kind.START, project.ordinal()));
            choose.active = blocked.isEmpty() && snapshot.mayManage();
            choose.setTooltip(Tooltip.create(blocked.isEmpty()
                ? Component.translatable("hearthstead.research.choose.tip", project.displayName())
                : Component.translatable(blocked)));
            addRenderableWidget(choose);
        }

        addRenderableWidget(HsButton.normal(left + BTN_X, top + FOOT + 20, BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.close"), this::onClose));
    }

    /** Empty when a project can be chosen right now; otherwise why not (D-014). */
    private String blockedReason(ResearchProject project) {
        if (snapshot.completedOrdinals().contains(project.ordinal())) {
            return "hearthstead.research.blocked.done";
        }
        if (snapshot.activeOrdinal() >= 0) {
            return "hearthstead.research.blocked.busy";
        }
        List<Integer> haves = snapshot.costHaves().get(project.ordinal());
        List<ResearchProject.Cost> costs = project.costs();
        for (int i = 0; i < costs.size(); i++) {
            if (haves.get(i) < costs.get(i).count()) {
                return "hearthstead.research.blocked.materials";
            }
        }
        return "";
    }

    private void act(ResearchActionPayload.Kind kind, int projectOrdinal) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(new ResearchActionPayload(
                snapshot.pos(), kind, projectOrdinal, snapshot.revision()));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int total = ResearchProject.BY_ORDINAL.length;
        if (total > ROWS) {
            int before = scroll;
            scroll = Math.max(0, Math.min(total - ROWS, scroll - (int) Math.signum(dy)));
            if (before != scroll) {
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }
        ResearchRenderView view = renderView();
        HsUi.window(graphics, left, top, PANEL_W, PANEL_H);
        graphics.renderItem(view.studyEmblem(), left + 10, top + 6);
        HsUi.label(graphics, font, view.title().text(),
            left + PANEL_W / 2 - view.title().width() / 2, top + 12,
            HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, left + 10, top + 26, PANEL_W - 20);
        HsUi.divider(graphics, left + 10, top + 30, PANEL_W - 20);

        drawHero(graphics, view.hero());
        HsUi.divider(graphics, left + 10, top + LIST_TOP - 8, PANEL_W - 20);
        drawProjects(graphics, mouseX, mouseY, view.projects());

        int total = ResearchProject.BY_ORDINAL.length;
        HsUi.scrollbar(graphics, left + PANEL_W - PAD - SCROLL_W, top + LIST_TOP, LIST_H,
            Math.min(1.0F, (float) ROWS / total),
            total <= ROWS ? 0.0F : (float) scroll / (total - ROWS), false);

        HsUi.divider(graphics, left + 10, top + FOOT, PANEL_W - 20);
        HsUi.label(graphics, font, view.footer().text(), left + 12, top + FOOT + 7,
            HsUiTokens.ACCENT);
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
    }

    private void drawHero(GuiGraphics graphics, HeroRenderView hero) {
        int cardTop = top + HERO_TOP;
        HsUi.card(graphics, left + CARD_X, cardTop, CARD_W, HERO_H, false);

        if (hero.empty()) {
            HsUi.label(graphics, font, hero.emptyLabel().text(),
                left + HERO_TEXT_X, cardTop + 24, HsUiTokens.TEXT_MUTED);
            return;
        }
        graphics.renderItem(hero.emblem(), left + HERO_TEXT_X - 2, cardTop + 4);
        HsUi.label(graphics, font, hero.name().text(),
            left + HERO_TEXT_X + 18, cardTop + 5, HsUiTokens.TEXT_STRONG);
        HsUi.label(graphics, font, hero.effect().text(),
            left + HERO_TEXT_X, cardTop + 17, HsUiTokens.ACCENT);

        int sessions = hero.sessions();
        int workDays = hero.workDays();
        float ratio = workDays <= 0 ? 0.0F : (float) sessions / workDays;
        HsUi.bar(graphics, left + HERO_TEXT_X, cardTop + 30, HERO_TEXT_BOX, 6, ratio,
            HsUi.Tone.ACCENT);
        dayMarks(graphics, left + HERO_TEXT_X, cardTop + 30, HERO_TEXT_BOX, 6, workDays);
        HsUi.label(graphics, font, hero.progress().text(),
            left + HERO_TEXT_X, cardTop + 40, HsUiTokens.TEXT_MUTED);
        HsUi.label(graphics, font, hero.paid().text(),
            left + HERO_TEXT_X, cardTop + 50, HsUiTokens.TEXT_MUTED);
    }

    /** Thin notches at each work-day boundary — measured in real screen
     *  pixels (1px, opaque), never in sub-pixel world units, so they are
     *  never the invisible-line trap. */
    private void dayMarks(GuiGraphics graphics, int x, int y, int w, int h, int workDays) {
        if (workDays <= 1) {
            return;
        }
        for (int i = 1; i < workDays; i++) {
            int markX = x + Math.round((float) (w - 2) * i / workDays) + 1;
            graphics.fill(markX, y + 1, markX + 1, y + h - 1, 0x80241A0E);
        }
    }

    private void drawProjects(GuiGraphics graphics, int mouseX, int mouseY,
                              List<ProjectRenderView> projects) {
        for (int row = 0; row < ROWS; row++) {
            int ordinal = projectOrdinalForRow(scroll, row);
            if (ordinal >= projects.size()) {
                break;
            }
            ProjectRenderView project = projects.get(ordinal);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean hovered = hovering(mouseX, mouseY, y);
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, hovered);
            graphics.renderItem(project.emblem(), left + ICON_X, y + 5);

            HsUi.label(graphics, font, project.name().text(), left + TEXT_X, y + 5,
                project.nameColour());
            HsUi.label(graphics, font, project.effect().text(), left + TEXT_X, y + 17,
                HsUiTokens.TEXT_MUTED);

            if (project.done()) {
                HsUi.label(graphics, font, project.doneLabel().text(),
                    left + TEXT_X, y + 30, HsUiTokens.GOOD);
            } else {
                drawCosts(graphics, project.costs(), left + TEXT_X, y + 30);
            }
        }
    }

    /** Itemised, each cost line coloured by whether the study (or hearth)
     *  currently holds enough of it — have/need, exactly {@link
     *  PlaqueScreen}'s requirement chips. */
    private void drawCosts(GuiGraphics graphics, List<CostRenderView> costs, int x, int y) {
        for (int index = 0, size = costs.size(); index < size; index++) {
            CostRenderView cost = costs.get(index);
            HsUi.label(graphics, font, cost.label().text(), x + cost.offset(), y,
                cost.colour());
        }
    }

    /**
     * Builds presentation only when an input that can change fitted text or
     * rendered items changes.  In particular, {@link #scroll} is absent from
     * the key: scrolling selects cards already present in this view.
     */
    private ResearchRenderView renderView() {
        Font currentFont = font;
        String language = net.minecraft.client.Minecraft.getInstance()
            .getLanguageManager().getSelected();
        int layoutWidth = width;
        if (renderView == null || !renderViewInputsMatch(renderView.snapshot(),
            renderView.revision(), renderView.font(), renderView.language(),
            renderView.layoutWidth(), snapshot, snapshot.revision(), currentFont,
            language, layoutWidth)) {
            renderView = buildRenderView(currentFont, language, layoutWidth);
        }
        return renderView;
    }

    private ResearchRenderView buildRenderView(Font currentFont, String language,
                                                int layoutWidth) {
        List<ProjectRenderView> projects = new ArrayList<>(ResearchProject.BY_ORDINAL.length);
        for (int ordinal = 0; ordinal < ResearchProject.BY_ORDINAL.length; ordinal++) {
            projects.add(buildProjectView(currentFont, ResearchProject.BY_ORDINAL[ordinal],
                ordinal));
        }
        return new ResearchRenderView(snapshot, snapshot.revision(), currentFont, language,
            layoutWidth,
            new ItemStack(com.hearthstead.building.BuildingType.ARCHITECTS_STUDY.emblem()),
            HsUi.fitLabel(currentFont, Component.translatable("hearthstead.research.title"),
                Integer.MAX_VALUE),
            HsUi.fitLabel(currentFont, Component.translatable(
                "hearthstead.research.footer.progress", snapshot.completedOrdinals().size(),
                ResearchProject.BY_ORDINAL.length), PANEL_W - 24),
            buildHeroView(currentFont), List.copyOf(projects));
    }

    private HeroRenderView buildHeroView(Font currentFont) {
        int ordinal = snapshot.activeOrdinal();
        if (ordinal < 0) {
            return new HeroRenderView(null, HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.research.hero.empty"), HERO_TEXT_BOX),
                null, null, null, null, 0, 0);
        }
        ResearchProject project = ResearchProject.BY_ORDINAL[ordinal];
        int sessions = snapshot.activeSessions();
        int workDays = project.workDays();
        // Scholar and session count share one line -- squeezing the count
        // beside the bar left too little width for a Norwegian worst case
        // ("arbeidsdager"), so it moved to its own full-width line instead.
        MutableComponent scholarPart = snapshot.scholarName().isEmpty()
            ? Component.translatable("hearthstead.research.hero.no_scholar")
            : Component.translatable("hearthstead.research.hero.scholar", snapshot.scholarName());
        HsUi.FittedLabel progress = HsUi.fitLabel(currentFont, scholarPart.append("   ")
            .append(Component.translatable("hearthstead.research.hero.sessions", sessions,
                workDays)), HERO_TEXT_BOX);
        return new HeroRenderView(new ItemStack(project.emblem()), null,
            HsUi.fitLabel(currentFont, project.displayName(), HERO_TEXT_BOX - 18),
            HsUi.fitLabel(currentFont, project.effectSentence(), HERO_TEXT_BOX), progress,
            paidLabel(currentFont, project), sessions, workDays);
    }

    private ProjectRenderView buildProjectView(Font currentFont, ResearchProject project,
                                               int ordinal) {
        boolean done = snapshot.completedOrdinals().contains(ordinal);
        return new ProjectRenderView(new ItemStack(project.emblem()),
            HsUi.fitLabel(currentFont, project.displayName(), TEXT_BOX),
            HsUi.fitLabel(currentFont, project.effectSentence(), TEXT_BOX), done,
            done ? HsUiTokens.TEXT_MUTED : HsUiTokens.TEXT_STRONG,
            done ? HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.research.blocked.done"), TEXT_BOX) : null,
            done ? List.of() : buildCostViews(currentFont, project, ordinal));
    }

    private List<CostRenderView> buildCostViews(Font currentFont, ResearchProject project,
                                                int ordinal) {
        List<Integer> haves = snapshot.costHaves().get(ordinal);
        List<ResearchProject.Cost> costs = project.costs();
        List<CostRenderView> lines = new ArrayList<>(costs.size());
        int cursor = 0;
        int remaining = TEXT_BOX;
        for (int index = 0; index < costs.size(); index++) {
            ResearchProject.Cost cost = costs.get(index);
            int have = haves.get(index);
            Component text = Component.literal(new ItemStack(cost.item()).getHoverName().getString()
                + " " + have + "/" + cost.count());
            int box = Math.min(remaining, 120);
            HsUi.FittedLabel label = HsUi.fitLabel(currentFont, text, box);
            int drawn = Math.min(currentFont.width(text), box);
            int colour = have >= cost.count() ? HsUiTokens.GOOD : HsUiTokens.WARN;
            lines.add(new CostRenderView(label, cursor, colour));
            cursor += drawn + 10;
            remaining -= drawn + 10;
            if (remaining <= 0) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    private HsUi.FittedLabel paidLabel(Font currentFont, ResearchProject project) {
        MutableComponent paid = Component.translatable("hearthstead.research.hero.paid_prefix")
            .append(" ");
        List<ResearchProject.Cost> costs = project.costs();
        for (int index = 0; index < costs.size(); index++) {
            if (index > 0) {
                paid.append(", ");
            }
            ResearchProject.Cost cost = costs.get(index);
            paid.append(Component.literal(new ItemStack(cost.item()).getHoverName().getString()))
                .append(" ×")
                .append(Component.literal(Integer.toString(cost.count())));
        }
        return HsUi.fitLabel(currentFont, paid, HERO_TEXT_BOX);
    }

    /** Package-visible for the cache contract test; scroll is deliberately not an input. */
    static boolean renderViewInputsMatch(Object cachedSnapshot, int cachedRevision,
                                         Object cachedFont, String cachedLanguage,
                                         int cachedLayoutWidth, Object snapshot, int revision,
                                         Object font, String language, int layoutWidth) {
        return cachedSnapshot == snapshot
            && cachedRevision == revision
            && cachedFont == font
            && Objects.equals(cachedLanguage, language)
            && cachedLayoutWidth == layoutWidth;
    }

    /** Maps a visible row to its cached project; no view rebuild belongs here. */
    static int projectOrdinalForRow(int scroll, int row) {
        return scroll + row;
    }

    private record ResearchRenderView(ResearchSnapshotPayload snapshot, int revision,
                                      Font font, String language, int layoutWidth,
                                      ItemStack studyEmblem, HsUi.FittedLabel title,
                                      HsUi.FittedLabel footer, HeroRenderView hero,
                                      List<ProjectRenderView> projects) {
    }

    private record HeroRenderView(ItemStack emblem, HsUi.FittedLabel emptyLabel,
                                  HsUi.FittedLabel name, HsUi.FittedLabel effect,
                                  HsUi.FittedLabel progress, HsUi.FittedLabel paid,
                                  int sessions, int workDays) {
        boolean empty() {
            return emblem == null;
        }
    }

    private record ProjectRenderView(ItemStack emblem, HsUi.FittedLabel name,
                                     HsUi.FittedLabel effect, boolean done,
                                     int nameColour, HsUi.FittedLabel doneLabel,
                                     List<CostRenderView> costs) {
    }

    private record CostRenderView(HsUi.FittedLabel label, int offset, int colour) {
    }

    private boolean hovering(int mouseX, int mouseY, int cardTop) {
        return mouseX >= left + CARD_X && mouseX <= left + CARD_X + CARD_W
            && mouseY >= cardTop && mouseY <= cardTop + CARD_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
