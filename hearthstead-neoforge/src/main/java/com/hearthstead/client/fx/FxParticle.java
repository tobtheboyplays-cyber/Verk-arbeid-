package com.hearthstead.client.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * One particle class for every Bannerhold particle type; {@link Style} picks
 * the motion, envelope and lighting. Every live instance reports to the
 * client budget from its tick ({@link FxClient#BUDGET}), and the provider
 * refuses to create one over budget or with {@code [particles] enabled=false}
 * -- so the budget also covers particles spawned by clip sidecars.
 */
public class FxParticle extends TextureSheetParticle {

    /** Behaviour presets. {@code animated}: frames play over the life; else a random frame. */
    public enum Style {
        SPARKLE(true, true), MOTE(true, true), EMBER(true, true), FIREFLY(true, true),
        SPARK(true, true), PUFF(false, true), STEAM(false, true), CHIP(false, false),
        COIN(true, false), CONFETTI(false, false), DUST(false, false);

        final boolean glow;
        final boolean animated;

        Style(boolean glow, boolean animated) {
            this.glow = glow;
            this.animated = animated;
        }
    }

    private final SpriteSet sprites;
    private final Style style;
    private final float baseSize;
    private final float phase;
    private float baseAlpha = 1.0F;
    private float spin;
    private float r0 = 1.0F;
    private float g0 = 1.0F;
    private float b0 = 1.0F;

    protected FxParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
                         SpriteSet sprites, Style style) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.style = style;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.phase = random.nextFloat() * Mth.TWO_PI;
        this.hasPhysics = false;
        float size;
        switch (style) {
            case SPARKLE -> {
                lifetime = 14 + random.nextInt(10);
                gravity = 0.0F;
                friction = 0.86F;
                size = 0.11F + random.nextFloat() * 0.06F;
                tint(1.0F, 0.86F, 0.45F);
            }
            case MOTE -> {
                lifetime = 30 + random.nextInt(22);
                gravity = -0.03F;
                friction = 0.94F;
                size = 0.06F + random.nextFloat() * 0.04F;
                tint(1.0F, 0.88F, 0.55F);
            }
            case EMBER -> {
                lifetime = 40 + random.nextInt(30);
                gravity = -0.025F;
                friction = 0.96F;
                size = 0.05F + random.nextFloat() * 0.03F;
            }
            case FIREFLY -> {
                lifetime = 90 + random.nextInt(60);
                gravity = 0.0F;
                friction = 0.9F;
                size = 0.06F + random.nextFloat() * 0.02F;
                tint(0.86F, 1.0F, 0.42F);
            }
            case SPARK -> {
                lifetime = 6 + random.nextInt(7);
                gravity = 0.9F;
                friction = 0.93F;
                size = 0.045F + random.nextFloat() * 0.02F;
                hasPhysics = true;
                tint(1.0F, 0.78F, 0.35F);
            }
            case PUFF -> {
                lifetime = 18 + random.nextInt(10);
                gravity = -0.01F;
                friction = 0.88F;
                size = 0.1F + random.nextFloat() * 0.06F;
                baseAlpha = 0.7F;
                tint(0.97F, 0.94F, 0.86F);
            }
            case STEAM -> {
                lifetime = 30 + random.nextInt(16);
                gravity = -0.035F;
                friction = 0.92F;
                size = 0.14F + random.nextFloat() * 0.06F;
                baseAlpha = 0.42F;
                tint(1.0F, 1.0F, 1.0F);
            }
            case CHIP -> {
                lifetime = 20 + random.nextInt(16);
                gravity = 0.8F;
                friction = 0.96F;
                size = 0.06F + random.nextFloat() * 0.02F;
                hasPhysics = true;
                spin = (random.nextFloat() - 0.5F) * 0.6F;
            }
            case COIN -> {
                lifetime = 18 + random.nextInt(9);
                gravity = 0.55F;
                friction = 0.95F;
                size = 0.085F;
            }
            case CONFETTI -> {
                lifetime = 45 + random.nextInt(30);
                gravity = 0.12F;
                friction = 0.92F;
                size = 0.06F + random.nextFloat() * 0.02F;
                spin = (random.nextFloat() - 0.5F) * 0.5F;
            }
            default -> { // DUST
                lifetime = 70 + random.nextInt(50);
                gravity = -0.002F;
                friction = 0.97F;
                size = 0.022F + random.nextFloat() * 0.018F;
                baseAlpha = 0.6F;
                tint(1.0F, 0.94F, 0.8F);
            }
        }
        this.baseSize = size;
        this.quadSize = size;
        if (style.animated) {
            setSpriteFromAge(sprites);
        } else {
            pickSprite(sprites);
        }
        this.alpha = style == Style.SPARKLE || style == Style.SPARK || style == Style.COIN ? baseAlpha : 0.0F;
    }

    /** Recipe tint (multiplies the sprite). */
    public FxParticle tint(float r, float g, float b) {
        r0 = r;
        g0 = g;
        b0 = b;
        setColor(r, g, b);
        return this;
    }

    public FxParticle scaleSize(float factor) {
        quadSize *= factor;
        return this;
    }

    public FxParticle life(int ticks) {
        lifetime = Math.max(2, ticks);
        return this;
    }

    public FxParticle opacity(float a) {
        baseAlpha = Mth.clamp(a, 0.0F, 1.0F);
        return this;
    }

    @Override
    public void tick() {
        super.tick();
        if (!isAlive()) {
            return;
        }
        FxClient.BUDGET.markAlive();
        float t = lifetime <= 0 ? 1.0F : (float) age / lifetime;
        if (style.animated && style != Style.COIN) {
            setSpriteFromAge(sprites);
        }
        oRoll = roll;
        switch (style) {
            case SPARKLE -> {
                alpha = baseAlpha * (t < 0.7F ? 1.0F : 1.0F - (t - 0.7F) / 0.3F);
            }
            case MOTE -> {
                xd += Mth.sin(age * 0.2F + phase) * 0.002D;
                alpha = baseAlpha * envelope(t, 0.2F, 0.35F);
            }
            case EMBER -> {
                xd += Mth.sin(age * 0.15F + phase) * 0.0015D;
                zd += Mth.cos(age * 0.13F + phase) * 0.0015D;
                float flicker = 0.75F + 0.25F * Mth.sin(age * 0.9F + phase);
                alpha = baseAlpha * envelope(t, 0.1F, 0.4F) * flicker;
                // cool from gold to deep red as it rises
                setColor(r0, g0 * (1.0F - 0.55F * t), b0 * (1.0F - 0.8F * t));
                quadSize = baseSize * (1.0F - 0.5F * t);
            }
            case FIREFLY -> {
                xd += (random.nextFloat() - 0.5F) * 0.006D;
                yd += (random.nextFloat() - 0.5F) * 0.004D;
                zd += (random.nextFloat() - 0.5F) * 0.006D;
                float pulse = 0.5F + 0.5F * Mth.sin(age * 0.12F + phase);
                alpha = baseAlpha * envelope(t, 0.15F, 0.2F) * (0.15F + 0.85F * pulse * pulse);
            }
            case SPARK -> {
                quadSize = baseSize * (1.0F - 0.6F * t);
                setColor(r0, g0 * (1.0F - 0.4F * t), b0 * (1.0F - 0.6F * t));
                if (onGround) {
                    xd *= 0.5D;
                    zd *= 0.5D;
                }
            }
            case PUFF, STEAM -> {
                quadSize = baseSize * (1.0F + (style == Style.STEAM ? 2.2F : 1.4F) * t);
                alpha = baseAlpha * envelope(t, 0.12F, 0.6F);
            }
            case CHIP -> {
                if (!onGround) {
                    roll += spin;
                }
                alpha = t < 0.8F ? 1.0F : 1.0F - (t - 0.8F) / 0.2F;
            }
            case COIN -> {
                setSprite(sprites.get((age / 2) % 4, 3));
                alpha = baseAlpha * (t < 0.75F ? 1.0F : 1.0F - (t - 0.75F) / 0.25F);
            }
            case CONFETTI -> {
                xd += Mth.sin(age * 0.25F + phase) * 0.004D;
                roll += spin;
                alpha = t < 0.8F ? 1.0F : 1.0F - (t - 0.8F) / 0.2F;
            }
            default -> { // DUST
                xd += (random.nextFloat() - 0.5F) * 0.0008D;
                zd += (random.nextFloat() - 0.5F) * 0.0008D;
                alpha = baseAlpha * envelope(t, 0.25F, 0.35F);
            }
        }
    }

    /** 0 -> 1 over {@code in}, hold, 1 -> 0 over the last {@code out} of the life. */
    private static float envelope(float t, float in, float out) {
        if (t < in) {
            return t / in;
        }
        if (t > 1.0F - out) {
            return Math.max(0.0F, (1.0F - t) / out);
        }
        return 1.0F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getLightColor(float partialTick) {
        if (style.glow) {
            return LightTexture.FULL_BRIGHT;
        }
        int packed = super.getLightColor(partialTick);
        if (style == Style.DUST) {
            // dust motes read as caught in the light: never darker than lamplight
            int block = Math.max(LightTexture.block(packed), 9);
            return LightTexture.pack(block, LightTexture.sky(packed));
        }
        return packed;
    }

    /** Provider shared by every Bannerhold type: budget and off switch first. */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final Style style;

        public Provider(SpriteSet sprites, Style style) {
            this.sprites = sprites;
            this.style = style;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double vx, double vy, double vz) {
            if (!FxClient.admit()) {
                return null;
            }
            return new FxParticle(level, x, y, z, vx, vy, vz, sprites, style);
        }
    }
}
