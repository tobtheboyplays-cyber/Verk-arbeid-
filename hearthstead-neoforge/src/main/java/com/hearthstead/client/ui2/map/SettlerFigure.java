package com.hearthstead.client.ui2.map;

/**
 * Pixel geometry for one settler figure on the realm map.
 *
 * <p>{@link #build} lays the figure out as a short list of rectangles in
 * physical pixels relative to the feet (y grows downward, so the body is
 * at negative y); one skin texel is {@code t} pixels. It writes into
 * preallocated arrays, so a frame allocates nothing. The view draws the
 * rectangles as ink silhouette, then colour, then blits the face.
 *
 * <p>Levels of detail (the far zoom uses the separate pawn sprite):
 * <ul>
 *   <li>Mid: front view with the two-frame march (a leg shortens, the body
 *       bobs a pixel), arms at the sides and the trade's tool carried upright
 *       beside the body.</li>
 *   <li>Close: a six-frame walk. Moving sideways the figure turns to profile
 *       and its legs scissor with the planted foot sliding back exactly as
 *       fast as the ground passes (see {@link #cyclesPerSecond}); arms swing
 *       opposite the legs, the head rises on the passing frames. Running
 *       takes longer strides, lifts higher and leans in. Moving up or down
 *       the screen it walks toward or away from you (the back of the head
 *       when walking away). Idle figures breathe and now and then look
 *       aside; workers swing their tool in three frames.</li>
 * </ul>
 * Colours come from the settler's own skin ({@link FigureSkin}); a missing
 * cell falls back to the trade colour.
 */
public final class SettlerFigure {
    public static final int FRONT = 0;
    public static final int BACK = 1;
    public static final int SIDE = 2;

    public static final int IDLE = 0;
    public static final int WALK = 1;
    public static final int RUN = 2;
    public static final int WORK = 3;
    public static final int SLEEP = 4;

    public static final int TOOL_NONE = 0;
    public static final int AXE = 1;
    public static final int HOE = 2;
    public static final int PICK = 3;
    public static final int HAMMER = 4;
    public static final int SWORD = 5;
    public static final int SPEAR = 6;
    public static final int STAFF = 7;
    public static final int BOW = 8;
    public static final int ROD = 9;
    public static final int BASKET = 10;
    public static final int BOOK = 11;
    public static final int CROOK = 12;

    /** Rectangle flags. */
    public static final int OUTLINE = 1;
    public static final int EDGE = 2;
    public static final int OVER = 4;

    static final int WOOD = 0xFF7A5230;
    static final int IRON = 0xFFC9CDD4;
    static final int DARK_IRON = 0xFF5A5D63;
    static final int GOLD = 0xFFCDB57E;
    static final int WICKER = 0xFFB88E4E;
    static final int STRING = 0xFFEDE6D3;
    static final int RUNE = 0xFF6F8BE0;
    static final int PAGE = 0xFF3E5C8A;
    private static final int[] TOOL_COLORS = {WOOD, IRON, DARK_IRON, GOLD, WICKER, STRING, RUNE, PAGE};

    /** Tool sprites pointing up with the hand at (0, 0): x, y, colour index per pixel. */
    static final int[][] TOOLS = {
        {},
        {0, 0, 0, 0, -1, 0, 0, -2, 0, 0, -3, 0, 1, -3, 1, 1, -2, 1},                 // axe
        {0, 0, 0, 0, -1, 0, 0, -2, 0, 0, -3, 0, 1, -3, 1},                           // hoe
        {0, 0, 0, 0, -1, 0, 0, -2, 0, -1, -3, 1, 0, -3, 1, 1, -3, 1, -2, -2, 1, 2, -2, 1}, // pick
        {0, 0, 0, 0, -1, 0, 0, -2, 0, -1, -3, 2, 0, -3, 2, 1, -3, 2, 0, -4, 2, 1, -4, 2},  // hammer
        {0, 0, 0, -1, -1, 3, 0, -1, 3, 1, -1, 3, 0, -2, 1, 0, -3, 1, 0, -4, 1},       // sword
        {0, 1, 0, 0, 0, 0, 0, -1, 0, 0, -2, 0, 0, -3, 0, 0, -4, 1, 0, -5, 1},         // spear
        {0, 1, 0, 0, 0, 0, 0, -1, 0, 0, -2, 0, 0, -3, 0, 0, -4, 6, -1, -4, 6, 1, -4, 6}, // rune staff
        {1, -2, 0, 2, -1, 0, 2, 0, 0, 2, 1, 0, 1, 2, 0, 1, -1, 5, 1, 0, 5, 1, 1, 5},  // bow (never rotated)
        {0, 0, 0, 0, -1, 0, 1, -2, 0, 1, -3, 0, 2, -4, 0},                           // rod
        {-1, 1, 4, 0, 1, 4, 1, 1, 4, -1, 2, 4, 0, 2, 4, 1, 2, 4, -1, 0, 0, 1, 0, 0},  // basket (hangs)
        {0, 0, 7, 1, 0, 7, 0, 1, 7, 1, 1, 7},                                          // book
        {0, 1, 0, 0, 0, 0, 0, -1, 0, 0, -2, 0, 0, -3, 0, 1, -4, 0, 2, -3, 0},         // crook
    };

