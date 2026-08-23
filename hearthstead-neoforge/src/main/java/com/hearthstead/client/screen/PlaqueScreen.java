package com.hearthstead.client.screen;

import com.hearthstead.network.PlaqueAction;
import com.hearthstead.network.PlaqueSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * The building plaque's screen: what this building is, what it still needs,
 * and who lives or works in it.
 *
 * <p>Drawn entirely from the server's snapshot. The screen holds no opinion
 * about capacity, eligibility or state — it renders what it was sent, and
 * sends back the revision it drew, so a click on a view the world has already
 * moved past is refused rather than applied.
 */
public class PlaqueScreen extends Screen {

    private static final int PANEL_W = 210;
    private static final int PANEL_H = 216;

    // Carved oak, iron, brass, charcoal and parchment — the material language
    // of the plaque itself, so the panel reads as the same object.
    private static final int OAK_DARK = 0xFF2A1E12;
    private static final int OAK = 0xFF3E2C1A;
    private static final int CHARCOAL = 0xFF1B1B1B;
    private static final int CHARCOAL_ROW = 0xFF232323;
    private static final int BRASS = 0xFFB8912F;
    private static final int BRASS_DIM = 0xFF6E571C;
    private static final int PARCHMENT = 0xFFE8E0D0;
    private static final int MUTED = 0xFF8A8578;
    private static final int EMERALD = 0xFF5FA860;
    private static final int AMBER = 0xFFC98A2E;
    private static final int RED = 0xFF8A3A35;

    private PlaqueSnapshot snapshot;
    private boolean showingCandidates;
    private int left;
    private int top;

    public PlaqueScreen(PlaqueSnapshot snapshot) {
        super(Component.translatable("hearthstead.plaque.title"));
        this.snapshot = snapshot;
    }

