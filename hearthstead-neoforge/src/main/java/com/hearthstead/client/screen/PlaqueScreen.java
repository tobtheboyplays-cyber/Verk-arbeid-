package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.PlaqueAction;
import com.hearthstead.network.PlaqueSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The building plaque's screen: what this building is, what it still needs,
 * who works in it, and who could.
 *
 * <p>Drawn entirely from the server's snapshot. The screen holds no opinion
 * about capacity, eligibility or cost — it renders what it was sent and sends
 * back the revision it drew, so a click on a view the world has already moved
 * past is refused rather than applied.
 *
 * <h2>The hire tab, and the one sentence that makes it different</h2>
 *
 * <p>MineColonies' hire list is a wall of names and numbers, and hiring someone
 * quietly guts the building they came from — you find out the farm has no
 * farmer when the bread stops. Here every candidate is a card that states
 * <b>what taking them costs</b>, in words, before you press anything:
 *
 * <pre>  Bjørn Kvam            ●●●○○        [ Hire ]
 *   Farmhouse
 *   The Farmhouse would have no worker            &lt;- amber</pre>
 *
 * <p>The cost sentence is computed on the server and sent as a key, so it is
 * true and it is in the player's language. Fitness is drawn as pips because you
 * read "three of five" at a glance and never read "48" at a glance.
 *
 * <p>Every control here does something (D-014). A candidate who cannot be hired
 * is drawn disabled <i>with the reason in their tooltip</i> — including the
 * honest one, that no trade is practised in this building yet.
 */
public class PlaqueScreen extends Screen {

    // 256 clipped the hire screen's cost sentence -- "The Carpenter's Shop
    // would have no worker" measured 224px against the 212px COST_BOX it
    // derives (256px is the point of the screen per the class doc, so it
    // wraps and truncating it is the wrong fix). 272 carries COST_BOX to
    // 228px, and as a side effect also clears NAME_BOX past the crowded-
    // settlement "Gislebert the Younger" fallback (111px) with margin.
    private static final int PANEL_W = 272;
    private static final int PAD = 8;
    private static final int SCROLL_W = HsUiTokens.SCROLL_W;
    private static final int CARD_X = PAD;
    private static final int CARD_W = PANEL_W - 2 * PAD - SCROLL_W - 2;
    private static final int CARD_H = 38;
    private static final int CARD_STEP = CARD_H + 4;
    private static final int BTN_W = 64;
    private static final int BTN_X = CARD_X + CARD_W - BTN_W - 8;
    // The footer's own "Survey again" / close pair sit far apart (the close
    // button is pinned to BTN_X, on the card column's right edge) with no
    // shared-width need, so the refresh button gets its own, wider constant:
    // "Survey again" measures 66px and "Mål opp på nytt" 77px against a
    // BTN_W (64) box, both over its 56px label budget.
    private static final int REFRESH_BTN_W = 92;
    private static final int TEXT_X = CARD_X + 10;
    private static final int LIST_TOP = 52;
    /** Rows shown at once. Three keeps the panel inside a 240px-tall GUI. */
    private static final int ROWS = 3;
    private static final int LIST_H = ROWS * CARD_STEP - 4;
    private static final int FOOT = LIST_TOP + LIST_H + 6;
    private static final int PANEL_H = FOOT + 20 + HsUiTokens.BUTTON_H + 10;

    /** The cost line gets the full card width; it is the point of the screen. */
    private static final int NAME_BOX = BTN_X - TEXT_X - 40;
    private static final int POST_BOX = BTN_X - TEXT_X - 6;
    private static final int COST_BOX = CARD_W - 20;

    // A worker's row grows a second, shorter button under Dismiss: 14px tall
    // (there is precedent for compact controls at this height already, e.g.
    // HandbookScreen's page-list dots) fits the remaining 38 - 20 - 4 = 14px
    // under Dismiss's 20px exactly. "Summon"/"Tilkall" measure 36px/28px
    // against its 56px box (BTN_W - 8) with room to spare.
    private static final int SUMMON_BTN_H = 14;