    // Arm and tool directions: up, forward, forward-down, down, up-forward, up-back.
    static final int UP = 0;
    static final int FWD = 1;
    static final int FDIAG = 2;
    static final int DOWN = 3;
    static final int UDIAG = 4;
    static final int BDIAG = 5;
    private static final int[] DIR_X = {0, 1, 1, 0, 1, -1};
    private static final int[] DIR_Y = {-1, 0, 1, 1, -1, -1};

    // Side-view cycle, six frames: contact, recoil, passing, contact, recoil, passing.
    // Foot A is planted for frames 0..3 and slides back exactly three (walk) or four (run) texels.
    static final float[] WALK_FOOT = {1.5F, 0.5F, -0.5F, -1.5F, -0.5F, 0.5F};
    static final float[] RUN_FOOT = {2.0F, 0.7F, -0.7F, -2.0F, -0.7F, 0.7F};
    static final int[] LIFT_A = {0, 0, 0, 0, 1, 1};
    static final int[] LIFT_B = {0, 1, 1, 0, 0, 0};
    static final int[] WALK_BOB = {0, 0, 1, 0, 0, 1};
    static final int[] RUN_BOB = {0, 1, 2, 0, 1, 2};
    /** Texels the planted foot travels per half cycle. */
    static final float WALK_STRIDE = 3.0F;
    static final float RUN_STRIDE = 4.0F;

    public static final int MAX = 128;
    public final int[] x0 = new int[MAX];
    public final int[] y0 = new int[MAX];
    public final int[] x1 = new int[MAX];
    public final int[] y1 = new int[MAX];
    public final int[] color = new int[MAX];
    public final int[] flags = new int[MAX];
    public int count;

    /** The head square (8 texels) and the skin UVs of its face and hat layers. */
    public int headX;
    public int headY;
    public int headSize;
    public int faceU;
    public int faceV;
    public int hatU;
    public int hatV;
    public boolean headMirror;
    /** Topmost pixel of the figure (for placing a status pip). */
    public int top;
    /** Rightmost pixel of the head (for placing a status pip). */
    public int headRight;

    private int t;
    private int headRect;
    private FigureSkin skin;
    private int torsoFallback;
    private int limbFallback;
    private float shade;

    /** Trouser colour when a skin has no leg texels. */
    public static final int TROUSERS = 0xFF3E2F23;
    /** Face colour when no skin texture is known. */
    public static final int SKIN = 0xFFE3B98F;

