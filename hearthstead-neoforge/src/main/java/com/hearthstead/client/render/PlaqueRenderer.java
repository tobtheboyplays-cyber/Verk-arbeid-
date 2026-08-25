package com.hearthstead.client.render;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueSheet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

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
     * What is actually VISIBLE of the parchment: the frame's opening, model
     * y 4.55..12.85. The panel behind it runs 4.3..13.1, and anything drawn
     * out there disappears under the brass.
     */
    private static final float OPENING_TOP = 12.85F * PX - 0.5F;
    private static final float OPENING_BOTTOM = 4.55F * PX - 0.5F;

    /**
     * How far the emblem stands off the parchment. Much bigger than the ink's
     * lift, because some emblems are real block models with depth -- a chest
     * and a lectern are not flat -- and they have to clear the sunken panel
     * rather than sink into it.
     */
    private static final float EMBLEM_LIFT = 0.018F;

    /**
     * Depth, squashed hard. There is only 0.044 of a block between the sunken
     * panel and the front of the brass frame; a chest at its natural depth
     * would push straight through it and out of the block. At a tenth it still
     * reads as a solid and stays inside the well.
     */
    private static final float EMBLEM_DEPTH = 0.15F;

    /**
     * The most of the sheet the writing may take. The rest belongs to the
     * picture — so a registered building, whose sheet is two lines, gives its
     * picture most of the parchment, and a plaque still gathering its
     * requirements gives it what is left over. The picture used to be pinned
     * to a fixed band whatever else was on the sheet, and at that size the
     * owner could not tell one plan from another.
     */
    private static final float TEXT_SHARE = 0.5F;

    /** Clear parchment between the picture and the first line of writing. */
    private static final float PICTURE_GAP = 0.018F;

    /**
     * Ruled lines: one under the title, dividing the emblem and the heading
     * from the list, and a fainter one between each requirement. They cost a
     * pixel of height each and they are what turns a stack of words into a
     * page -- the eye gets rows to follow instead of a paragraph.
     *
     * <p>Drawn with {@link RenderType#textBackground()}, which is the render
     * type vanilla uses for the dark box behind a nameplate: no texture, plain
     * vertex colour with alpha, and lit like everything else on the sheet. It
     * means the rules need no asset at all.
     *
     * <p>The thickness is in FONT units, not pixels, so it scales with the
     * writing. A first attempt used 0.9 of a unit and lifted the quads 0.0008
     * of a block off the parchment: at a normal viewing distance that is two
     * thirds of a screen pixel, sitting close enough to the panel to z-fight,
     * and the rules were invisible in game while being perfectly present in
     * the code. Thin geometry has to be measured in screen pixels, not in
     * intent.
     */
    private static final float RULE_THICKNESS = 1.6F;
    private static final int RULE_UNDER_TITLE = 0xC8;
    private static final int RULE_BETWEEN_ROWS = 0x66;
    private static final float RULE_LIFT = 0.0040F;

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

    /**
     * One item per building type, kept as a stack so nothing is allocated per
     * frame per plaque.
     */
    private static final Map<BuildingType, ItemStack> EMBLEMS =
        new EnumMap<>(BuildingType.class);

    static {
        for (BuildingType type : BuildingType.values()) {
            EMBLEMS.put(type, new ItemStack(type.emblem()));
        }
    }

    private final Font font;
    private final ItemRenderer items;

    public PlaqueRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.items = context.getItemRenderer();
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public void render(PlaqueBlockEntity plaque, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        PlaqueSheet sheet = PlaqueSheet.of(plaque.type(), plaque.state(),
            plaque.lastSurvey(), plaque.occupants(), plaque.capacity());
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
        float opening = OPENING_TOP - OPENING_BOTTOM;
        float scale = Math.min(MAX_SCALE, Math.min(usableWidth / widest,
            opening * TEXT_SHARE / height));

        // The writing sits at the FOOT of the sheet; everything above it is
        // the picture's.
        float textBottom = OPENING_BOTTOM + MARGIN_Y;
        float textTop = textBottom + height * scale;

        Direction facing = plaque.getBlockState().getValue(PlaqueBlock.FACING);
        int lit = LightTexture.pack(
            Math.max(LightTexture.block(light), MIN_BLOCK_LIGHT), LightTexture.sky(light));

        pose.pushPose();
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        float pictureTop = OPENING_TOP - MARGIN_Y;
        float pictureBottom = textTop + PICTURE_GAP;
        float side = Math.min(pictureTop - pictureBottom, usableWidth);
        if (side > 0.02F) {
            drawEmblem(plaque, pose, buffers, lit,
                (pictureTop + pictureBottom) * 0.5F, side);
        }

        drawRules(pose, buffers, lit, sheet.lines().size(), textTop, widest, scale);

        pose.translate(0.0F, (textTop + textBottom) * 0.5F, SHEET_Z + INK_LIFT);
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

    /**
     * Renders the plan's emblem: the real Minecraft item, exactly the way an
     * item frame renders one.
     *
     * <p>{@link ItemDisplayContext#GUI} on purpose, not {@code FIXED}. FIXED is
     * what an item frame uses, and it was the obvious first choice -- but it
     * hangs a bed the way a frame does, edge-on and two blocks long, and
     * squashed into a sheet this shallow the bed collapsed to a strip of
     * planks. GUI is the pose every player has seen ten thousand times: the
     * icon in their own hotbar. A chest looks like the chest in slot one; a
     * bed looks like the bed in slot two. There is no more recognisable way to
     * draw an item than the way the player already keeps it.
     *
     * <p>Seven versions of hand-drawn art came before this and every one was
     * worse, for the same reason each time: a sprite this mod authors is
     * competing with art the player has been looking at for years. The item
     * itself wins on recognition, on sitting consistently beside everything
     * else on screen, and on the day a seventh building type is designed and
     * needs no new art at all.
     */
    private void drawEmblem(PlaqueBlockEntity plaque, PoseStack pose,
                            MultiBufferSource buffers, int light,
                            float centreY, float side) {
        ItemStack emblem = EMBLEMS.get(plaque.type());
        if (emblem == null || emblem.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.0F, centreY, SHEET_Z + EMBLEM_LIFT);
        pose.scale(side, side, side * EMBLEM_DEPTH);
        items.renderStatic(emblem, ItemDisplayContext.GUI, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, plaque.getLevel(), 0);
        pose.popPose();
    }

    /**
     * The ruled lines, drawn in block units before the text's own flipped
     * scale is applied -- so the quads keep an honest winding and the
     * arithmetic reads the same way the layout does.
     */
    private void drawRules(PoseStack pose, MultiBufferSource buffers, int light,
                           int rows, float textTop, float widest, float scale) {
        VertexConsumer rules = buffers.getBuffer(RenderType.textBackground());
        float half = widest * 0.5F * scale;
        float thickness = RULE_THICKNESS * scale;
        float headed = (LINE * TITLE_SCALE + TITLE_GAP * 0.5F) * scale;
        rule(rules, pose, -half, half, textTop - headed, thickness,
            RULE_UNDER_TITLE, light);

        float listTop = (LINE * TITLE_SCALE + TITLE_GAP) * scale;
        for (int row = 1; row < rows; row++) {
            rule(rules, pose, -half, half,
                textTop - listTop - row * LINE * scale, thickness,
                RULE_BETWEEN_ROWS, light);
        }
    }

    private static void rule(VertexConsumer rules, PoseStack pose,
                             float x0, float x1, float y, float thickness,
                             int alpha, int light) {
        float lo = y - thickness * 0.5F;
        float hi = y + thickness * 0.5F;
        float z = SHEET_Z + RULE_LIFT;
        int r = PlaqueSheet.TITLE_COLOUR >> 16 & 0xFF;
        int g = PlaqueSheet.TITLE_COLOUR >> 8 & 0xFF;
        int b = PlaqueSheet.TITLE_COLOUR & 0xFF;
        PoseStack.Pose last = pose.last();
        rules.addVertex(last, x0, lo, z).setColor(r, g, b, alpha).setLight(light);
        rules.addVertex(last, x1, lo, z).setColor(r, g, b, alpha).setLight(light);
        rules.addVertex(last, x1, hi, z).setColor(r, g, b, alpha).setLight(light);
        rules.addVertex(last, x0, hi, z).setColor(r, g, b, alpha).setLight(light);
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
