package com.hearthstead.settlement;

import com.hearthstead.settlement.raid.RaidBroadcast;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Sparse, world-first feedback for earned and accepted Blessings. */
public final class BlessingPresentation {

    private static final double[][] PLAQUE_RUNE_ONSET = {
        {-0.27, -0.24, 0.00}, {0.27, -0.24, 0.00}
    };
    private static final int BINDING_CONTACT_SOUND_COUNT = 1;

    /**
     * Local coordinates are right, up and forward. Each authored silhouette
     * remains distinct without colour and uses at most nine particles.
     */
    private static final double[][] WARDEN_CONTACT = {
        {0.00, -0.30, 0.00}, {0.00, -0.12, 0.00},
        {0.00, 0.06, 0.00}, {0.00, 0.24, 0.00},
        {0.00, 0.42, 0.00}, {-0.22, 0.04, -0.05},
        {0.22, 0.04, 0.05}
    };
    private static final double[][] HEARTHWARD_CONTACT = {
        {0.00, 0.38, 0.00}, {-0.22, 0.25, -0.08},
        {0.22, 0.25, 0.08}, {-0.28, 0.00, 0.00},
        {0.28, 0.00, 0.00}, {-0.16, -0.25, 0.10},
        {0.16, -0.25, -0.10}, {0.00, -0.36, 0.00}
    };
    private static final double[][] THORNED_CONTACT = {
        {0.00, -0.34, 0.00}, {0.00, -0.17, 0.00},
        {0.00, 0.00, 0.00}, {-0.15, 0.12, -0.08},
        {-0.31, 0.24, -0.16}, {0.15, 0.12, 0.08},
        {0.31, 0.24, 0.16}, {0.00, 0.18, -0.15},
        {0.00, 0.34, -0.28}
    };

    /** Announces one newly earned offer without ever opening combat UI. */
    public static void offerEarned(ServerLevel level, Settlement settlement) {
        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.blessing_waiting", settlement.name));
        level.sendParticles(ParticleTypes.WAX_ON,
            settlement.center.getX() + 0.5D, settlement.center.getY() + 1.0D,
            settlement.center.getZ() + 0.5D, 18,
            1.1D, 0.35D, 1.1D, 0.035D);
        level.playSound(null, settlement.center, SoundEvents.AMETHYST_BLOCK_CHIME,
            SoundSource.BLOCKS, 0.9F, 0.82F);
    }

    /** Announces that the shared offer became one physical, still-unbound seal. */
    public static void sealIssued(ServerLevel level, Settlement settlement,
                                  ServerPlayer recipient, BlessingId blessing) {
        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.blessing_seal_issued", recipient.getDisplayName(),
            Component.translatable("hearthstead.blessing." + blessing.id() + ".name"),
            settlement.name));
        level.sendParticles(ParticleTypes.WAX_ON,
            settlement.center.getX() + 0.5D, settlement.center.getY() + 1.0D,
            settlement.center.getZ() + 0.5D, 30,
            1.6D, 0.5D, 1.6D, 0.05D);
        level.sendParticles(ParticleTypes.SMALL_FLAME,
            settlement.center.getX() + 0.5D, settlement.center.getY() + 0.8D,
            settlement.center.getZ() + 0.5D, 8,
            0.8D, 0.25D, 0.8D, 0.025D);
        level.playSound(null, settlement.center, SoundEvents.PLAYER_LEVELUP,
            SoundSource.BLOCKS, 0.65F, 1.15F);
    }

    /**
     * Immediate, silent start of a plaque's short rune activation. The two
     * edge sparks acknowledge the authoritative APPLIED response within one
     * tick; the authored contact silhouette and sole sound land four ticks
     * later through {@link #bindingContact}.
     */
    public static void plaqueRuneOnset(ServerLevel level, BlessingId blessing,
                                      double x, double y, double z, float yaw) {
        ParticleOptions particle = bindingParticle(blessing);
        for (double[] point : PLAQUE_RUNE_ONSET) {
            point(level, particle, x, y, z, yaw,
                point[0], point[1], point[2]);
        }
    }

    /**
     * Shared contact cue for the settler's hand-to-heart beat and a plaque's
     * completed rune. It is APPLIED-only, allocation-free on use, emits one
     * positional sound and 7/8/9 particles depending on the Blessing.
     */
    public static void bindingContact(ServerLevel level, BlessingId blessing,
                                      double x, double y, double z, float yaw,
                                      SoundSource soundSource) {
        double[][] shape = bindingContactShape(blessing);
        ParticleOptions particle = bindingParticle(blessing);
        for (double[] point : shape) {
            point(level, particle, x, y, z, yaw,
                point[0], point[1], point[2]);
        }
        float pitch = switch (blessing) {
            case WARDEN_OATH -> 0.92F;
            case HEARTHWARD -> 1.18F;
            case THORNED_ROADS -> 0.74F;
        };
        for (int sound = 0; sound < BINDING_CONTACT_SOUND_COUNT; sound++) {
            level.playSound(null, x, y, z, SoundEvents.ENCHANTMENT_TABLE_USE,
                soundSource, 0.65F, pitch);
        }
    }

    /** Package-local pure seams pin the exact arrays used by production emission. */
    static int plaqueRuneOnsetParticleCount() {
        return PLAQUE_RUNE_ONSET.length;
    }

    static int bindingContactParticleCount(BlessingId blessing) {
        return bindingContactShape(blessing).length;
    }

    static int bindingContactSoundCount() {
        return BINDING_CONTACT_SOUND_COUNT;
    }

    private static double[][] bindingContactShape(BlessingId blessing) {
        return switch (blessing) {
            case WARDEN_OATH -> WARDEN_CONTACT;
            case HEARTHWARD -> HEARTHWARD_CONTACT;
            case THORNED_ROADS -> THORNED_CONTACT;
        };
    }

    private static ParticleOptions bindingParticle(BlessingId blessing) {
        return switch (blessing) {
            case WARDEN_OATH -> ParticleTypes.CRIT;
            case HEARTHWARD -> ParticleTypes.WAX_ON;
            case THORNED_ROADS -> ParticleTypes.HAPPY_VILLAGER;
        };
    }

    private static void point(ServerLevel level, ParticleOptions particle,
                              double x, double y, double z, float yaw,
                              double right, double up, double forward) {
        double radians = Math.toRadians(yaw);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        double forwardX = -rightZ;
        double forwardZ = rightX;
        level.sendParticles(particle,
            x + right * rightX + forward * forwardX,
            y + up,
            z + right * rightZ + forward * forwardZ,
            1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private BlessingPresentation() {
    }
}