    /**
     * Lays out one figure.
     *
     * @param t          physical pixels per skin texel
     * @param close      the close level of detail (six-frame cycles, profile view)
     * @param view       FRONT, BACK or SIDE (SIDE only applies when close)
     * @param left       faces screen-left (the figure is mirrored)
     * @param motion     IDLE, WALK, RUN, WORK or SLEEP
     * @param frame      walk frame: 0..5 when close, 0..1 for the mid march
     * @param workFrame  tool gesture frame 0..2 (mid uses 0 = arm up, 1 = down)
     * @param lift       extra body lift in pixels (mid bob, close breathing)
     * @param look       close idle glance: -1 left, 0 ahead, 1 right
     * @param tool       tool sprite index
     * @param skin       sampled outfit, or null
     * @param torso      trade colour used where the outfit has no texels
     * @param hasFace    whether a face texture will be blitted over the head square
     * @param sleeping   darkens the colours
     */
    public void build(int t, boolean close, int view, boolean left, int motion, int frame, int workFrame, int lift,
                      int look, int tool, FigureSkin skin, int torso, boolean hasFace, boolean sleeping) {
        this.t = Math.max(1, t);
        this.skin = skin;
        this.torsoFallback = torso;
        this.limbFallback = TROUSERS;
        this.shade = sleeping ? 0.7F : 1.0F;
        count = 0;
        top = 0;
        headMirror = false;
        if (close && (view == SIDE || motion == WORK)) {
            buildSide(motion, frame, workFrame, lift, tool, hasFace);
        } else {
            buildFront(close, view == BACK, motion, frame, workFrame, lift, look, tool, hasFace);
        }
        if (left) mirror();
        headRight = headX + headSize;
    }

    // ------------------------------------------------------------ front ---

    private void buildFront(boolean close, boolean back, int motion, int frame, int workFrame, int lift, int look,
                            int tool, boolean hasFace) {
        int u = t;
        boolean walking = motion == WALK || motion == RUN;
        int bob = lift;
        int legLeft = 3 * u;
        int legRight = 3 * u;
        int liftLeft = 0;
        int liftRight = 0;
        int raiseLeft = 0;
        int raiseRight = 0;
        if (walking && !close) {
            // The mid march, unchanged: one leg shortens under the body on alternate frames.
            legLeft = frame == 0 ? 3 * u : 2 * u;
            legRight = frame == 1 ? 3 * u : 2 * u;
        } else if (walking) {
            int f = Math.floorMod(frame, 6);
            int h = motion == RUN ? u : Math.max(1, u * 2 / 3);
            liftLeft = LIFT_B[f] * h;
            liftRight = LIFT_A[f] * h;
            // The arm opposite a lifted leg swings forward (foreshortens).
            raiseLeft = liftRight / 2 + (liftRight > 0 ? 1 : 0);
            raiseRight = liftLeft / 2 + (liftLeft > 0 ? 1 : 0);
            bob += (motion == RUN ? RUN_BOB : WALK_BOB)[f];
        }
        int toolRaise = 0;
        if (motion == WORK && !close) toolRaise = workFrame == 0 ? u : 0;
        int legParL = back ? FigureSkin.LEG_L_BACK : FigureSkin.LEG_R_FRONT;
        int legParR = back ? FigureSkin.LEG_R_BACK : FigureSkin.LEG_L_FRONT;
        int armParL = back ? FigureSkin.ARM_L_BACK : FigureSkin.ARM_R_FRONT;
        int armParR = back ? FigureSkin.ARM_R_BACK : FigureSkin.ARM_L_FRONT;
        int torsoPart = back ? FigureSkin.TORSO_BACK : FigureSkin.TORSO_FRONT;

        // Legs, then the ink seam between them.
        if (!close) add(-2 * u, -3 * u, 2 * u, 0, 0, OUTLINE);
        leg(-2 * u, 0, legParL, legLeft, liftLeft, close);
        leg(0, 2 * u, legParR, legRight, liftRight, close);
        add(-1, -3 * u, 0, 0, 0x991E1610, 0);
        // Torso in 3x4 outfit cells; the top row stretches with the bob.
        for (int row = 0; row < 4; row++) {
            int ya = -7 * u + row * u - (row == 0 ? bob : 0);
            int yb = -7 * u + (row + 1) * u;
            for (int col = 0; col < 3; col++) {
                add(-3 * u + col * 2 * u, ya, -3 * u + (col + 1) * 2 * u, yb,
                    tone(skinCell(torsoPart, col, row, torsoFallback)), 0);
            }
        }
        setOutlineBounds(count - 12, -3 * u, -7 * u - bob, 3 * u, -3 * u);
        add(-3 * u, -7 * u - bob, 3 * u, -7 * u - bob + 1, 0x40FFFFFF, 0);
        // Arms at the sides; the tool arm (screen right) lifts while working at mid zoom.
        arm(-4 * u, armParL, bob + raiseLeft, raiseLeft);
        arm(3 * u, armParR, bob + raiseRight + toolRaise, raiseRight);
        // Head square.
        head(-4 * u, -15 * u - bob, hasFace);
        if (back) {
            faceU = 24;
            hatU = 56;
        } else if (look != 0) {
            // A glance aside: the face turns (mirrors) and the head shifts a pixel that way.
            headMirror = look < 0;
            headX += look;
            x0[headRect] += look;
            x1[headRect] += look;
        }
        // Tool carried upright beside the body, in the right hand.
        if (tool != TOOL_NONE) {
            int handY = -5 * u - bob - raiseRight - toolRaise;
            toolAt(tool, 4 * u, handY, UP, 0);
        }
    }

