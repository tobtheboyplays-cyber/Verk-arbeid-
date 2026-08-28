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
import java.util.List;
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
     * A soft cast shadow under the emblem, so it reads as sitting proud of
     * the sheet rather than pasted flat onto it. Two nested quads standing
     * in for a blur — one wide and faint, one tighter and darker — offset
     * down-right of the emblem's own centre to match the top-left key light
     * every other material in this board is lit from. Drawn on the SAME
     * {@link RenderType#textBackground()} buffer as the rules and the rail,
     * so this costs vertices, not a draw call.
     */
    private static final float SHADOW_OFFSET = 0.10F;
    private static final float SHADOW_SPREAD = 1.14F;
    private static final int SHADOW_SOFT = 0x2E000000;
    private static final int SHADOW_CORE = 0x4A000000;

    /**
     * The most of the sheet the writing may take. The rest belongs to the
     * picture — so a registered building, whose sheet is two lines, gives its
     * picture most of the parchment, and a plaque still gathering its
     * requirements gives it what is left over. The picture used to be pinned
     * to a fixed band whatever else was on the sheet, and at that size the
     * owner could not tell one plan from another.
     */
    private static final float TEXT_SHARE = 0.44F;

    /** Clear parchment between the picture and the first line of writing. */
    private static final float PICTURE_GAP = 0.018F;

    /**
     * How much of the opening's foot GREEN (registered) alone gives up, so
     * the picture and the (short, occupancy-only) writing never draw over
     * plaque_plan_sealed.png's baked wax seal. Derived from that texture's
     * own geometry, not guessed: the seal sits at (32, 53) with radius 7 in
     * its 64px face, which maps the panel element's own y 4.3..13.1 span —
     * its top edge lands at model y around 6.9, block-local y around -0.068 —
     * rounded up with real headroom rather than trimmed to the exact figure,
     * since a collision would read as ink scrawled over the seal and a few
     * spare millimetres of bare parchment above it reads as nothing at all.
     */
    private static final float SEAL_CLEARANCE = 0.15F;

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

    /**
     * The line under the title is drawn TWICE, a hair apart — a ledger's
     * double rule, not a single stroke — because one thick line and one
     * thin one both read as "an underline"; two thin ones read as "the
     * heading is over, the account begins here", which is what this line
     * is actually doing on the page.
     */
    private static final float RULE_DOUBLE_GAP = 2.6F;

    /**
     * The rail, the rules and the stamps are all flat quads on the same
     * {@link RenderType#textBackground()} buffer, and the rail and the
     * under-title double rule occupy overlapping ground -- two truly
     * coplanar quads there is exactly the z-fight {@link #RULE_LIFT}'s own
     * history is about. Three distinct lift MULTIPLIERS on that one proven
     * offset give every layer its own depth without inventing new untested
     * numbers: the rail sits behind the rules, the stamps sit in front of
     * them, and nothing shares a depth with anything it might cross.
     */
    private static final float BACKDROP_LIFT = RULE_LIFT * 0.6F;
    private static final float ACCENT_LIFT = RULE_LIFT * 1.3F;

    /** Clear parchment left around the writing, in block units. */
    private static final float MARGIN_X = 0.012F;
    private static final float MARGIN_Y = 0.006F;

    /** Font units. */
    private static final int LINE = 10;
    private static final int TITLE_GAP = 3;

    // --- the brass name-rail the title sits on -----------------------------
    // Hex values pulled straight from texlib's "brass" ramp so the one piece
    // of brass PlaqueRenderer draws itself never sits a shade off the brass
    // the block model is actually wearing.
    private static final int RAIL_HI = 0xFFD4AF5A;
    private static final int RAIL_BASE = 0xFFB8912F;
    private static final int RAIL_LO = 0xFF5C4715;
    /** Font units, either side of the title's own width. */
    private static final float RAIL_PAD_X = 4.0F;
    /** Font units of clearance above/below the title's own line height. */
    private static final float RAIL_PAD_Y = 1.6F;

    /**
     * Ink for a title struck INTO brass rather than written on parchment:
     * near-black, the far wall of the engraved groove. oak_carved's darkest
     * step, the same hex the generator's own carving uses.
     */
    private static final int TITLE_ENGRAVED_COLOUR = 0xFF241A0E;
    /** The groove's near wall, catching light — drawn first, offset up-left. */
    private static final int TITLE_ENGRAVED_RIM = 0xFFE6C979;
    /**
     * The same rim, warmed and lifted for a registered plaque only — the
     * "gold shimmer" the brief asks for. Still a single static colour, drawn
     * once a frame like everything else here: the shimmer is that the title
     * itself is gilded, not a moving light.
     */
    private static final int TITLE_GILD_REGISTERED = 0xFFFFDD8E;

    // --- the stamped mark at the end of each requirement row ---------------
    /** Font units; the bead's outer radius. */
    private static final float STAMP_RADIUS = 3.4F;
    /** Block units reserved on the right of the field for the stamp column. */
    private static final float STAMP_GUTTER = 0.050F;
    private static final int STAMP_RIM_MET = 0xFF22401E;
    private static final int STAMP_FILL_MET = 0xFF5FA860;
    private static final int STAMP_TICK = 0xFFEFE5C8;

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

        // GREEN is the one glow value the sealed texture belongs to (see
        // plaque_green.json); reading it straight off the block state keeps
        // this in lockstep with the lamp rather than re-deriving it from the
        // sheet, which is exactly the kind of second source of truth D-006
        // warns against.
        boolean registered =
            plaque.getBlockState().getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.GREEN;

        int titleWidth = font.width(sheet.title());
        float widest = titleWidth * TITLE_SCALE;
        for (PlaqueSheet.Line line : sheet.lines()) {
            widest = Math.max(widest, font.width(line.text()));
        }
        float height = LINE * TITLE_SCALE + TITLE_GAP + sheet.lines().size() * LINE;
        if (widest <= 0.0F || height <= 0.0F) {
            return;
        }

        // STAMP_GUTTER carves a fixed column out of the field for the
        // per-row stamp (see drawStamps) so it sits in one consistent
        // column regardless of how long any row's own text runs, rather
        // than hugging each row individually.
        float usableWidth = 2.0F * (FIELD_HALF_WIDTH - MARGIN_X) - STAMP_GUTTER;
        // Registered alone raises the floor by SEAL_CLEARANCE, so neither
        // the picture nor the (short, occupancy-only) writing ever draws
        // over plaque_plan_sealed.png's baked wax seal.
        float openingBottom = OPENING_BOTTOM + (registered ? SEAL_CLEARANCE : 0.0F);
        float opening = OPENING_TOP - openingBottom;
        float scale = Math.min(MAX_SCALE, Math.min(usableWidth / widest,
            opening * TEXT_SHARE / height));

        // The writing sits at the FOOT of the sheet; everything above it is
        // the picture's.
        float textBottom = openingBottom + MARGIN_Y;
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

        drawTitleRail(pose, buffers, lit, titleWidth, scale, textTop);
        drawRules(pose, buffers, lit, sheet.lines().size(), textTop, widest, scale);
        drawStamps(pose, buffers, lit, sheet.lines(), textTop, scale);

        pose.translate(0.0F, (textTop + textBottom) * 0.5F, SHEET_Z + INK_LIFT);
        // Negative Y scale because font coordinates run downward.
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();

        float top = -height / 2.0F;
        float left = -widest / 2.0F;

        pose.pushPose();
        pose.scale(TITLE_SCALE, TITLE_SCALE, 1.0F);
        drawTitle(sheet.title(), -titleWidth / 2.0F, top / TITLE_SCALE, registered,
            pose.last().pose(), buffers, lit);
        pose.popPose();

        float y = top + LINE * TITLE_SCALE + TITLE_GAP;
        for (PlaqueSheet.Line line : sheet.lines()) {
            draw(line.text(), left, y, line.ink().colour(), matrix, buffers, lit);
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
        drawEmblemShadow(pose, buffers, light, centreY, side);
        pose.pushPose();
        pose.translate(0.0F, centreY, SHEET_Z + EMBLEM_LIFT);
        pose.scale(side, side, side * EMBLEM_DEPTH);
        items.renderStatic(emblem, ItemDisplayContext.GUI, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, plaque.getLevel(), 0);
        pose.popPose();
    }

    /**
     * Two nested quads standing in for a soft blur, offset down-right of the
     * emblem's own centre to match the board's top-left key light. Cheap on
     * purpose: this is the same {@link RenderType#textBackground()} buffer
     * the rules and the rail already use, so it costs a handful of vertices
     * per plaque, not a draw call.
     */
    private void drawEmblemShadow(PoseStack pose, MultiBufferSource buffers, int light,
                                  float centreY, float side) {
        VertexConsumer shadow = buffers.getBuffer(RenderType.textBackground());
        float z = SHEET_Z + BACKDROP_LIFT;
        float ox = side * SHADOW_OFFSET;
        float oy = -side * SHADOW_OFFSET;
        float wide = side * 0.5F * SHADOW_SPREAD;
        fillQuad(shadow, pose, ox - wide, ox + wide, centreY + oy - wide,
            centreY + oy + wide, z, SHADOW_SOFT, light);
        float tight = side * 0.5F;
        float tox = ox * 0.55F;
        float toy = oy * 0.55F;
        fillQuad(shadow, pose, tox - tight, tox + tight, centreY + toy - tight,
            centreY + toy + tight, z, SHADOW_CORE, light);
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
        // A ledger's double rule under the title, not a single stroke -- see
        // RULE_DOUBLE_GAP.
        float underTitle = textTop - headed;
        float doubleGap = RULE_DOUBLE_GAP * scale * 0.5F;
        rule(rules, pose, -half, half, underTitle - doubleGap, thickness,
            RULE_UNDER_TITLE, light);
        rule(rules, pose, -half, half, underTitle + doubleGap, thickness,
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

    /**
     * The brass name-rail the title sits on: three flat bands standing in
     * for a bevelled strip -- a bright top edge, the base fill, a dark
     * bottom edge -- centred on the title and padded a little past its own
     * width, the way a nameplate is always a size larger than the name cut
     * into it. Drawn, like the rules, in block units before the text's own
     * flipped scale is applied.
     */
    private void drawTitleRail(PoseStack pose, MultiBufferSource buffers, int light,
                               int titleWidth, float scale, float textTop) {
        VertexConsumer rail = buffers.getBuffer(RenderType.textBackground());
        float half = (titleWidth * TITLE_SCALE * 0.5F + RAIL_PAD_X) * scale;
        float top = textTop + RAIL_PAD_Y * scale;
        float bottom = textTop - (LINE * TITLE_SCALE + RAIL_PAD_Y) * scale;
        // BACKDROP_LIFT, not RULE_LIFT itself: the double rule crosses right
        // over this rail's foot, and two coplanar quads there is undefined
        // which one shows. The engraved title still wins the depth test on
        // top of either, because Font.DisplayMode.POLYGON_OFFSET biases
        // text forward regardless of its own, smaller, geometric lift.
        float z = SHEET_Z + BACKDROP_LIFT;
        float edge = Math.max(scale * 0.6F, (top - bottom) * 0.16F);
        fillQuad(rail, pose, -half, half, bottom, top, z, RAIL_BASE, light);
        fillQuad(rail, pose, -half, half, top - edge, top, z, RAIL_HI, light);
        fillQuad(rail, pose, -half, half, bottom, bottom + edge, z, RAIL_LO, light);
    }

    /**
     * The stamped mark at the end of each row: a small bead in a FIXED
     * column near the frame's right edge, rather than hugging each row's own
     * text -- fixed is what makes the column read as tabular instead of
     * ragged, since the requirement text itself runs to very different
     * lengths ("Storage 3/4" against "Floor 142/28"). MET gets a brighter
     * fleck pressed into the top-left of its bead, the way a wax tick
     * catches light differently from a flat dot; PARTIAL and UNMET stay a
     * plain bead, since the row's own ink colour already says which of the
     * two it is.
     */
    private void drawStamps(PoseStack pose, MultiBufferSource buffers, int light,
                            List<PlaqueSheet.Line> lines, float textTop,
                            float scale) {
        boolean any = false;
        for (PlaqueSheet.Line line : lines) {
            if (!line.ink().mark().isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        VertexConsumer stamps = buffers.getBuffer(RenderType.textBackground());
        float x = FIELD_HALF_WIDTH - MARGIN_X - STAMP_GUTTER * 0.5F;
        float listTop = (LINE * TITLE_SCALE + TITLE_GAP) * scale;
        float r = STAMP_RADIUS * scale;
        float z = SHEET_Z + ACCENT_LIFT;
        int row = 0;
        for (PlaqueSheet.Line line : lines) {
            if (!line.ink().mark().isEmpty()) {
                float y = textTop - listTop - (row + 0.5F) * LINE * scale;
                boolean met = line.ink() == PlaqueSheet.Ink.MET;
                int rim = met ? STAMP_RIM_MET : shade(line.ink().colour(), 0.55F);
                int fill = met ? STAMP_FILL_MET : line.ink().colour();
                diamond(stamps, pose, x, y, r, rim, light, z);
                diamond(stamps, pose, x, y, r * 0.6F, fill, light, z);
                if (met) {
                    float g = r * 0.30F;
                    fillQuad(stamps, pose, x - g * 1.6F, x - g * 0.2F, y,
                        y + g, z + ACCENT_LIFT * 0.2F, STAMP_TICK, light);
                }
            }
            row++;
        }
    }

    /**
     * The title, struck into the brass rail rather than written on
     * parchment: a warm rim drawn first and offset up-left, then the dark
     * engraved fill on top, so the glyphs read as a groove catching light
     * rather than flat paint. A registered plaque lifts that rim to a
     * brighter gold -- the one "shimmer" the brief asks for, delivered as a
     * static colour choice, not anything that ticks.
     */
    private void drawTitle(Component title, float x, float y, boolean registered,
                           Matrix4f matrix, MultiBufferSource buffers, int light) {
        int rim = registered ? TITLE_GILD_REGISTERED : TITLE_ENGRAVED_RIM;
        font.drawInBatch(title, x - 0.4F, y - 0.4F, rim, false, matrix, buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, light);
        font.drawInBatch(title, x, y, TITLE_ENGRAVED_COLOUR, false, matrix, buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }

    private void draw(Component text, float x, float y, int colour, Matrix4f matrix,
                      MultiBufferSource buffers, int light) {
        // No drop shadow: this is ink on parchment, not a HUD label, and a
        // shadow at this size turns every glyph into a smudge.
        font.drawInBatch(text, x, y, colour, false, matrix, buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }

    /** A flat, axis-aligned quad -- the rail bands, the shadow, the stamps. */
    private static void fillQuad(VertexConsumer buffer, PoseStack pose,
                                 float x0, float x1, float y0, float y1,
                                 float z, int argb, int light) {
        int a = argb >>> 24;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        buffer.addVertex(last, x0, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y1, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x0, y1, z).setColor(r, g, b, a).setLight(light);
    }

    /** A small rotated-square bead, centred at (cx, cy) with corner radius r. */
    private static void diamond(VertexConsumer buffer, PoseStack pose,
                                float cx, float cy, float r, int argb, int light,
                                float z) {
        int a = argb >>> 24;
        int red = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        buffer.addVertex(last, cx, cy + r, z).setColor(red, g, b, a).setLight(light);
        buffer.addVertex(last, cx - r, cy, z).setColor(red, g, b, a).setLight(light);
        buffer.addVertex(last, cx, cy - r, z).setColor(red, g, b, a).setLight(light);
        buffer.addVertex(last, cx + r, cy, z).setColor(red, g, b, a).setLight(light);
    }

    /** Darkens (f &lt; 1) or lightens (f &gt; 1) an ARGB colour; alpha untouched. */
    private static int shade(int argb, float f) {
        int a = argb & 0xFF000000;
        int r = clamp255(Math.round((argb >> 16 & 0xFF) * f));
        int g = clamp255(Math.round((argb >> 8 & 0xFF) * f));
        int b = clamp255(Math.round((argb & 0xFF) * f));
        return a | r << 16 | g << 8 | b;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
