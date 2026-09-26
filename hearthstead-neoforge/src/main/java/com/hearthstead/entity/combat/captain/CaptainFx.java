package com.hearthstead.entity.combat.captain;

import com.hearthstead.fx.FxHooks;
import com.hearthstead.registry.RoleItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The ONE place hero-Captain effects are emitted, keyed by hook id
 * ({@code captain.<special>.windup|impact}, {@code captain.promoted},
 * {@code captain.rally.buff}). The fx and sound lanes own the final look and
 * sound; until they swap in theirs, each hook plays a readable vanilla
 * stand-in. Never gameplay: effects only.
 */
public final class CaptainFx {
    public enum Phase { WINDUP, IMPACT }

    private CaptainFx() {
    }

    public static String hook(CaptainSpecial special, Phase phase) {
        return special.fxId() + "." + (phase == Phase.WINDUP ? "windup" : "impact");
    }

    /** Telegraph: shown every few ticks during the wind-up. */
    public static void windup(ServerLevel level, LivingEntity captain, CaptainSpecial sp, int tick) {
        if (tick == 0) {
            level.playSound(null, captain.blockPosition(), RoleItems.CAPTAIN_WINDUP_SOUND.get(),
                SoundSource.NEUTRAL, 0.9F, pitch(sp));
        }
        if (tick == 0) {
            // FX lane: one payload; the client pulses the telegraph ring for the whole wind-up.
            FxHooks.captainWindup(captain, sp);
        }
    }

    public static void impact(ServerLevel level, LivingEntity captain, CaptainSpecial sp, Vec3 at) {
        level.playSound(null, captain.blockPosition(), RoleItems.CAPTAIN_IMPACT_SOUND.get(),
            SoundSource.NEUTRAL, 1.0F, pitch(sp));
        switch (sp) {
            case RALLY_CRY -> {
                level.playSound(null, captain.blockPosition(), RoleItems.CAPTAIN_RALLY_SOUND.get(),
                    SoundSource.NEUTRAL, 1.2F, 1.0F);
            }
            default -> {
            }
        }
        // FX lane: the look per special (banner flare, sweep ring, dust, sparks...) lives in client FxRecipes.
        FxHooks.captainImpact(captain, sp, at);
    }

    /** The commissioning moment: a burst and the horn. */
    public static void promoted(ServerLevel level, LivingEntity captain) {
        level.playSound(null, captain.blockPosition(), RoleItems.CAPTAIN_PROMOTED_SOUND.get(),
            SoundSource.NEUTRAL, 1.5F, 1.0F);
        FxHooks.captainPromoted(captain);
    }

    private static float pitch(CaptainSpecial sp) {
        return switch (sp.loadout() == null ? CaptainLoadout.SWORD_SHIELD : sp.loadout()) {
            case GREAT_AXE, WARHAMMER -> 0.8F;
            case DUAL_SWORDS -> 1.2F;
            default -> 1.0F;
        };
    }
}