    private void leg(int xa, int xb, int part, int length, int lift, boolean close) {
        int u = t;
        int topY = -length - lift;
        for (int row = 0; row < 3; row++) {
            int ya = Math.max(topY, -3 * u + row * u - lift);
            int yb = -3 * u + (row + 1) * u - lift;
            if (yb <= ya) continue;
            add(xa, ya, xb, yb, tone(skinCell(part, 0, row, limbFallback)), close ? OUTLINE : 0);
        }
    }

    private void arm(int xa, int part, int lift, int foreshorten) {
        int u = t;
        int ya = -7 * u - lift + foreshorten;
        for (int row = 0; row < 3; row++) {
            int ra = ya + row * u;
            int rb = Math.min(ya + (row + 1) * u, -4 * u - lift);
            if (rb <= ra) continue;
            add(xa, ra, xa + u, rb, tone(skinCell(part, 0, row, torsoFallback)), OUTLINE | EDGE);
        }
    }

    // ------------------------------------------------------------- side ---

    private void buildSide(int motion, int frame, int workFrame, int lift, int tool, boolean hasFace) {
        int u = t;
        boolean run = motion == RUN;
        boolean walking = motion == WALK || run;
        boolean work = motion == WORK;
        int f = Math.floorMod(frame, 6);
        float footA = 0.0F;
        float footB = 0.0F;
        int liftA = 0;
        int liftB = 0;
        int bob = lift;
        if (walking) {
            footA = (run ? RUN_FOOT : WALK_FOOT)[f];
            footB = -footA;
            int h = run ? u : Math.max(1, u * 2 / 3);
            liftA = LIFT_A[f] * h;
            liftB = LIFT_B[f] * h;
            bob += (run ? RUN_BOB : WALK_BOB)[f];
        } else if (work) {
            footA = 0.6F;
            footB = -0.6F;
        }
        int lean = run ? Math.max(1, u / 2) : 0;
        float swing = run ? 0.9F : 0.7F;
        // Far leg and far arm sit behind the body, a shade darker.
        float saved = shade;
        shade = saved * 0.78F;
        sideLeg(footB, liftB, lean / 2);
        sideArm(-footB * swing, bob, lean, 0);
        shade = saved;
        sideLeg(footA, liftA, lean / 2);
        // Torso in profile, 2x4 outfit cells.
        int first = count;
        for (int row = 0; row < 4; row++) {
            int ya = -7 * u + row * u - (row == 0 ? bob : 0);
            int yb = -7 * u + (row + 1) * u;
            for (int col = 0; col < 2; col++) {
                add(-2 * u + col * 2 * u + lean, ya, -2 * u + (col + 1) * 2 * u + lean, yb,
                    tone(skinCell(FigureSkin.TORSO_SIDE, col, row, torsoFallback)), 0);
            }
        }
        setOutlineBounds(first, -2 * u + lean, -7 * u - bob, 2 * u + lean, -3 * u);
        // Profile body, but the face stays readable: the front face turned toward the heading.
        head(-4 * u + lean, -15 * u - bob, hasFace);
        if (work) {
            workArm(tool, workFrame, bob, lean);
        } else {
            int handX = sideArm(-footA * swing, bob, lean, EDGE);
            if (tool != TOOL_NONE) {
                int handY = -5 * u - bob;
                if (tool == BOW || tool == BASKET || tool == BOOK) {
                    toolAt(tool, handX, handY, UP, OVER);
                } else {
                    toolAt(tool, handX + DIR_X[FDIAG] * u, handY + DIR_Y[FDIAG] * u, FDIAG, OVER);
                }
            }
        }
    }

