package com.hearthstead.client.screen;

import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.client.ui2.Ui2WoodKey;
import com.hearthstead.network.ResearchActionPayload;
import com.hearthstead.network.ResearchSnapshotPayload;
import com.hearthstead.settlement.research.ResearchProject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.util.FormattedCharSequence;
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
 *
 * <p>Chrome is the standard Bannerhold window ({@link Ui2Frame}): serif
 * title with the research progress as its subtitle, the wood close key, the
 * hero as an inset strip, projects as rows with a text Choose action.
 */
public class ResearchScreen extends Screen implements QaUiInspectable {

    static final int PANEL_W = 460;
    static final int PANEL_H = 338;
    /** Choose/Cancel column: fits the label plus the disabled padlock. */
    static final int BTN_W = 56;
    static final int HERO_H = 60;
    static final int CARD_H = 50;
    static final int CARD_STEP = 54;
    static final int MAX_ROWS = 3;
    /** Scrollbar lane at the right of the list (1px rule, 3px thumb). */
    static final int SCROLL_W = 4;
    static final int ICON_OFF = 3;
    static final int TEXT_OFF = ICON_OFF + 16 + 3;
    static final int HERO_TEXT_OFF = 8;
    private ResearchLayout layout = layoutFor(PANEL_W + 16, PANEL_H + 16);

    static ResearchLayout layoutFor(int viewportWidth, int viewportHeight) {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(viewportWidth, viewportHeight, PANEL_W, PANEL_H, true);
        Rect content = frame.content();
        Rect hero = new Rect(content.x(), content.y(), content.width(), HERO_H);
        int listTop = hero.bottom() + Ui2FrameLayout.M;
        int rows = Math.max(1, Math.min(MAX_ROWS, (content.bottom() - listTop + 4) / CARD_STEP));
        int listHeight = rows * CARD_STEP - 4;
        Rect list = new Rect(content.x(), listTop, content.width() - SCROLL_W - 2, listHeight);
        Rect scrollbar = new Rect(content.right() - 3, listTop, 3, listHeight);
        int buttonX = list.right() - BTN_W;
        return new ResearchLayout(frame, rows, hero, list, scrollbar, buttonX,
            buttonX - (list.x() + TEXT_OFF) - 4, hero.width() - HERO_TEXT_OFF * 2);
    }

    /**
     * Absolute screen geometry. {@code textBox} is the project text column
     * left of the Choose column; {@code heroTextBox} the hero's full-width lines.
     */
    record ResearchLayout(Ui2FrameLayout frame, int visibleRows, Rect hero, Rect list, Rect scrollbar,
                          int buttonX, int textBox, int heroTextBox) {
        int left() {
            return frame.x();
        }

        int top() {
            return frame.y();
        }

        int panelWidth() {
            return frame.width();
        }

        int panelHeight() {
            return frame.height();
        }

        int listTop() {
            return list.y();
        }

        int listHeight() {
            return list.height();
        }

        int cardWidth() {
            return list.width();
        }

        /** Card {@code row} of the visible list. */
        Rect card(int row) {
            return new Rect(list.x(), list.y() + row * CARD_STEP, list.width(), CARD_H);
        }

        /** Choose for card {@code row}: a text button, vertically centred, right of the text column. */
        Rect choose(int row) {
            Rect c = card(row);
            return new Rect(buttonX, c.y() + (CARD_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, BTN_W,
                Ui2FrameLayout.TEXT_BUTTON_H);
        }

        /** Cancel on the hero strip, in the same column as Choose. */
        Rect cancel() {
            return new Rect(buttonX, hero.y() + 6, BTN_W, Ui2FrameLayout.BUTTON_H);
        }
    }

    private ResearchSnapshotPayload snapshot;
    /** Immutable presentation for the current snapshot/font/language/layout. */
    private ResearchRenderView renderView;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Ui2WoodKey closeKey;
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
        layout = layoutFor(width, height);
        left = layout.left();
        top = layout.top();
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
        rebuild(true, focusedTarget());
    }