    // Requirements tab only: a local, wider text column that leaves room for
    // a 16x16 representative item icon. Kept separate from TEXT_X/POST_BOX so
    // the Hire tab's already-measured cost-sentence budget (COST_BOX) is
    // untouched by this.
    private static final int REQ_ICON_X = CARD_X + 6;
    private static final int REQ_TEXT_X = REQ_ICON_X + 16 + 6;
    private static final int REQ_BOX = BTN_X - REQ_TEXT_X - 6;

    // Extracted (not invented) from BuildingType's own Requirement.blocks(...)
    // declarations -- the first block listed for each requirement id, wherever
    // it appears, is always the same block, so this is that same association
    // read onto the client rather than a new one. floor_space has no natural
    // single block and is left without an icon. Stacks, not bare Items, and
    // built once rather than allocated per row per frame -- the same idiom
    // PlaqueRenderer's own EMBLEMS cache already uses.
    private static final Map<String, ItemStack> REQUIREMENT_ICONS = Map.ofEntries(
        icon("anvil", Items.ANVIL), icon("beds", Items.RED_BED),
        icon("bell", Items.BELL), icon("bookshelf", Items.BOOKSHELF),
        icon("brewing_stand", Items.BREWING_STAND), icon("cauldron", Items.CAULDRON),
        icon("composter", Items.COMPOSTER), icon("doors", Items.OAK_DOOR),
        icon("dressed_stone", Items.STONE_BRICKS), icon("fletching", Items.FLETCHING_TABLE),
        icon("forge", Items.BLAST_FURNACE), icon("grindstone", Items.GRINDSTONE),
        icon("hay", Items.HAY_BLOCK), icon("hearth_fire", Items.CAMPFIRE),
        icon("ladder", Items.LADDER), icon("lectern", Items.LECTERN),
        icon("lights", Items.TORCH), icon("loom", Items.LOOM),
        icon("oven", Items.FURNACE), icon("sawbench", Items.STONECUTTER),
        icon("smithing_table", Items.SMITHING_TABLE), icon("smoker", Items.SMOKER),
        icon("stall", Items.BARREL), icon("storage", Items.CHEST),
        icon("water", Items.WATER_BUCKET), icon("workbench", Items.CRAFTING_TABLE));

    private static Map.Entry<String, ItemStack> icon(String id, Item item) {
        return Map.entry(id, new ItemStack(item));
    }

    private enum Tab {
        REQUIREMENTS("requirements"),
        PEOPLE("people"),
        HIRE("hire");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        Component label() {
            return Component.translatable("hearthstead.plaque.tab." + key);
        }
    }

    private PlaqueSnapshot snapshot;
    private Tab tab = Tab.REQUIREMENTS;
    private int scroll;
    private int left;
    private int top;
    /** The header emblem -- rebuilt only when the building type changes. */
    private ItemStack emblem = ItemStack.EMPTY;

    public PlaqueScreen(PlaqueSnapshot snapshot) {
        super(Component.translatable("hearthstead.plaque.title"));
        this.snapshot = snapshot;
    }

    /** A fresh snapshot from the server replaces what is on screen. */
    public void update(PlaqueSnapshot fresh) {
        this.snapshot = fresh;
        rebuildEmblem();
        rebuild();
    }

    /** The building's own item stands in for a coat of arms beside its name. */
    private void rebuildEmblem() {
        BuildingType type = snapshot == null ? null : BuildingType.byId(snapshot.buildingType());
        emblem = type == null ? ItemStack.EMPTY : new ItemStack(type.emblem());
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        rebuildEmblem();
        rebuild();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        if (snapshot == null) {
            return;
        }
        int tabW = (PANEL_W - 20 - 2 * 4) / 3;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab which = tabs[i];
            addRenderableWidget(new TabButton(
                left + 10 + i * (tabW + 4), top + 26, tabW, 16,
                which.label(), which == tab, () -> {
                    tab = which;
                    scroll = 0;
                    rebuild();
                }));
        }