    /** A fresh snapshot from the server replaces what is on screen. */
    public void update(PlaqueSnapshot fresh) {
        this.snapshot = fresh;
        rebuild();
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        if (snapshot == null) {
            return;
        }
        int rowY = top + 96;

        if (showingCandidates) {
            int shown = 0;
            for (PlaqueSnapshot.Candidate candidate : snapshot.candidates()) {
                if (shown >= 5) {
                    break;
                }
                boolean eligible = candidate.blockedReason().isEmpty();
                Component label = Component.literal(candidate.name())
                    .append(Component.literal("  " + candidate.distance() + "m")
                        .withStyle(ChatFormatting.DARK_GRAY));
                var button = net.minecraft.client.gui.components.Button
                    .builder(label, b -> act(PlaqueAction.Kind.ASSIGN, candidate.id()))
                    .bounds(left + 12, rowY, PANEL_W - 24, 18)
                    .tooltip(eligible ? null
                        : net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(candidate.blockedReason())))
                    .build();
                button.active = eligible && snapshot.mayManage();
                addRenderableWidget(button);
                rowY += 20;
                shown++;
            }
            addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(Component.translatable("hearthstead.plaque.back"),
                    b -> { showingCandidates = false; rebuild(); })
                .bounds(left + 12, top + PANEL_H - 26, PANEL_W - 24, 18).build());
            return;
        }

        for (PlaqueSnapshot.Occupant occupant : snapshot.occupants()) {
            var evict = net.minecraft.client.gui.components.Button
                .builder(Component.translatable("hearthstead.plaque.evict"),
                    b -> act(PlaqueAction.Kind.EVICT, occupant.id()))
                .bounds(left + PANEL_W - 52, rowY + 4, 40, 16)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("hearthstead.plaque.evict.tip",
                        occupant.name())))
                .build();
            evict.active = snapshot.mayManage();
            addRenderableWidget(evict);
            rowY += 24;
        }

        if (snapshot.occupants().size() < snapshot.capacity()) {
            var assign = net.minecraft.client.gui.components.Button
                .builder(Component.translatable("hearthstead.plaque.assign"),
                    b -> { showingCandidates = true; rebuild(); })
                .bounds(left + 12, rowY + 2, PANEL_W - 24, 18).build();
            assign.active = snapshot.mayManage() && !snapshot.candidates().isEmpty();
            addRenderableWidget(assign);
        }

        addRenderableWidget(net.minecraft.client.gui.components.Button
            .builder(Component.translatable("hearthstead.plaque.close"), b -> onClose())
            .bounds(left + PANEL_W / 2 + 4, top + PANEL_H - 26, PANEL_W / 2 - 16, 18).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button
            .builder(Component.translatable("hearthstead.plaque.refresh"),
                b -> act(PlaqueAction.Kind.REFRESH, new UUID(0, 0)))
            .bounds(left + 12, top + PANEL_H - 26, PANEL_W / 2 - 16, 18).build());
    }

    private void act(PlaqueAction.Kind kind, UUID target) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(new PlaqueAction(
                snapshot.pos(), kind, target, snapshot.revision()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // Frame: oak board, brass inner rule, charcoal interior.
        graphics.fill(left - 3, top - 3, left + PANEL_W + 3, top + PANEL_H + 3, OAK_DARK);
        graphics.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, OAK);
        graphics.fill(left + 3, top + 3, left + PANEL_W - 3, top + PANEL_H - 3, BRASS_DIM);
        graphics.fill(left + 4, top + 4, left + PANEL_W - 4, top + PANEL_H - 4, CHARCOAL);
        brassCorners(graphics);

        graphics.drawCenteredString(font,
            Component.translatable("hearthstead.plaque.title"),
            left + PANEL_W / 2, top + 12, PARCHMENT);

        // Identity line: name, level, and a status badge that never relies on
        // colour alone — the word is always there next to the dot.
        Component name = Component.translatable("hearthstead.building." + snapshot.buildingType());
        graphics.drawString(font, name, left + 14, top + 30, PARCHMENT, false);
        graphics.drawString(font, Component.translatable("hearthstead.plaque.level",
            roman(snapshot.level())), left + 14, top + 42, MUTED, false);
        drawBadge(graphics);

        divider(graphics, top + 56);

        if ("linked".equals(snapshot.state())) {
            String header = Component.translatable("hearthstead.plaque.residents").getString()
                + "   " + snapshot.occupants().size() + " / " + snapshot.capacity();
            graphics.drawCenteredString(font, header, left + PANEL_W / 2, top + 66, PARCHMENT);
            if (!showingCandidates) {
                drawOccupants(graphics);
            } else {
                graphics.drawCenteredString(font,
                    Component.translatable("hearthstead.plaque.choose"),
                    left + PANEL_W / 2, top + 84, MUTED);
            }
        } else {
            drawRequirements(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawOccupants(GuiGraphics graphics) {
        int y = top + 96;
        for (PlaqueSnapshot.Occupant occupant : snapshot.occupants()) {
            graphics.fill(left + 12, y, left + PANEL_W - 12, y + 22, CHARCOAL_ROW);
            graphics.fill(left + 12, y, left + 13, y + 22, BRASS_DIM);
            graphics.drawString(font, occupant.name(), left + 18, y + 3, PARCHMENT, false);
            graphics.drawString(font,
                Component.translatable("hearthstead.profession." +
                    occupant.profession().toLowerCase(java.util.Locale.ROOT)),
                left + 18, y + 13, MUTED, false);
            String health = (int) occupant.health() + "/" + (int) occupant.maxHealth();
            graphics.drawString(font, "♥ " + health, left + PANEL_W - 108, y + 3,
                0xFFC0392B, false);
            graphics.drawString(font, mood(occupant.morale()), left + PANEL_W - 108, y + 13,
                moodColour(occupant.morale()), false);
            y += 24;
        }
        if (snapshot.occupants().isEmpty()) {
            graphics.drawCenteredString(font,
                Component.translatable("hearthstead.plaque.empty"),
                left + PANEL_W / 2, y + 6, MUTED);
        }
    }

    /**
     * The requirement list — the answer to "why isn't this a building yet".
     * Each line shows have/needed, so a half-finished requirement reads as
     * progress rather than as a bare failure.
     */
    private void drawRequirements(GuiGraphics graphics) {
        graphics.drawCenteredString(font,
            Component.translatable("hearthstead.plaque.needs"),
            left + PANEL_W / 2, top + 66, PARCHMENT);
        int y = top + 82;
        if (snapshot.requirements().isEmpty()) {
            graphics.drawCenteredString(font,
                Component.translatable("hearthstead.plaque.no_room"),
                left + PANEL_W / 2, y + 8, AMBER);
            return;
        }
        for (PlaqueSnapshot.RequirementLine line : snapshot.requirements()) {
            boolean met = line.have() >= line.needed();
            boolean partial = !met && line.have() > 0;
            int colour = met ? EMERALD : partial ? AMBER : RED;
            String mark = met ? "✔" : partial ? "○" : "✘";
            graphics.drawString(font, mark, left + 16, y, colour, false);
            graphics.drawString(font,
                Component.translatable("hearthstead.requirement." + line.id()),
                left + 30, y, met ? PARCHMENT : MUTED, false);
            String count = line.have() + " / " + line.needed();
            graphics.drawString(font, count,
                left + PANEL_W - 16 - font.width(count), y, colour, false);
            y += 14;
        }
    }

    private void drawBadge(GuiGraphics graphics) {
        String state = snapshot.state();
        int colour = switch (state) {
            case "linked" -> EMERALD;
            case "incomplete" -> AMBER;
            default -> RED;
        };
        Component label = Component.translatable("hearthstead.plaque.state." + state);
        int w = font.width(label);
        int x = left + PANEL_W - 16 - w;
        graphics.fill(x - 10, top + 28, x + w + 4, top + 40, CHARCOAL_ROW);
        graphics.fill(x - 7, top + 32, x - 3, top + 36, colour);
        graphics.drawString(font, label, x, top + 30, colour, false);
    }

    private void divider(GuiGraphics graphics, int y) {
        graphics.fill(left + 14, y, left + PANEL_W - 14, y + 1, BRASS_DIM);
        graphics.fill(left + PANEL_W / 2 - 2, y - 2, left + PANEL_W / 2 + 2, y + 3, BRASS);
    }

    private void brassCorners(GuiGraphics graphics) {
        int[][] corners = {
            {left + 4, top + 4}, {left + PANEL_W - 12, top + 4},
            {left + 4, top + PANEL_H - 12}, {left + PANEL_W - 12, top + PANEL_H - 12}};
        for (int[] c : corners) {
            graphics.fill(c[0], c[1], c[0] + 8, c[1] + 2, BRASS);
            graphics.fill(c[0], c[1], c[0] + 2, c[1] + 8, BRASS);
        }
    }

    private static String mood(int morale) {
        if (morale >= 70) {
            return Component.translatable("hearthstead.mood.happy").getString();
        }
        if (morale >= 40) {
            return Component.translatable("hearthstead.mood.content").getString();
        }
        return Component.translatable("hearthstead.mood.unhappy").getString();
    }

    private static int moodColour(int morale) {
        return morale >= 70 ? EMERALD : morale >= 40 ? 0xFF9AA36B : RED;
    }

    private static String roman(int level) {
        return switch (level) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "I";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
