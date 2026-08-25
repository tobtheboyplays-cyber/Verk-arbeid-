package com.hearthstead.client.render;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueSheet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * Writes the plaque's survey onto its parchment, in the world.
 *
 * <p>The lamp in the board says <em>whether</em> the room passes. It cannot
 * say <em>what is missing</em> — that the room needs one more lantern, or has
 * no bed at all — and that is the difference between a player who knows what
 * to do next and one who is guessing. This is the part the owner's mockup is
 * really about: <em>"disse arkene som oppdaterer seg når du putter inn mer
 * ting"</em>. Place the bed and the line flips to a tick on the wall, with no
 * click and no command.
 *
 * <p>Nothing is cached here. The sheet is rebuilt from the block entity's
 * synced type, state and survey every frame, so it cannot drift from the
 * server's answer; the plaque remains an access point, never a second source
 * of truth (D-006).
 */
public class PlaqueRenderer implements BlockEntityRenderer<PlaqueBlockEntity> {

    /** One model pixel, in block units. */
    private static final float PX = 1.0F / 16.0F;

    /**
     * The model space this draws into. After translating to the block centre
     * and rotating by the plaque's facing, local +X is the viewer's right,
     * +Y is up and +Z points out of the board — so a model coordinate becomes
     * {@code x: 0.5 - mx/16} (mirrored, because a north face is seen from the
     * north), {@code y: my/16 - 0.5}, {@code z: 0.5 - mz/16}. Every constant
     * below is one of those three conversions applied to a number that is
     * written down in {@code models/block/plaque_base.json}; if that model
     * moves, these move with it.
     */
    private static final float SHEET_Z = 0.5F - 12.9F * PX;

    /** A hair proud of the parchment, so the ink is not inside the panel. */
    private static final float INK_LIFT = 0.0015F;

    /** The brass frame's opening: x 4.3 .. 11.7 of the model. */
    private static final float FIELD_HALF_WIDTH = (11.7F - 4.3F) * 0.5F * PX;

    /**
     * The clear field on the sheet, below the header drawing and its ruled
     * line. The parchment is a 64px texture stretched over the panel's face
     * (model y 4.3 at the bottom to 13.1 at the top), so texture row r sits at
     * model y = 13.1 - r * 8.8/64. The rule is drawn on rows 30-31 by
     * {@code tools/gen_plaque.py}; the field runs from row 32.5 to row 61.5.
     */
    private static final float FIELD_TOP = (13.1F - 32.5F * 8.8F / 64.0F) * PX - 0.5F;
    private static final float FIELD_BOTTOM = (13.1F - 61.5F * 8.8F / 64.0F) * PX - 0.5F;

    /** Clear parchment left around the writing, in block units. */
    private static final float MARGIN_X = 0.012F;
    private static final float MARGIN_Y = 0.006F;

    /** Font units. */
    private static final int LINE = 9;
    private static final int TITLE_GAP = 3;
    private static final int MARK_GAP = 4;

    /**
     * The title is written larger than the list, as the mockup has it. It is
     * the one line that has to survive being read from across the square,
     * where the requirement counts have already dissolved.
     */
    private static final float TITLE_SCALE = 1.35F;

    /**
     * A ceiling on the text size, so a warehouse plaque and a house plaque
     * beside each other are written in the same hand. Only unusually long
     * content shrinks below it.
     */
    private static final float MAX_SCALE = 0.0055F;

    /**
     * Ink under glass catches the plaque's own lamp, so the writing never goes
     * fully dark — but it still sits in the room's light rather than glowing
     * like a sign the player has waxed.
     */
    private static final int MIN_BLOCK_LIGHT = 7;

    /**
     * Text this small is gone well before vanilla's 64-block default, and a
     * village is a lot of plaques. Past this it is the lamp's job anyway.
     */
    private static final int VIEW_DISTANCE = 24;

    private final Font font;

    public PlaqueRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public void render(PlaqueBlockEntity plaque, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        PlaqueSheet sheet = PlaqueSheet.of(plaque.type(), plaque.state(), plaque.lastSurvey());
        if (sheet.isBlank()) {
            return; // no plan fitted: an empty well, and nothing to say
        }

        int titleWidth = font.width(sheet.title());
        float widest = titleWidth * TITLE_SCALE;
        for (PlaqueSheet.Line line : sheet.lines()) {
            widest = Math.max(widest, lineWidth(line));
        }
        float height = LINE * TITLE_SCALE + TITLE_GAP + sheet.lines().size() * LINE;
        if (widest <= 0.0F || height <= 0.0F) {
            return;
        }

        float usableWidth = 2.0F * (FIELD_HALF_WIDTH - MARGIN_X);
        float usableHeight = (FIELD_TOP - FIELD_BOTTOM) - 2.0F * MARGIN_Y;
        float scale = Math.min(MAX_SCALE,
            Math.min(usableWidth / widest, usableHeight / height));

        Direction facing = plaque.getBlockState().getValue(PlaqueBlock.FACING);
        int lit = LightTexture.pack(
            Math.max(LightTexture.block(light), MIN_BLOCK_LIGHT), LightTexture.sky(light));

        pose.pushPose();
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(0.0F, (FIELD_TOP + FIELD_BOTTOM) * 0.5F, SHEET_Z + INK_LIFT);
        // Negative Y scale because font coordinates run downward.
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();

        float top = -height / 2.0F;
        float left = -widest / 2.0F;
        float right = widest / 2.0F;

        pose.pushPose();
        pose.scale(TITLE_SCALE, TITLE_SCALE, 1.0F);
        draw(sheet.title(), -titleWidth / 2.0F, top / TITLE_SCALE,
            PlaqueSheet.TITLE_COLOUR, pose.last().pose(), buffers, lit);
        pose.popPose();

        float y = top + LINE * TITLE_SCALE + TITLE_GAP;
        for (PlaqueSheet.Line line : sheet.lines()) {
            int colour = line.ink().colour();
            draw(line.text(), left, y, colour, matrix, buffers, lit);
            String mark = line.ink().mark();
            if (!mark.isEmpty()) {
                draw(Component.literal(mark), right - font.width(mark), y,
                    colour, matrix, buffers, lit);
            }
            y += LINE;
        }
        pose.popPose();
    }

    private int lineWidth(PlaqueSheet.Line line) {
        int width = font.width(line.text());
        String mark = line.ink().mark();
        return mark.isEmpty() ? width : width + MARK_GAP + font.width(mark);
    }

    private void draw(Component text, float x, float y, int colour, Matrix4f matrix,
                      MultiBufferSource buffers, int light) {
        // No drop shadow: this is ink on parchment, not a HUD label, and a
        // shadow at this size turns every glyph into a smudge.
        font.drawInBatch(text, x, y, colour, false, matrix, buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }
}