    private void sideLeg(float foot, int lift, int lean) {
        int u = t;
        for (int row = 0; row < 3; row++) {
            int off = Math.round(foot * u * (row + 1) / 3.0F) + lean;
            int ya = -3 * u + row * u - lift;
            add(-u + off, ya, u + off, ya + u, tone(skinCell(FigureSkin.LEG_SIDE, 0, row, limbFallback)), OUTLINE);
        }
    }

    /** A hanging arm swinging by {@code swing} texels at the hand; returns the hand cell's left x. */
    private int sideArm(float swing, int bob, int lean, int extraFlags) {
        int u = t;
        int x = -u / 2 + lean;
        int hand = x;
        for (int row = 0; row < 3; row++) {
            int off = Math.round(swing * u * (row + 1) / 3.0F);
            int ya = -7 * u - bob + row * u;
            add(x + off, ya, x + off + u, ya + u, tone(skinCell(FigureSkin.ARM_SIDE, 0, row, torsoFallback)),
                OUTLINE | extraFlags);
            hand = x + off;
        }
        return hand;
    }

    /** The near arm and its tool in one of three gesture frames, drawn over the head. */
    private void workArm(int tool, int workFrame, int bob, int lean) {
        int u = t;
        int dir;
        boolean swingTool = tool == AXE || tool == HOE || tool == PICK || tool == HAMMER || tool == SWORD
            || tool == SPEAR || tool == STAFF || tool == CROOK;
        int w = Math.floorMod(workFrame, 3);
        if (swingTool) {
            dir = w == 0 ? BDIAG : w == 1 ? UDIAG : FDIAG;
        } else if (tool == BOW) {
            dir = FWD;
        } else {
            dir = FDIAG;
        }
        int sx = -u / 2 + lean;
        int sy = -7 * u - bob;
        int cells = dir == FWD || dir == UP || dir == DOWN ? 3 : 2;
        int hx = sx;
        int hy = sy;
        for (int k = 0; k < cells; k++) {
            hx = sx + DIR_X[dir] * u * k;
            hy = sy + DIR_Y[dir] * u * k + (dir == FDIAG || dir == FWD ? u : 0);
            add(hx, hy, hx + u, hy + u, tone(skinCell(FigureSkin.ARM_SIDE, 0, Math.min(2, k), torsoFallback)),
                OUTLINE | EDGE | OVER);
        }
        if (tool == TOOL_NONE) return;
        if (tool == BOW) {
            // Draw: the string hand pulls back one pixel on alternate frames.
            toolAt(tool, hx + u - (w == 1 ? 1 : 0), hy, UP, OVER);
        } else if (!swingTool) {
            toolAt(tool, hx, hy + (w == 1 ? -1 : 0), UP, OVER);
        } else {
            toolAt(tool, hx + DIR_X[dir] * u, hy + DIR_Y[dir] * u, dir, OVER);
        }
    }

    // ------------------------------------------------------------ shared ---

    private void head(int x, int y, boolean hasFace) {
        headX = x;
        headY = y;
        headSize = 8 * t;
        faceU = 8;
        faceV = 8;
        hatU = 40;
        hatV = 8;
        headRect = count;
        add(x, y, x + headSize, y + headSize, hasFace ? 0 : tone(SKIN), OUTLINE);
        top = Math.min(top, y);
    }

    /** Tool pixels from cell (ox, oy) (top-left of the hand-side cell), rotated to {@code dir}. */
    private void toolAt(int tool, int ox, int oy, int dir, int extraFlags) {
        int[] px = tool >= 0 && tool < TOOLS.length ? TOOLS[tool] : TOOLS[0];
        int u = t;
        for (int i = 0; i + 2 < px.length; i += 3) {
            int x = px[i];
            int y = px[i + 1];
            int rx;
            int ry;
            switch (tool == BOW || tool == BASKET || tool == BOOK ? UP : dir) {
                case FWD -> {
                    rx = -y;
                    ry = x;
                }
                case FDIAG -> {
                    rx = -x - y;
                    ry = x - y;
                }
                case DOWN -> {
                    rx = -x;
                    ry = -y;
                }
                case UDIAG -> {
                    rx = x - y;
                    ry = x + y;
                }
                case BDIAG -> {
                    rx = x + y;
                    ry = -x + y;
                }
                default -> {
                    rx = x;
                    ry = y;
                }
            }
            int cx = ox + rx * u;
            int cy = oy + ry * u;
            add(cx, cy, cx + u, cy + u, tone(TOOL_COLORS[px[i + 2]]), OUTLINE | extraFlags);
            top = Math.min(top, cy);
        }
    }