    private void rebuild(boolean revealFocusedProject, FocusTarget retained) {
        clearWidgets();
        setFocused(null);
        closeKey = null;
        if (snapshot == null) {
            return;
        }
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, ResearchProject.BY_ORDINAL.length - layout.visibleRows())));
        if (revealFocusedProject && retained != null
            && retained.kind() == FocusKind.PROJECT) {
            reveal(retained.ordinal());
        }

        if (snapshot.activeOrdinal() >= 0) {
            Rect r = layout.cancel();
            Action cancel = new Action(r, Component.translatable("hearthstead.research.cancel"),
                Ui2Button.Variant.DANGER, () -> act(ResearchActionPayload.Kind.CANCEL, 0), FocusTarget.cancel());
            Ui2Tips.enable(cancel, snapshot.mayManage(),
                Component.translatable("hearthstead.research.cancel.tip"),
                Component.translatable("hearthstead.research.blocked.read_only"));
            addRenderableWidget(cancel);
        }

        for (int row = 0; row < layout.visibleRows(); row++) {
            int ordinal = row + scroll;
            if (ordinal >= ResearchProject.BY_ORDINAL.length) {
                break;
            }
            ResearchProject project = ResearchProject.BY_ORDINAL[ordinal];
            String blocked = blockedReason(project);
            Action choose = new Action(layout.choose(row), Component.translatable("hearthstead.research.choose"),
                Ui2Button.Variant.SECONDARY, () -> act(ResearchActionPayload.Kind.START, project.ordinal()),
                FocusTarget.project(ordinal));
            Ui2Tips.enable(choose, blocked.isEmpty() && snapshot.mayManage(),
                Component.translatable("hearthstead.research.choose.tip", project.displayName()),
                !snapshot.mayManage() ? Component.translatable("hearthstead.research.blocked.read_only")
                    : Component.translatable(blocked));
            addRenderableWidget(choose);
        }

        // The old footer Close button is the standard wood close key; Esc still closes.
        closeKey = addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
        restoreFocus(retained);
    }

    private enum FocusKind { PROJECT, CANCEL, CLOSE }

    private record FocusTarget(FocusKind kind, int ordinal) {
        static FocusTarget project(int ordinal) {
            return new FocusTarget(FocusKind.PROJECT, ordinal);
        }
        static FocusTarget cancel() { return new FocusTarget(FocusKind.CANCEL, -1); }
        static FocusTarget close() { return new FocusTarget(FocusKind.CLOSE, -1); }
    }

    private FocusTarget focusedTarget() {
        GuiEventListener focused = getFocused();
        if (focused instanceof Action button) return button.focusTarget;
        if (focused != null && focused == closeKey) return FocusTarget.close();
        return null;
    }

    private void reveal(int ordinal) {
        if (ordinal < 0 || ordinal >= ResearchProject.BY_ORDINAL.length) return;
        if (ordinal < scroll) scroll = ordinal;
        else if (ordinal >= scroll + layout.visibleRows()) {
            scroll = ordinal - layout.visibleRows() + 1;
        }
        scroll = Math.max(0, Math.min(scroll,
            ResearchProject.BY_ORDINAL.length - layout.visibleRows()));
    }

    private void restoreFocus(FocusTarget target) {
        setFocused(null);
        if (target == null) return;
        if (target.kind() == FocusKind.CLOSE) {
            if (closeKey != null) setFocused(closeKey);
            return;
        }
        for (GuiEventListener child : children()) {
            if (child instanceof Action button && button.active && button.visible
                && target.equals(button.focusTarget)) {
                setFocused(button);
                return;
            }
        }
        // An accepted Start disables Choose; an accepted Cancel removes Cancel.
        // Do not transfer a held/repeated Enter to a different action in either case.
    }

    private boolean isCurrentChild(GuiEventListener target) {
        if (target == null) return false;
        for (GuiEventListener child : children()) {
            if (child == target) return true;
        }
        return false;
    }

    @Override
    protected void rebuildWidgets() {
        FocusTarget retained = focusedTarget();
        super.rebuildWidgets();
        rebuild(true, retained);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled && minecraft != null && minecraft.screen == this
            && getFocused() != null && !isCurrentChild(getFocused())) {
            restoreFocus(focusedTarget());
        }
        return handled;
    }

    private static String qaFocus(FocusTarget target) {
        if (target == null) return "none";
        return target.kind() == FocusKind.PROJECT ? "project:" + target.ordinal()
            : target.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Empty when a project can be chosen right now; otherwise why not (D-014). */
    private String blockedReason(ResearchProject project) {
        if (snapshot.completedOrdinals().contains(project.ordinal())) {
            return "hearthstead.research.blocked.done";
        }
        if (snapshot.activeOrdinal() >= 0) {
            return "hearthstead.research.blocked.busy";
        }
        if (!project.newStartsSupported()) {
            return "hearthstead.research.blocked.unreleased";
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
        if (total > layout.visibleRows()) {
            int before = scroll;
            scroll = Math.max(0, Math.min(total - layout.visibleRows(), scroll - (int) Math.signum(dy)));
            if (before != scroll) {
                rebuild(false, focusedTarget());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int next = switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> scroll - layout.visibleRows();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> scroll + layout.visibleRows();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> 0;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> ResearchProject.BY_ORDINAL.length;
            default -> Integer.MIN_VALUE;
        };
        if (next != Integer.MIN_VALUE) {
            FocusTarget retained = focusedTarget();
            int row = retained != null && retained.kind() == FocusKind.PROJECT
                ? Math.max(0, Math.min(layout.visibleRows() - 1, retained.ordinal() - scroll)) : 0;
            scroll = Math.max(0, Math.min(ResearchProject.BY_ORDINAL.length - layout.visibleRows(), next));
            int ordinal = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME ? 0
                : keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_END
                    ? ResearchProject.BY_ORDINAL.length - 1 : scroll + row;
            // Page navigation moves inspection only. A locked Choose stays disabled
            // and unfocused; no fallback Cancel/Close action receives repeated Enter.
            rebuild(false, FocusTarget.project(ordinal));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------- drawing ---

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(graphics, 4.0F, () -> render(graphics, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }
        ResearchRenderView view = renderView();
        Ui2Frame.draw(graphics, layout.frame());
        Ui2Frame.title(graphics, font, layout.frame(), titleText, title.getString(), view.progress());

        drawHero(graphics, view.hero());

        drawProjects(graphics, mouseX, mouseY, view.projects());

        int total = ResearchProject.BY_ORDINAL.length;
        Rect bar = layout.scrollbar();
        if (total > layout.visibleRows()) {
            Ui2Surface.scrollbar(graphics, bar.x() + 1, bar.y(), bar.height(),
                Math.min(1.0F, (float) layout.visibleRows() / total),
                (float) scroll / (total - layout.visibleRows()));
        }
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (mouseX >= layout.list().x() && mouseX < layout.buttonX() - 4) {
            for (int row = 0; row < layout.visibleRows(); row++) {
                int ordinal = projectOrdinalForRow(scroll, row);
                if (ordinal < view.projects().size() && hovering(mouseX, mouseY, row)) {
                    graphics.renderTooltip(font, view.projects().get(ordinal).tooltip(), mouseX, mouseY);
                    return;
                }
            }
        }
        Rect hero = layout.hero();
        if (!view.hero().empty() && mouseX >= hero.x() && mouseX < layout.buttonX() - 4
            && mouseY >= hero.y() && mouseY < hero.bottom()) {
            // Complete source text is cached independently of fitted display labels.
            graphics.renderTooltip(font, view.hero().tooltip(), mouseX, mouseY);
        }
    }

    /** The active project as an inset strip with a forest state bar; empty is a quiet status. */
    private void drawHero(GuiGraphics graphics, HeroRenderView hero) {
        Rect r = layout.hero();
        if (hero.empty()) {
            Ui2Frame.status(graphics, font, r, hero.emptyLabel().text(), Ui2Frame.Tone.NEUTRAL);
            return;
        }
        graphics.fill(r.x(), r.y(), r.right(), r.bottom(), Ui2Palette.INSET);
        graphics.fill(r.x(), r.y(), r.x() + 2, r.bottom(), Ui2Palette.FOREST);
        int x = r.x() + HERO_TEXT_OFF;
        graphics.renderItem(hero.emblem(), x - 2, r.y() + 4);
        graphics.drawString(font, hero.name().text(), x + 18, r.y() + 5, Ui2Palette.INK, false);
        graphics.drawString(font, hero.effect().text(), x, r.y() + 17, Ui2Palette.INK_SOFT, false);

        int sessions = hero.sessions();
        int workDays = hero.workDays();
        float ratio = workDays <= 0 ? 0.0F : (float) sessions / workDays;
        Ui2Surface.progress(graphics, x, r.y() + 31, layout.heroTextBox(), ratio, Ui2Palette.FOREST);
        dayMarks(graphics, x, r.y() + 29, layout.heroTextBox(), 6, workDays);
        graphics.drawString(font, hero.progress().text(), x, r.y() + 38, Ui2Palette.INK_MUTED, false);
        graphics.drawString(font, hero.paid().text(), x, r.y() + 49, Ui2Palette.INK_MUTED, false);
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
            graphics.fill(markX, y + 1, markX + 1, y + h - 1, Ui2Palette.RULE_STRONG);
        }
    }

    private void drawProjects(GuiGraphics graphics, int mouseX, int mouseY,
                              List<ProjectRenderView> projects) {
        for (int row = 0; row < layout.visibleRows(); row++) {
            int ordinal = projectOrdinalForRow(scroll, row);
            if (ordinal >= projects.size()) {
                break;
            }
            ProjectRenderView project = projects.get(ordinal);
            Rect card = layout.card(row);
            boolean hovered = hovering(mouseX, mouseY, row);
            Ui2Surface.row(graphics, card.x(), card.y(), card.width(), CARD_H, hovered ? 1.0F : 0.0F, false);
            if (row + 1 < layout.visibleRows() && ordinal + 1 < projects.size()) {
                Ui2Surface.rule(graphics, card.x(), card.bottom() + 1, card.width());
            }
            int textX = card.x() + TEXT_OFF;
            graphics.renderItem(project.emblem(), card.x() + ICON_OFF, card.y() + 5);

            graphics.drawString(font, project.name().text(), textX, card.y() + 5, project.nameColour(), false);
            graphics.drawString(font, project.effect().text(), textX, card.y() + 17, Ui2Palette.INK_SOFT, false);

            if (project.done()) {
                Ui2Surface.checkGlyph(graphics, textX, card.y() + 31, Ui2Palette.FOREST);
                graphics.drawString(font, project.doneLabel().text(), textX + 10, card.y() + 30,
                    Ui2Palette.FOREST, false);
            } else {
                drawCosts(graphics, project.costs(), textX, card.y() + 30);
            }
        }
    }

    /** Itemised, each cost line coloured by whether the study (or hearth)
     *  currently holds enough of it — have/need, exactly {@link
     *  PlaqueScreen}'s requirement chips. */
    private void drawCosts(GuiGraphics graphics, List<CostRenderView> costs, int x, int y) {
        for (int index = 0, size = costs.size(); index < size; index++) {
            CostRenderView cost = costs.get(index);
            graphics.drawString(font, cost.label().text(), x, y + cost.offset(), cost.colour(), false);
            graphics.drawString(font, cost.amount(), x + layout.textBox() - cost.amountWidth(),
                y + cost.offset(), cost.colour(), false);
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
            Component.translatable("hearthstead.research.footer.progress", snapshot.completedOrdinals().size(),
                ResearchProject.BY_ORDINAL.length),
            buildHeroView(currentFont), List.copyOf(projects));
    }

    private HeroRenderView buildHeroView(Font currentFont) {
        int ordinal = snapshot.activeOrdinal();
        if (ordinal < 0) {
            return new HeroRenderView(null, HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.research.hero.empty"), layout.heroTextBox() - 8),
                null, null, null, null, 0, 0, List.of());
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
        Component progressText = scholarPart.append("   ")
            .append(Component.translatable("hearthstead.research.hero.sessions", sessions, workDays));
        HsUi.FittedLabel progress = HsUi.fitLabel(currentFont, progressText, layout.heroTextBox());
        int heroX = layout.hero().x() + HERO_TEXT_OFF;
        return new HeroRenderView(new ItemStack(project.emblem()), null,
            HsUi.fitLabel(currentFont, project.displayName(), layout.buttonX() - heroX - 24),
            HsUi.fitLabel(currentFont, project.effectSentence(),
                layout.buttonX() - heroX - 6), progress,
            paidLabel(currentFont, project), sessions, workDays,
            wrapTooltip(currentFont,
                List.of(project.displayName(), project.effectSentence(), progressText, paidText(project))));
    }

    private ProjectRenderView buildProjectView(Font currentFont, ResearchProject project,
                                               int ordinal) {
        boolean done = snapshot.completedOrdinals().contains(ordinal);
        return new ProjectRenderView(new ItemStack(project.emblem()),
            HsUi.fitLabel(currentFont, project.displayName(), layout.textBox()),
            HsUi.fitLabel(currentFont, project.effectSentence(), layout.textBox()), done,
            done ? Ui2Palette.INK_MUTED : Ui2Palette.INK,
            done ? HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.research.blocked.done"), layout.textBox() - 10) : null,
            done ? List.of() : buildCostViews(currentFont, project, ordinal),
            wrapTooltip(currentFont, projectTooltip(project, ordinal)));
    }

    /** Keep complete facts while fitting the actual viewport; paid once per render-view key. */
    private List<FormattedCharSequence> wrapTooltip(Font currentFont, List<Component> source) {
        int wrapWidth = Math.min(260, Math.max(80, layout.panelWidth() - 32));
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : source) wrapped.addAll(currentFont.split(line, wrapWidth));
        return List.copyOf(wrapped);
    }

    private List<Component> projectTooltip(ResearchProject project, int ordinal) {
        List<Component> lines = new ArrayList<>();
        lines.add(project.displayName());
        lines.add(project.effectSentence());
        String blocked = blockedReason(project);
        if (!blocked.isEmpty()) {
            lines.add(Component.translatable(blocked));
        }
        List<Integer> haves = snapshot.costHaves().get(ordinal);
        for (int i = 0; i < project.costs().size(); i++) {
            ResearchProject.Cost cost = project.costs().get(i);
            lines.add(Component.literal(new ItemStack(cost.item()).getHoverName().getString()
                + " " + haves.get(i) + "/" + cost.count()));
        }
        if (!snapshot.mayManage()) {
            lines.add(Component.translatable("hearthstead.research.blocked.read_only"));
        }
        return List.copyOf(lines);
    }

    private List<CostRenderView> buildCostViews(Font currentFont, ResearchProject project,
                                                int ordinal) {
        List<Integer> haves = snapshot.costHaves().get(ordinal);
        List<ResearchProject.Cost> costs = project.costs();
        List<CostRenderView> lines = new ArrayList<>(costs.size());
        for (int index = 0; index < costs.size(); index++) {
            ResearchProject.Cost cost = costs.get(index);
            int have = haves.get(index);
            Component amount = Component.literal(have + "/" + cost.count());
            int amountWidth = currentFont.width(amount);
            HsUi.FittedLabel label = HsUi.fitLabel(currentFont,
                new ItemStack(cost.item()).getHoverName(),
                Math.max(1, layout.textBox() - amountWidth - 6));
            // Have/need is always written; colour only repeats it.
            int colour = have >= cost.count() ? Ui2Palette.FOREST : Ui2Palette.AMBER;
            lines.add(new CostRenderView(label, amount, amountWidth, index * 10, colour));
        }
        return List.copyOf(lines);
    }

    private HsUi.FittedLabel paidLabel(Font currentFont, ResearchProject project) {
        return HsUi.fitLabel(currentFont, paidText(project), layout.heroTextBox());
    }

    private Component paidText(ResearchProject project) {
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
        return paid;
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
                                      Component progress, HeroRenderView hero,
                                      List<ProjectRenderView> projects) {
    }

    private record HeroRenderView(ItemStack emblem, HsUi.FittedLabel emptyLabel,
                                  HsUi.FittedLabel name, HsUi.FittedLabel effect,
                                  HsUi.FittedLabel progress, HsUi.FittedLabel paid,
                                  int sessions, int workDays, List<FormattedCharSequence> tooltip) {
        boolean empty() {
            return emblem == null;
        }
    }

    private record ProjectRenderView(ItemStack emblem, HsUi.FittedLabel name,
                                     HsUi.FittedLabel effect, boolean done,
                                     int nameColour, HsUi.FittedLabel doneLabel,
                                     List<CostRenderView> costs, List<FormattedCharSequence> tooltip) {
    }

    private record CostRenderView(HsUi.FittedLabel label, Component amount, int amountWidth,
                                  int offset, int colour) {
    }

    @Override
    public String qaUiState() {
        if (snapshot == null || renderView == null
            || renderView.snapshot() != snapshot) return "loading";
        return "revision=" + snapshot.revision()
            + ",readOnly=" + !snapshot.mayManage()
            + ",projects=" + renderView.projects().size()
            + ",visible=" + layout.visibleRows() + ",scroll=" + scroll
            + ",active=" + snapshot.activeOrdinal()
            + ",sessions=" + snapshot.activeSessions()
            + ",focus=" + qaFocus(focusedTarget())
            + ",focusCurrentChild=" + isCurrentChild(getFocused())
            + ",focusActive=" + (getFocused() instanceof AbstractWidget widget
                && widget.active && widget.visible)
            + ",panel=" + left + ":" + top + ":"
            + layout.panelWidth() + ":" + layout.panelHeight();
    }

    private boolean hovering(int mouseX, int mouseY, int row) {
        Rect card = layout.card(row);
        return mouseX >= card.x() && mouseX <= card.right()
            && mouseY >= card.y() && mouseY <= card.bottom();
    }

    /** The server answers nothing once the block is gone; close instead of showing dead buttons. */
    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.level == null || snapshot == null) return;
        if (minecraft.level.isLoaded(snapshot.pos())
            && minecraft.level.getBlockState(snapshot.pos()).isAir()) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A kit button that remembers which project (or Cancel) it acts on, for focus restore. */
    private static final class Action extends Ui2Button {
        private final FocusTarget focusTarget;

        private Action(Rect r, Component label, Variant variant, Runnable action, FocusTarget focusTarget) {
            super(r.x(), r.y(), r.width(), r.height(), label, variant, action);
            this.focusTarget = focusTarget;
        }
    }
}
