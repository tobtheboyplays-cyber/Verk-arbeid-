package com.hearthstead.settlement.raid;

import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.saga.Captain;
import com.hearthstead.saga.CaptainRoster;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

import java.util.Optional;

/**
 * Native presentation for the three one-way raid terminals.
 *
 * <p>The caller owns authority and invokes these methods only after the
 * matching persisted transition succeeds. Keeping presentation downstream of
 * the transition prevents a failed warning, spawn, or resolution attempt from
 * leaking a success cue. Hearthstead's processed guard alert is the warning
 * identity layer; restrained vanilla bell/horn cues provide familiar spatial
 * anchors. Terminal victory/defeat remains deliberately quiet until native
 * listening approves a stronger original mix.
 */
public final class RaidPresentation {

    /**
     * A low settlement bell plus the exact already-persisted plan. Nothing is
     * announced if captain, calendar or queued-plan identity cannot be
     * proven, and the caller uses the boolean to keep FJ-600 honest.
     */
    public static boolean warning(ServerLevel level, Settlement settlement) {
        if (!valid(level, settlement)) return false;
        WarningView warning = warningView(settlement).orElse(null);
        if (warning == null) return false;

        RaidBroadcast.send(level, settlement, Component.translatable(
            RaidEscalation.isOutlawBand(settlement) ? "hearthstead.message.raid_omen_outlaws"
                : "hearthstead.message.raid_omen", settlement.name));
        RaidBroadcast.town(level, settlement, Component.translatable(
            "hearthstead.message.raid_warning_exact",
            warning.attackNight(), Component.literal(warning.captainName()),
            settlement.name, Component.translatable(
                "hearthstead.raid.compass." + warning.approach().id()),
            Component.translatable(warning.objective().translationKey())));
        level.playSound(null, settlement.center, ModSounds.GUARD_ALERT.get(),
            SoundSource.BLOCKS, 1.35F, 0.82F);
        level.playSound(null, settlement.center, ModSounds.VILLAGE_BELL.get(),
            SoundSource.BLOCKS, 1.35F, 0.9F);
        level.sendParticles(ParticleTypes.ASH,
            settlement.center.getX() + 0.5,
            settlement.center.getY() + 1.35,
            settlement.center.getZ() + 0.5,
            18, 1.4, 0.55, 1.4, 0.012);
        return true;
    }

    /** Pure, allocation-bounded derivation used by warning and preflight. */
    public static Optional<WarningView> warningView(Settlement settlement) {
        if (settlement == null || settlement.raidLifecycle == null
            || settlement.raidLifecycle.firstState() != FirstRaidState.SCHEDULED
            || settlement.raidLifecycle.integrityLost()) {
            return Optional.empty();
        }
        RaidPlan plan = settlement.raidLifecycle.queuedPlan().orElse(null);
        if (!RaidPlan.isValid(plan)
            || plan.night() != settlement.raidLifecycle.firstAttackNight()) {
            return Optional.empty();
        }
        RaidCaptain raidCaptain = RaidDirector.captainOf(settlement,
            plan.captainId());
        if (raidCaptain == null) {
            return Optional.empty();
        }
        Captain saga = CaptainRoster.find(settlement, plan.captainId());
        String captainName = saga == null ? raidCaptain.name()
            : saga.displayName();
        if (!boundedName(captainName)) {
            return Optional.empty();
        }
        Compass approach = Compass.fromApproachDegrees(
            plan.approachDegrees());
        return Optional.of(new WarningView(plan.night(), captainName,
            plan.objective(), approach));
    }

    /** Exact server-authored facts rendered by the first-warning line. */
    public record WarningView(long attackNight, String captainName,
                              RaidObjective objective, Compass approach) {
    }

    /**
     * Eight real world-space sectors for {@link RaidDirector#formUpAt}:
     * Minecraft +Z is south, so zero degrees is SOUTH, not north.
     */
    public enum Compass {
        SOUTH("south"),
        SOUTH_WEST("south_west"),
        WEST("west"),
        NORTH_WEST("north_west"),
        NORTH("north"),
        NORTH_EAST("north_east"),
        EAST("east"),
        SOUTH_EAST("south_east");

        private final String id;

        Compass(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Compass fromApproachDegrees(float degrees) {
            if (!Float.isFinite(degrees)) {
                throw new IllegalArgumentException("non-finite raid approach");
            }
            double normalized = (degrees % 360.0D + 360.0D) % 360.0D;
            int sector = ((int) Math.floor((normalized + 22.5D) / 45.0D))
                % values().length;
            return values()[sector];
        }
    }

    /** The sealed band is live: a restrained horn plus Hearthstead identity. */
    public static void arrival(ServerLevel level, Settlement settlement) {
        if (!valid(level, settlement)) return;
        // Volume above 1 only widens the server broadcast range (32 blocks);
        // the original horn's own level stays restrained in sounds.json.
        level.playSound(null, settlement.center, ModSounds.RAID_HORN.get(),
            SoundSource.HOSTILE, 2.0F, 1.0F);
        level.playSound(null, settlement.center, ModSounds.GUARD_ALERT.get(),
            SoundSource.HOSTILE, 1.05F, 0.94F);
    }

    /** Clear, restrained terminal feedback after the raid ledger has closed. */
    public static void resolved(ServerLevel level, Settlement settlement,
                                boolean held) {
        if (!valid(level, settlement)) return;
        level.playSound(null, settlement.center,
            held ? ModSounds.RAID_WON_FANFARE.get()
                : ModSounds.RAID_LOST_TOLL.get(),
            held ? SoundSource.PLAYERS : SoundSource.BLOCKS,
            held ? 1.6F : 1.6F,
            1.0F);
        // The soundtrack's short victory / aftermath sting, under the Music slider.
        level.playSound(null, settlement.center,
            held ? ModSounds.MUSIC_RAID_VICTORY.get() : ModSounds.MUSIC_RAID_DEFEAT.get(),
            SoundSource.MUSIC, 3.0F, 1.0F);
        level.sendParticles(held ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ASH,
            settlement.center.getX() + 0.5,
            settlement.center.getY() + 1.35,
            settlement.center.getZ() + 0.5,
            held ? 28 : 22, 1.8, 0.75, 1.8, held ? 0.08 : 0.014);
    }

    private static boolean valid(ServerLevel level, Settlement settlement) {
        return level != null && settlement != null && settlement.center != null;
    }

    private static boolean boundedName(String name) {
        if (name == null || name.isBlank()
            || name.length() > RaidLogEntry.MAX_CAPTAIN_NAME
            || "?".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private RaidPresentation() {
    }
}
