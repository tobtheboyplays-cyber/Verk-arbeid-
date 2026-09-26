package com.hearthstead.client.fx;

import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementHeraldry;
import com.hearthstead.fx.FxEffect;
import com.hearthstead.registry.ModParticles;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What each particle moment looks like. Authored counts are "normal"
 * intensity; {@link FxClient#count} scales them. Tasteful by rule: short
 * bursts, soft motes, the mod palette (aged gold, linen, burgundy, forest,
 * deep blue), no screen-filling clouds.
 */
public final class FxRecipes {
    // Palette (UI brief): aged gold, pale gold, linen, burgundy, forest, deep blue, silver, amber.
    static final float[] GOLD = {1.0F, 0.82F, 0.4F};
    static final float[] PALE_GOLD = {1.0F, 0.93F, 0.66F};
    static final float[] LINEN = {0.96F, 0.91F, 0.8F};
    static final float[] BURGUNDY = {0.7F, 0.2F, 0.24F};
    static final float[] FOREST = {0.36F, 0.62F, 0.38F};
    static final float[] BLUE = {0.36F, 0.5F, 0.82F};
    static final float[] SILVER = {0.88F, 0.93F, 1.0F};
    static final float[] AMBER = {1.0F, 0.72F, 0.38F};
    static final float[] DUST_BROWN = {0.78F, 0.7F, 0.56F};
    private static final float[][] CELEBRATION = {GOLD, BURGUNDY, LINEN, FOREST, BLUE};

    private FxRecipes() {
    }

    static void play(FxEffect effect, ClientLevel level, double x, double y, double z,
                     @Nullable Entity entity, int arg, @Nullable int[] box) {
        switch (effect) {
            case SKILL_LEVEL_UP -> skillLevelUp(level, x, y, z, entity);
            case TECH_LEARNED -> techLearned(level, x, y, z);
            case BUILDING_LEVEL_UP -> buildingLevelUp(level, x, y, z, box, false);
            case WAREHOUSE_LEVEL_UP -> buildingLevelUp(level, x, y, z, box, true);
            case JOURNEY_CHAPTER -> journeyChapter(level, x, y, z);
            case CRAFT_GLINT -> craftGlint(level, x, y, z, entity, arg);
            case CRAFT_LEGENDARY -> craftLegendary(level, x, y, z, entity);
            case COIN_SALE -> coinSale(level, x, y, z, entity);
            case BUILD_DONE -> buildDone(level, x, y, z, box);
            case RAID_WON -> raidWon(level, x, y, z);
            case SETTLER_WELCOME -> welcome(level, x, y, z, entity);
            case CAPTAIN_PROMOTED -> captainPromoted(level, x, y, z, entity);
            case CAPTAIN_WINDUP -> captainWindup(level, x, y, z, entity, arg & 255, (arg >> 8) & 255);
            case CAPTAIN_IMPACT -> captainImpact(level, x, y, z, entity, arg & 255, ((arg >> 8) & 255) / 10.0D);
            default -> {
            }
        }
    }

    // -------------------------------------------------------------- helpers ---

    static FxParticle p(SimpleParticleType type, double x, double y, double z, double vx, double vy, double vz,
                        float[] color) {
        FxParticle particle = FxClient.spawn(type, x, y, z, vx, vy, vz);
        if (particle != null && color != null) {
            particle.tint(color[0], color[1], color[2]);
        }
        return particle;
    }

    /** Spherical burst of {@code n} (already scaled) from a point. */
    static void burst(ClientLevel level, SimpleParticleType type, double x, double y, double z, int n,
                      double speed, float[] color) {
        RandomSource r = level.random;
        for (int i = 0; i < n; i++) {
            double theta = r.nextDouble() * Mth.TWO_PI;
            double cos = r.nextDouble() * 2.0D - 1.0D;
            double sin = Math.sqrt(1.0D - cos * cos);
            double s = speed * (0.6D + r.nextDouble() * 0.4D);
            p(type, x, y, z, Math.cos(theta) * sin * s, cos * s, Math.sin(theta) * sin * s, color);
        }
    }

    /** Horizontal ring of {@code n} around a point, drifting outward/upward. */
    static void ring(ClientLevel level, SimpleParticleType type, double x, double y, double z, int n,
                     double radius, double out, double up, float[] color) {
        double offset = level.random.nextDouble() * Mth.TWO_PI;
        for (int i = 0; i < n; i++) {
            double a = offset + i * Mth.TWO_PI / Math.max(1, n);
            double cx = Math.cos(a);
            double cz = Math.sin(a);
            p(type, x + cx * radius, y, z + cz * radius, cx * out, up, cz * out, color);
        }
    }

    private static double height(@Nullable Entity entity) {
        return entity == null ? 1.8D : entity.getBbHeight();
    }

    // ------------------------------------------------------------- progress ---

    /** Golden sparkle burst at the chest, then motes spiralling up round the body. */
    private static void skillLevelUp(ClientLevel level, double x, double y, double z, @Nullable Entity entity) {
        double h = height(entity);
        burst(level, ModParticles.SPARKLE.get(), x, y + h * 0.62D, z, FxClient.count(14, true), 0.16D, GOLD);
        int motes = FxClient.count(12, false);
        RandomSource r = level.random;
        for (int i = 0; i < motes; i++) {
            double a = i * 0.9D;
            double yy = y + 0.1D + (h * i) / Math.max(1, motes);
            p(ModParticles.MOTE.get(), x + Math.cos(a) * 0.55D, yy, z + Math.sin(a) * 0.55D,
                -Math.sin(a) * 0.02D, 0.03D + r.nextDouble() * 0.02D, Math.cos(a) * 0.02D, PALE_GOLD);
        }
        FxClient.playSound(FxEffect.SKILL_LEVEL_UP, x, y + h * 0.6D, z, 1.0F);
    }

    /** Burst at the Banner, a ripple of particles across its cloth, and a ring at its foot. */
    private static void techLearned(ClientLevel level, double x, double y, double z) {
        BannerCloth cloth = BannerCloth.at(level, BlockPos.containing(x, y, z));
        burst(level, ModParticles.SPARKLE.get(), cloth.cx, cloth.top + 0.3D, cloth.cz,
            FxClient.count(18, true), 0.2D, GOLD);
        // ripple: a travelling wave of motes across the cloth, left to right, over ~0.8 s
        int columns = Math.max(1, FxClient.count(8, false));
        for (int c = 0; c < columns; c++) {
            final int column = c;
            FxClient.later(1 + c * 2, () -> {
                double u = columns == 1 ? 0.0D : (column / (double) (columns - 1)) - 0.5D;
                for (int row = 0; row < 4; row++) {
                    double v = row / 3.0D;
                    double px = cloth.cx + cloth.ax * u * cloth.width;
                    double pz = cloth.cz + cloth.az * u * cloth.width;
                    double py = cloth.top - v * cloth.height;
                    double wave = Math.sin(column * 0.9D + row * 0.6D) * 0.03D;
                    p(ModParticles.MOTE.get(), px + cloth.nx * 0.08D, py, pz + cloth.nz * 0.08D,
                        cloth.nx * (0.02D + wave), 0.012D, cloth.nz * (0.02D + wave),
                        row % 2 == 0 ? cloth.color : GOLD);
                }
            });
        }
        ring(level, ModParticles.MOTE.get(), x, y + 0.1D, z, FxClient.count(12, false), 1.3D, 0.01D, 0.035D, PALE_GOLD);
        FxClient.playSound(FxEffect.TECH_LEARNED, x, y + 1.5D, z, 1.0F);
    }

    /**
     * Sparkles along the plaque, then along the room's floor outline and up
     * its corners, drawn round the room over about a second.
     */
    private static void buildingLevelUp(ClientLevel level, double x, double y, double z, @Nullable int[] box,
                                        boolean warehouse) {
        float[] color = warehouse ? AMBER : GOLD;
        RandomSource r = level.random;
        int plaque = FxClient.count(8, true);
        for (int i = 0; i < plaque; i++) {
            p(ModParticles.SPARKLE.get(), x + (r.nextDouble() - 0.5D) * 0.9D, y + (r.nextDouble() - 0.5D) * 0.6D,
                z + (r.nextDouble() - 0.5D) * 0.9D, 0.0D, 0.02D, 0.0D, PALE_GOLD);
        }
        if (box != null && box.length == 6) {
            double x0 = box[0], y0 = box[1] + 0.1D, z0 = box[2];
            double x1 = box[3] + 1.0D, y1 = box[4] + 1.0D, z1 = box[5] + 1.0D;
            double dx = x1 - x0, dz = z1 - z0;
            double perimeter = 2.0D * (dx + dz);
            int points = Math.min(64, FxClient.count((int) Math.ceil(perimeter / 1.1D), false));
            int steps = 5;
            for (int s = 0; s < steps; s++) {
                final int step = s;
                FxClient.later(1 + s * 4, () -> {
                    for (int i = step; i < points; i += steps) {
                        double d = perimeter * i / Math.max(1, points);
                        double px, pz;
                        if (d < dx) {
                            px = x0 + d; pz = z0;
                        } else if (d < dx + dz) {
                            px = x1; pz = z0 + (d - dx);
                        } else if (d < 2 * dx + dz) {
                            px = x1 - (d - dx - dz); pz = z1;
                        } else {
                            px = x0; pz = z1 - (d - 2 * dx - dz);
                        }
                        p(ModParticles.SPARKLE.get(), px, y0, pz, 0.0D, 0.03D, 0.0D, color);
                    }
                });
            }
            // corner posts rising once the outline has closed
            double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
            FxClient.later(22, () -> {
                int per = Math.max(1, FxClient.count(3, false));
                for (double[] c : corners) {
                    for (int k = 0; k < per; k++) {
                        p(ModParticles.MOTE.get(), c[0], y0 + (y1 - y0) * k / (double) per, c[1],
                            0.0D, 0.04D, 0.0D, PALE_GOLD);
                    }
                }
            });
            if (warehouse) {
                double cx = (x0 + x1) / 2.0D, cz = (z0 + z1) / 2.0D;
                FxClient.later(10, () -> coinPop(level, cx, y0 + 0.6D, cz, FxClient.count(6, false)));
            }
        }
        FxClient.playSound(warehouse ? FxEffect.WAREHOUSE_LEVEL_UP : FxEffect.BUILDING_LEVEL_UP, x, y, z, 1.0F);
    }

    /** A column of gold motes up the Banner and a crown burst over it. */
    private static void journeyChapter(ClientLevel level, double x, double y, double z) {
        BannerCloth cloth = BannerCloth.at(level, BlockPos.containing(x, y, z));
        int column = FxClient.count(22, false);
        RandomSource r = level.random;
        for (int i = 0; i < column; i++) {
            double a = r.nextDouble() * Mth.TWO_PI;
            double rad = 0.3D + r.nextDouble() * 0.5D;
            p(ModParticles.MOTE.get(), x + Math.cos(a) * rad, y + r.nextDouble() * 0.6D, z + Math.sin(a) * rad,
                0.0D, 0.05D + r.nextDouble() * 0.05D, 0.0D, i % 3 == 0 ? cloth.color : PALE_GOLD);
        }
        FxClient.later(12, () -> burst(level, ModParticles.SPARKLE.get(), cloth.cx, cloth.top + 0.9D, cloth.cz,
            FxClient.count(20, true), 0.22D, GOLD));
        FxClient.playSound(FxEffect.JOURNEY_CHAPTER, x, y + 1.5D, z, 1.0F);
    }

    // -------------------------------------------------------------- rewards ---

    /** Where a worker's hands are: in front of the chest. */
    private static double[] hands(double x, double y, double z, @Nullable Entity entity) {
        if (entity == null) {
            return new double[] {x, y + 1.1D, z};
        }
        float yaw = (entity instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot
            : entity.getYRot()) * Mth.DEG_TO_RAD;
        return new double[] {x - Mth.sin(yaw) * 0.55D, y + entity.getBbHeight() * 0.6D, z + Mth.cos(yaw) * 0.55D};
    }

    /** Superior .. Masterwork: a small silver-gold glint at the hands, a touch more per tier. */
    private static void craftGlint(ClientLevel level, double x, double y, double z, @Nullable Entity entity, int tier) {
        double[] h = hands(x, y, z, entity);
        int n = FxClient.count(tier >= 4 ? 8 : tier == 3 ? 5 : 3, true);
        burst(level, ModParticles.SPARKLE.get(), h[0], h[1], h[2], n, 0.07D, tier >= 4 ? PALE_GOLD : SILVER);
        if (tier >= 4) {
            ring(level, ModParticles.MOTE.get(), h[0], h[1] - 0.2D, h[2], FxClient.count(4, false), 0.3D, 0.0D,
                0.03D, PALE_GOLD);
        }
        FxClient.playSound(FxEffect.CRAFT_GLINT, h[0], h[1], h[2], 0.9F + 0.1F * Math.max(0, tier - 2));
    }

    /** Legendary: a radiant burst, a horizontal halo and motes rising for a moment. */
    private static void craftLegendary(ClientLevel level, double x, double y, double z, @Nullable Entity entity) {
        double[] h = hands(x, y, z, entity);
        burst(level, ModParticles.SPARKLE.get(), h[0], h[1], h[2], FxClient.count(22, true), 0.24D, GOLD);
        ring(level, ModParticles.SPARKLE.get(), h[0], h[1], h[2], FxClient.count(16, false), 0.2D, 0.14D, 0.0D,
            PALE_GOLD);
        for (int wave = 0; wave < 3; wave++) {
            FxClient.later(4 + wave * 6, () -> ring(level, ModParticles.MOTE.get(), x, y + 0.2D, z,
                FxClient.count(6, false), 0.7D, 0.0D, 0.05D, PALE_GOLD));
        }
        FxClient.playSound(FxEffect.CRAFT_LEGENDARY, h[0], h[1], h[2], 1.0F);
    }

    static void coinPop(ClientLevel level, double x, double y, double z, int n) {
        RandomSource r = level.random;
        for (int i = 0; i < n; i++) {
            p(ModParticles.COIN.get(), x, y, z, (r.nextDouble() - 0.5D) * 0.12D, 0.18D + r.nextDouble() * 0.08D,
                (r.nextDouble() - 0.5D) * 0.12D, null);
        }
    }

    /** A few coins flicked up between merchant and buyer, and a glint. */
    private static void coinSale(ClientLevel level, double x, double y, double z, @Nullable Entity entity) {
        double[] h = hands(x, y, z, entity);
        coinPop(level, h[0], h[1], h[2], FxClient.count(5, true));
        burst(level, ModParticles.SPARKLE.get(), h[0], h[1] + 0.2D, h[2], FxClient.count(4, false), 0.08D, GOLD);
        FxClient.playSound(FxEffect.COIN_SALE, h[0], h[1], h[2], 1.0F);
    }

    /** Dust puffs round the base, wood chips tossed off the top, sparkles at the ridge. */
    private static void buildDone(ClientLevel level, double x, double y, double z, @Nullable int[] box) {
        RandomSource r = level.random;
        if (box != null && box.length == 6) {
            double x0 = box[0], y0 = box[1] + 0.2D, z0 = box[2];
            double x1 = box[3] + 1.0D, z1 = box[5] + 1.0D;
            int dust = FxClient.count(18, false);
            for (int i = 0; i < dust; i++) {
                double u = r.nextDouble();
                double px, pz;
                switch (i % 4) {
                    case 0 -> { px = x0 + (x1 - x0) * u; pz = z0 - 0.3D; }
                    case 1 -> { px = x0 + (x1 - x0) * u; pz = z1 + 0.3D; }
                    case 2 -> { px = x0 - 0.3D; pz = z0 + (z1 - z0) * u; }
                    default -> { px = x1 + 0.3D; pz = z0 + (z1 - z0) * u; }
                }
                FxParticle puff = p(ModParticles.FLOUR_PUFF.get(), px, y0, pz, (r.nextDouble() - 0.5D) * 0.04D,
                    0.02D, (r.nextDouble() - 0.5D) * 0.04D, DUST_BROWN);
                if (puff != null) {
                    puff.scaleSize(2.2F).life(30 + r.nextInt(12)).opacity(0.55F);
                }
            }
        }
        int chips = FxClient.count(20, true);
        for (int i = 0; i < chips; i++) {
            p(ModParticles.WOOD_CHIP.get(), x + (r.nextDouble() - 0.5D) * 1.5D, y, z + (r.nextDouble() - 0.5D) * 1.5D,
                (r.nextDouble() - 0.5D) * 0.3D, 0.25D + r.nextDouble() * 0.2D, (r.nextDouble() - 0.5D) * 0.3D, null);
        }
        burst(level, ModParticles.SPARKLE.get(), x, y + 0.4D, z, FxClient.count(8, false), 0.15D, GOLD);
        FxClient.playSound(FxEffect.BUILD_DONE, x, y, z, 1.0F);
    }

    /**
     * Three soft "firework" bursts above the Banner, half a second apart, in
     * the heraldic palette, with confetti drifting down. Particles only: no
     * rocket entity, no damage, no vanilla firework explosion.
     */
    private static void raidWon(ClientLevel level, double x, double y, double z) {
        BannerCloth cloth = BannerCloth.at(level, BlockPos.containing(x, y, z));
        RandomSource r = level.random;
        for (int b = 0; b < 3; b++) {
            final int burstIndex = b;
            double bx = x + (r.nextDouble() - 0.5D) * 4.0D;
            double by = y + 8.0D + r.nextDouble() * 3.0D;
            double bz = z + (r.nextDouble() - 0.5D) * 4.0D;
            FxClient.later(1 + b * 10, () -> {
                float[] main = burstIndex == 0 ? cloth.color : CELEBRATION[(burstIndex * 2) % CELEBRATION.length];
                burst(level, ModParticles.SPARKLE.get(), bx, by, bz, FxClient.count(26, true), 0.32D, main);
                burst(level, ModParticles.SPARKLE.get(), bx, by, bz, FxClient.count(10, false), 0.18D, PALE_GOLD);
                int confetti = FxClient.count(14, false);
                for (int i = 0; i < confetti; i++) {
                    p(ModParticles.CONFETTI.get(), bx + (r.nextDouble() - 0.5D) * 2.0D, by - 0.5D,
                        bz + (r.nextDouble() - 0.5D) * 2.0D, (r.nextDouble() - 0.5D) * 0.1D, 0.02D,
                        (r.nextDouble() - 0.5D) * 0.1D, CELEBRATION[r.nextInt(CELEBRATION.length)]);
                }
                FxClient.playSound(FxEffect.RAID_WON, bx, by, bz, 0.9F + 0.1F * burstIndex);
            });
        }
    }

    /** A warm amber glow gathering round the new settler. */
    private static void welcome(ClientLevel level, double x, double y, double z, @Nullable Entity entity) {
        double h = height(entity);
        ring(level, ModParticles.MOTE.get(), x, y + 0.2D, z, FxClient.count(12, true), 0.8D, -0.012D, 0.03D, AMBER);
        FxClient.later(8, () -> ring(level, ModParticles.MOTE.get(), x, y + h * 0.5D, z,
            FxClient.count(8, false), 0.6D, -0.01D, 0.025D, PALE_GOLD));
        burst(level, ModParticles.SPARKLE.get(), x, y + h + 0.2D, z, FxClient.count(6, false), 0.08D, AMBER);
        FxClient.playSound(FxEffect.SETTLER_WELCOME, x, y + 1.0D, z, 1.0F);
    }

    // --------------------------------------------------------------- Captain ---
    // Style codes mirror FxHooks.CAPTAIN_* (0 blade, 1 rally, 2 heal, 3 bow, 4 heavy, 5 sweep, 6 shield, 7 execution).

    static final float[] CRIMSON = {0.78F, 0.18F, 0.2F};
    static final float[] SOFT_GREEN = {0.62F, 0.9F, 0.55F};

    private static float[] captainColor(int style) {
        return switch (style) {
            case 1 -> GOLD;
            case 2 -> SOFT_GREEN;
            case 3 -> SILVER;
            case 4 -> AMBER;
            case 5 -> PALE_GOLD;
            case 6 -> LINEN;
            case 7 -> CRIMSON;
            default -> SILVER;
        };
    }

    private static double[] feet(double x, double y, double z, @Nullable Entity entity) {
        return entity == null ? new double[] {x, y, z} : new double[] {entity.getX(), entity.getY(), entity.getZ()};
    }

    /**
     * The commissioning moment: a gold burst at the chest, a spiral of motes
     * up the (1.13-scale) hero, a ring at the feet, then a crown of sparkles.
     * The horn is CaptainFx's own sound.
     */
    private static void captainPromoted(ClientLevel level, double x, double y, double z, @Nullable Entity entity) {
        double h = height(entity) * 1.13D;
        burst(level, ModParticles.SPARKLE.get(), x, y + h * 0.6D, z, FxClient.count(26, true), 0.22D, GOLD);
        ring(level, ModParticles.SPARKLE.get(), x, y + 0.1D, z, FxClient.count(16, false), 0.4D, 0.16D, 0.0D, PALE_GOLD);
        int motes = FxClient.count(20, false);
        RandomSource r = level.random;
        for (int i = 0; i < motes; i++) {
            double a = i * 0.7D;
            p(ModParticles.MOTE.get(), x + Math.cos(a) * 0.7D, y + 0.1D + h * i / Math.max(1, motes),
                z + Math.sin(a) * 0.7D, -Math.sin(a) * 0.03D, 0.035D + r.nextDouble() * 0.02D, Math.cos(a) * 0.03D,
                i % 4 == 0 ? CRIMSON : PALE_GOLD);
        }
        FxClient.later(12, () -> burst(level, ModParticles.SPARKLE.get(), x, y + h + 0.5D, z,
            FxClient.count(14, true), 0.14D, GOLD));
        FxClient.playSound(FxEffect.CAPTAIN_PROMOTED, x, y + 1.0D, z, 1.0F);
    }

    /**
     * Telegraph: an 8-point ring at the feet that pulses every 3 ticks and
     * tightens as the wind-up runs out, so the release reads. One payload
     * drives the whole wind-up; the ring follows the Captain.
     */
    private static void captainWindup(ClientLevel level, double x, double y, double z, @Nullable Entity entity,
                                      int style, int ticks) {
        float[] color = captainColor(style);
        int id = entity == null ? -1 : entity.getId();
        for (int t = 0; t < ticks; t += 3) {
            final double progress = ticks <= 0 ? 1.0D : t / (double) ticks;
            Runnable pulse = () -> {
                Entity e = id < 0 ? null : level.getEntity(id);
                if (id >= 0 && (e == null || !e.isAlive())) {
                    return;
                }
                double[] f = feet(x, y, z, e);
                double radius = 1.1D - 0.45D * progress;
                if (style == 4) {
                    int n = FxClient.count(8, true);
                    for (int i = 0; i < n; i++) {
                        double a = i * Mth.TWO_PI / Math.max(1, n);
                        FxParticle puff = p(ModParticles.FLOUR_PUFF.get(), f[0] + Math.cos(a) * radius, f[1] + 0.05D,
                            f[2] + Math.sin(a) * radius, 0.0D, 0.01D, 0.0D, DUST_BROWN);
                        if (puff != null) {
                            puff.life(10).opacity(0.5F);
                        }
                    }
                } else {
                    ring(level, ModParticles.MOTE.get(), f[0], f[1] + 0.08D, f[2], FxClient.count(8, true), radius,
                        -0.01D, 0.01D, color);
                }
            };
            if (t == 0) {
                pulse.run();
            } else {
                FxClient.later(t, pulse);
            }
        }
        FxClient.playSound(FxEffect.CAPTAIN_WINDUP, x, y + 1.0D, z, 1.0F);
    }

    /** Resolution, drawn where the blow lands ({@code range} in blocks for area specials). */
    private static void captainImpact(ClientLevel level, double x, double y, double z, @Nullable Entity entity,
                                      int style, double range) {
        RandomSource r = level.random;
        double[] f = feet(x, y, z, entity);
        switch (style) {
            case 1 -> { // rally cry: the banner flare, a heraldic ring racing out and embers rising
                ring(level, ModParticles.MOTE.get(), f[0], f[1] + 0.2D, f[2], FxClient.count(24, true), 0.5D, 0.22D,
                    0.02D, CRIMSON);
                ring(level, ModParticles.SPARKLE.get(), f[0], f[1] + 0.4D, f[2], FxClient.count(16, false), 0.4D,
                    0.18D, 0.0D, GOLD);
                int embers = FxClient.count(12, false);
                for (int i = 0; i < embers; i++) {
                    p(ModParticles.EMBER.get(), f[0] + (r.nextDouble() - 0.5D) * 1.2D, f[1] + 1.0D + r.nextDouble(),
                        f[2] + (r.nextDouble() - 0.5D) * 1.2D, 0.0D, 0.06D + r.nextDouble() * 0.04D, 0.0D, null);
                }
                burst(level, ModParticles.SPARKLE.get(), f[0], f[1] + height(entity) * 1.13D + 0.4D, f[2],
                    FxClient.count(12, true), 0.16D, GOLD);
            }
            case 2 -> { // second wind: soft green-gold motes rising round the Captain
                ring(level, ModParticles.MOTE.get(), f[0], f[1] + 0.2D, f[2], FxClient.count(14, true), 0.6D, -0.01D,
                    0.05D, SOFT_GREEN);
                burst(level, ModParticles.SPARKLE.get(), f[0], f[1] + 1.3D, f[2], FxClient.count(6, false), 0.08D,
                    PALE_GOLD);
            }
            case 3 -> { // bow: a clean silver glint at the target
                burst(level, ModParticles.SPARKLE.get(), x, y + 1.0D, z, FxClient.count(10, true), 0.14D, SILVER);
                ring(level, ModParticles.MOTE.get(), x, y + 0.1D, z, FxClient.count(6, false), 0.4D, 0.04D, 0.01D,
                    SILVER);
            }
            case 4 -> { // heavy: a dust ring over the area and sparks off the strike
                double radius = Math.max(1.0D, range);
                int n = FxClient.count((int) Math.min(24, 8 + radius * 4), true);
                for (int i = 0; i < n; i++) {
                    double a = i * Mth.TWO_PI / Math.max(1, n);
                    FxParticle puff = p(ModParticles.FLOUR_PUFF.get(), x + Math.cos(a) * radius * 0.6D, y + 0.1D,
                        z + Math.sin(a) * radius * 0.6D, Math.cos(a) * 0.12D, 0.02D, Math.sin(a) * 0.12D, DUST_BROWN);
                    if (puff != null) {
                        puff.scaleSize(2.0F).opacity(0.6F);
                    }
                }
                burst(level, ModParticles.ANVIL_SPARK.get(), x, y + 0.6D, z, FxClient.count(10, false), 0.3D, null);
            }
            case 5 -> { // sweep: a whirling ring of sparkles at the weapon's reach
                double radius = Math.max(1.5D, range);
                int n = FxClient.count(18, true);
                for (int i = 0; i < n; i++) {
                    double a = i * Mth.TWO_PI / Math.max(1, n);
                    p(ModParticles.SPARKLE.get(), f[0] + Math.cos(a) * radius, f[1] + 1.0D, f[2] + Math.sin(a) * radius,
                        -Math.sin(a) * 0.12D, 0.0D, Math.cos(a) * 0.12D, PALE_GOLD);
                }
            }
            case 6 -> { // shield: sparks off the rim and a puff of linen dust
                burst(level, ModParticles.ANVIL_SPARK.get(), x, y + 1.0D, z, FxClient.count(10, true), 0.28D, null);
                FxParticle puff = p(ModParticles.FLOUR_PUFF.get(), x, y + 0.9D, z, 0.0D, 0.02D, 0.0D, LINEN);
                if (puff != null) {
                    puff.scaleSize(2.4F).opacity(0.5F);
                }
            }
            case 7 -> { // execution: a crimson-gold burst
                burst(level, ModParticles.SPARKLE.get(), x, y + 1.0D, z, FxClient.count(14, true), 0.2D, CRIMSON);
                burst(level, ModParticles.SPARKLE.get(), x, y + 1.0D, z, FxClient.count(8, false), 0.12D, GOLD);
            }
            default -> burst(level, ModParticles.SPARKLE.get(), x, y + 1.0D, z, FxClient.count(10, true), 0.18D,
                captainColor(style));
        }
        FxClient.playSound(FxEffect.CAPTAIN_IMPACT, x, y + 1.0D, z, 1.0F);
    }

    // ---------------------------------------------------------- banner cloth ---

    /**
     * Where the Banner's cloth hangs, mirrored from SettlementBannerRenderer
     * (pivot 36.6/16 above the base, offset 4.25/16 side and back, half-scale
     * vanilla flag: 0.625 wide, 1.25 tall), plus its heraldic base colour.
     */
    static final class BannerCloth {
        final double cx;
        final double cz;
        final double top;
        final double width = 0.625D;
        final double height = 1.25D;
        final double ax;
        final double az;
        final double nx;
        final double nz;
        final float[] color;

        private BannerCloth(double cx, double cz, double top, double ax, double az, float[] color) {
            this.cx = cx;
            this.cz = cz;
            this.top = top;
            this.ax = ax;
            this.az = az;
            this.nx = -az;
            this.nz = ax;
            this.color = color;
        }

        static BannerCloth at(ClientLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            Direction facing = state.hasProperty(HearthBlock.FACING) ? state.getValue(HearthBlock.FACING)
                : Direction.NORTH;
            float[] color = BURGUNDY;
            if (level.getBlockEntity(pos) instanceof HearthBlockEntity banner) {
                int rgb = SettlementHeraldry.baseColor(banner.getHeraldry()).getTextureDiffuseColor();
                color = new float[] {((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F};
            }
            double theta = -facing.toYRot() * Mth.DEG_TO_RAD;
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            double side = 4.25D / 16.0D;
            double back = -4.25D / 16.0D;
            double ox = cos * side + sin * back;
            double oz = -sin * side + cos * back;
            double top = pos.getY() + 36.6D / 16.0D;
            return new BannerCloth(pos.getX() + 0.5D + ox, pos.getZ() + 0.5D + oz, top, cos, -sin, color);
        }

        /** The lantern on the counter top (model point 2.25, 13.5, 5.25 px, authored facing north). */
        static double[] lantern(BlockPos pos, Direction facing) {
            double lx = 2.25D / 16.0D;
            double lz = 5.25D / 16.0D;
            int turns = switch (facing) {
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
                default -> 0;
            };
            for (int i = 0; i < turns; i++) {
                double nxp = 1.0D - lz;
                lz = lx;
                lx = nxp;
            }
            return new double[] {pos.getX() + lx, pos.getY() + 14.5D / 16.0D, pos.getZ() + lz};
        }
    }
}