        int rows = rowCount();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - ROWS)));

        if (tab == Tab.PEOPLE) {
            buildPeople();
        } else if (tab == Tab.HIRE) {
            buildHire();
        }

        addRenderableWidget(HsButton.normal(left + 10, top + FOOT + 20, REFRESH_BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.refresh"),
            () -> act(PlaqueAction.Kind.REFRESH, new UUID(0, 0))));
        addRenderableWidget(HsButton.normal(left + BTN_X, top + FOOT + 20, BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.close"), this::onClose));
    }

    private void buildPeople() {
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        for (int row = 0; row < ROWS && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Occupant occupant = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            HsButton dismiss = HsButton.danger(left + BTN_X, y + 4, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(PlaqueAction.Kind.EVICT, occupant.id()));
            dismiss.active = snapshot.mayManage();
            dismiss.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.plaque.evict.tip", occupant.name())));
            addRenderableWidget(dismiss);

            // "Come here" is only meaningful for someone who actually holds a
            // post in this building -- a resident with no job here has no
            // post to be called away from, matching the server's own
            // not_employed refusal, so the row is simply left without the
            // button rather than drawn and always refused (D-014).
            if (occupant.worker()) {
                HsButton summon = HsButton.normal(left + BTN_X, y + 4 + HsUiTokens.BUTTON_H,
                    BTN_W, SUMMON_BTN_H,
                    Component.translatable("hearthstead.plaque.summon"),
                    () -> act(PlaqueAction.Kind.SUMMON, occupant.id()));
                summon.active = snapshot.mayManage();
                summon.setTooltip(Tooltip.create(Component.translatable(
                    "hearthstead.plaque.summon.tip", occupant.name())));
                addRenderableWidget(summon);
            }
        }
    }

    private void buildHire() {
        List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
        for (int row = 0; row < ROWS && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Candidate candidate = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean eligible = candidate.blockedReason().isEmpty();
            HsButton hire = HsButton.normal(left + BTN_X, y + 4, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.hire"),
                () -> act(PlaqueAction.Kind.ASSIGN, candidate.id()));
            hire.active = eligible && snapshot.mayManage();
            // A disabled control always says why (D-014).
            hire.setTooltip(Tooltip.create(eligible
                ? Component.translatable("hearthstead.employ.hire.tip",
                    candidate.name())
                : Component.translatable(candidate.blockedReason())));
            addRenderableWidget(hire);
        }
    }

    private int rowCount() {
        return switch (tab) {
            case PEOPLE -> snapshot.occupants().size();
            case HIRE -> snapshot.candidates().size();
            case REQUIREMENTS -> snapshot.requirements().size();
        };
    }

    private void act(PlaqueAction.Kind kind, UUID target) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(new PlaqueAction(
                snapshot.pos(), kind, target, snapshot.revision()));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int rows = rowCount();
        if (rows > ROWS) {
            int before = scroll;
            scroll = Math.max(0, Math.min(rows - ROWS, scroll - (int) Math.signum(dy)));
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
        // super.renderBackground, paired with the renderBackground override
        // below: Screen#render (invoked via super.render further down)
        // always re-runs renderBackground() itself, which would otherwise
        // blur and re-tint this panel's own already-drawn content a second
        // time -- not just the 3D world behind it. See SettlerScreen#render
        // for the full mechanism (UI-BLUR investigation, 2026-08-26); every
        // Hearthstead screen sharing this renderBackground-then-super.render
        // idiom carries the same two-line fix.
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        HsUi.window(graphics, left, top, PANEL_W, PANEL_H);
        if (!emblem.isEmpty()) {
            // The building's own item beside its name -- a small coat of arms,
            // not a functional slot (it never gets a tooltip or a hover state).
            graphics.renderItem(emblem, left + 10, top + 6);
        }
        HsUi.centred(graphics, font, title(), left + PANEL_W / 2, top + 12,
            HsUiTokens.TEXT_STRONG);
        // A double rule instead of one -- the same ink, just given a second
        // hairline of breathing room, the way a title page is ruled off from
        // its body. Both fit between the tabs (ending at top+42) and
        // LIST_TOP (52) with 2px clear on every side.
        HsUi.divider(graphics, left + 10, top + 44, PANEL_W - 20);
        HsUi.divider(graphics, left + 10, top + 48, PANEL_W - 20);

        switch (tab) {
            case REQUIREMENTS -> drawRequirements(graphics);
            case PEOPLE -> drawPeople(graphics, mouseX, mouseY);
            case HIRE -> drawHire(graphics, mouseX, mouseY);
        }

        int rows = rowCount();
        HsUi.scrollbar(graphics, left + PANEL_W - PAD - SCROLL_W, top + LIST_TOP,
            LIST_H, rows == 0 ? 1.0F : Math.min(1.0F, (float) ROWS / rows),
            rows <= ROWS ? 0.0F : (float) scroll / (rows - ROWS), false);

        HsUi.divider(graphics, left + 10, top + FOOT, PANEL_W - 20);
        HsUi.labelIn(graphics, font, footer(), left + 12, top + FOOT + 7,
            PANEL_W - 24, HsUiTokens.ACCENT);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Made inert -- see the comment in {@link #render}. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    private Component title() {
        BuildingType type = BuildingType.byId(snapshot.buildingType());
        return type == null ? Component.translatable("hearthstead.plaque.title")
            : type.displayName();
    }

    /**
     * The footer says the one thing worth saying about this tab. On the hire
     * tab that is the suggestion — and it is a <i>suggestion</i>: the settlement
     * never moves anybody on its own (D-013). The server has already sorted the
     * candidates, so the first one is the recommendation.
     */
    private Component footer() {
        if (tab == Tab.HIRE) {
            List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
            return people.isEmpty()
                ? Component.translatable("hearthstead.employ.no_candidates")
                : Component.translatable("hearthstead.employ.suggested",
                    people.get(0).name());
        }
        if (tab == Tab.PEOPLE) {
            return Component.translatable("hearthstead.plaque.people_count",
                snapshot.occupants().size(), snapshot.capacity());
        }
        return Component.translatable("hearthstead.plaque.level", snapshot.level());
    }

    private void drawRequirements(GuiGraphics graphics) {
        List<PlaqueSnapshot.RequirementLine> lines = snapshot.requirements();
        for (int row = 0; row < ROWS && row + scroll < lines.size(); row++) {
            PlaqueSnapshot.RequirementLine line = lines.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean met = line.have() >= line.needed();
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, false);

            ItemStack icon = REQUIREMENT_ICONS.get(line.id());
            if (icon != null) {
                graphics.renderItem(icon, left + REQ_ICON_X, y + 11);
            }
            // The lang strings carry their own counts ("Storage %s/%s") --
            // the same keys the physical sheet formats -- so the row passes
            // the real numbers instead of showing literal placeholders
            // (found by the UI pass: an argless call rendered "%s/%s").
            // One source of truth; the separate count chip went with it.
            HsUi.labelIn(graphics, font,
                Component.translatable("hearthstead.requirement." + line.id(),
                    line.have(), line.needed()),
                left + REQ_TEXT_X, y + 14,
                REQ_BOX, met ? HsUiTokens.TEXT_STRONG : HsUiTokens.WARN);

            HsUi.pips(graphics, left + BTN_X, y + 10,
                line.needed() == 0 ? 5
                    : Math.min(5, line.have() * 5 / Math.max(1, line.needed())),
                5, met ? HsUi.Tone.GOOD : HsUi.Tone.WARN);
        }
    }

    private void drawPeople(GuiGraphics graphics, int mouseX, int mouseY) {
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        for (int row = 0; row < ROWS && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Occupant occupant = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean hovered = hovering(mouseX, mouseY, y);
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, hovered);
            cardFrame(graphics, y);
            HsUi.labelIn(graphics, font, Component.literal(occupant.name()),
                left + TEXT_X, y + 5, NAME_BOX, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font,
                Component.translatable("hearthstead.profession."
                    + occupant.profession().toLowerCase(java.util.Locale.ROOT)),
                left + TEXT_X, y + 17, POST_BOX, HsUiTokens.TEXT_MUTED);
            float morale = occupant.morale() / 100.0F;
            HsUi.bar(graphics, left + TEXT_X, y + 29, 80, 6, morale,
                HsUi.Tone.of(morale));
        }
    }

    private void drawHire(GuiGraphics graphics, int mouseX, int mouseY) {
        List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
        for (int row = 0; row < ROWS && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Candidate candidate = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean hovered = hovering(mouseX, mouseY, y);
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, hovered);
            cardFrame(graphics, y);
            HsUi.labelIn(graphics, font, Component.literal(candidate.name()),
                left + TEXT_X, y + 5, NAME_BOX, HsUiTokens.TEXT_STRONG);
            HsUi.pips(graphics, left + BTN_X - 36, y + 6, candidate.fitness(), 5,
                HsUi.Tone.ACCENT);
            HsUi.labelIn(graphics, font, currentPost(candidate),
                left + TEXT_X, y + 17, POST_BOX, HsUiTokens.TEXT_MUTED);
            // The sentence that says what taking them costs, on its own row at
            // full card width, amber when a building would be left empty.
            boolean empties = candidate.costKey()
                .equals("hearthstead.employ.cost.leaves_empty");
            HsUi.labelIn(graphics, font, costSentence(candidate),
                left + TEXT_X, y + 28, COST_BOX,
                empties ? HsUiTokens.WARN : HsUiTokens.TEXT_MUTED);
        }
    }

    private Component currentPost(PlaqueSnapshot.Candidate candidate) {
        return candidate.costArg().isEmpty()
            ? Component.translatable("hearthstead.employ.unemployed")
            : Component.translatable(candidate.costArg());
    }

    private Component costSentence(PlaqueSnapshot.Candidate candidate) {
        return candidate.costArg().isEmpty()
            ? Component.translatable(candidate.costKey())
            : Component.translatable(candidate.costKey(),
                Component.translatable(candidate.costArg()));
    }

    /**
     * A thin inset sits inside the card with a 4px margin on every side --
     * matching the nine-slice border width itself, so the card's own edge
     * reads as a deliberate mat around a recessed inner panel rather than a
     * flat rectangle. Drawn under the row's text and buttons (both come
     * after this in render order), using only the existing inset sprite.
     */
    private void cardFrame(GuiGraphics graphics, int cardTop) {
        HsUi.inset(graphics, left + CARD_X + 4, cardTop + 4, CARD_W - 8, CARD_H - 8);
    }

    private boolean hovering(int mouseX, int mouseY, int cardTop) {
        return mouseX >= left + CARD_X && mouseX <= left + CARD_X + CARD_W
            && mouseY >= cardTop && mouseY <= cardTop + CARD_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A tab is a button that looks like a tab and says where you are. */
    private static final class TabButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;

        private TabButton(int x, int y, int w, int h, Component label,
                          boolean selected, Runnable onPress) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            HsUi.tab(graphics, getX(), getY(), getWidth(), getHeight(), selected);
            if (selected) {
                // A quiet engraved highlight, not a new sprite: one
                // low-alpha accent hairline just inside the top edge.
                graphics.fill(getX() + 3, getY() + 1, getX() + getWidth() - 3, getY() + 2,
                    0x40000000 | (HsUiTokens.ACCENT & 0x00FFFFFF));
            }
            var font = net.minecraft.client.Minecraft.getInstance().font;
            HsUi.labelIn(graphics, font, getMessage(),
                getX() + 4, getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                getWidth() - 8,
                selected ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