    private void add(int xa, int ya, int xb, int yb, int c, int f) {
        if (count >= MAX) return;
        x0[count] = xa;
        y0[count] = ya;
        x1[count] = xb;
        y1[count] = yb;
        color[count] = c;
        flags[count] = f;
        count++;
        top = Math.min(top, ya);
    }

    /** Replaces per-cell outlines of a block with one outline rectangle covering it (fewer fills). */
    private void setOutlineBounds(int from, int xa, int ya, int xb, int yb) {
        for (int i = Math.max(0, from); i < count; i++) flags[i] &= ~OUTLINE;
        add(xa, ya, xb, yb, 0, OUTLINE);
        // Keep the outline-only block before the cells so the draw order stays body-first.
        int last = count - 1;
        int start = Math.max(0, from);
        if (last <= start) return;
        int ax = x0[last];
        int ay = y0[last];
        int bx = x1[last];
        int by = y1[last];
        int c = color[last];
        int fl = flags[last];
        for (int i = last; i > start; i--) {
            x0[i] = x0[i - 1];
            y0[i] = y0[i - 1];
            x1[i] = x1[i - 1];
            y1[i] = y1[i - 1];
            color[i] = color[i - 1];
            flags[i] = flags[i - 1];
        }
        x0[start] = ax;
        y0[start] = ay;
        x1[start] = bx;
        y1[start] = by;
        color[start] = c;
        flags[start] = fl;
    }

    private void mirror() {
        for (int i = 0; i < count; i++) {
            int a = x0[i];
            x0[i] = -x1[i];
            x1[i] = -a;
        }
        headX = -(headX + headSize);
        headMirror = !headMirror;
    }

    private int skinCell(int part, int col, int row, int fallback) {
        if (skin != null) {
            int c = skin.cell(part, col, row);
            if (c != 0) return c;
        }
        return fallback;
    }

    private int tone(int argb) {
        if (shade >= 0.999F) return argb;
        return RealmMapPalette.darker(argb, shade);
    }

    /**
     * Walk cycles per second so the planted foot moves back exactly as fast
     * as the ground passes under the figure: half a cycle covers the stride
     * (texels) times {@code t} physical pixels, which is
     * {@code stride * t / (zoom * guiScale)} blocks. Clamped to a sane cadence.
     */
    public static float cyclesPerSecond(float blocksPerSecond, int t, float zoom, double guiScale, boolean run) {
        float stride = run ? RUN_STRIDE : WALK_STRIDE;
        double halfCycleBlocks = stride * Math.max(1, t) / Math.max(0.05D, zoom * guiScale);
        float cps = (float) (blocksPerSecond / (2.0D * halfCycleBlocks));
        return Math.max(0.6F, Math.min(2.6F, cps));
    }

    /** The tool a trade carries, by its stable key. */
    public static int toolFor(String professionKey) {
        if (professionKey == null) return TOOL_NONE;
        return switch (professionKey) {
            case "farmer" -> HOE;
            case "lumberer", "sawyer", "butcher" -> AXE;
            case "miner" -> PICK;
            case "smith", "armourer", "carpenter", "mason", "smelter", "builder" -> HAMMER;
            case "guard", "longswordsman" -> SWORD;
            case "spearman" -> SPEAR;
            case "rune_mage" -> STAFF;
            case "archer", "hunter", "fletcher" -> BOW;
            case "fisher" -> ROD;
            case "herder" -> CROOK;
            case "scholar" -> BOOK;
            case "none", "mayor" -> TOOL_NONE;
            default -> BASKET;
        };
    }
}
