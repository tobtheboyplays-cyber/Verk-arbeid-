package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardExperience;
import com.hearthstead.entity.GuardCombatLedger;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.InspectionViewers;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.UUID;

/** Server-authoritative hostile-kill credit for guards and archers. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GuardExperienceEvents {

    /**
     * The marker lives on the dying entity itself and disappears with it. It
     * makes duplicate event delivery idempotent without a static UUID cache,
     * cleanup tick, or cross-world lifetime risk.
     */
    public static final String KILL_CREDIT_TAG = "hearthstead:guard_xp_awarded";
    /** Random persisted award identity; deliberately not the victim UUID. */
    public static final String KILL_SOURCE_ID_TAG =
        "hearthstead:guard_xp_source_id";

    /**
     * Commit only after {@code LivingEntity#die} has accepted the death.
     * {@link LivingDropsEvent} is posted after the cancellable death gate and
     * after the entity's terminal {@code dead} flag is set. Cancelling drops
     * suppresses items, not the accepted death, so receive-cancelled is
     * intentional: compatibility loot rules must not silently erase combat
     * progression for a death that really happened.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDrops(LivingDropsEvent event) {
        tryAward(event.getEntity(), event.getSource());
    }

    /**
     * Applies one death event. Public only so deterministic GameTests can
     * replay the same already-dead victim and prove the dedupe contract.
     * Production calls this exclusively from {@link #onLivingDrops}.
     *
     * @return true exactly once for an eligible hostile death
     */
    public static boolean tryAward(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level)
            || !victim.isDeadOrDying()
            || !(victim instanceof Enemy)
            || victim.getPersistentData().getBoolean(KILL_CREDIT_TAG)) {
            return false;
        }

        Entity credited = source == null ? null : source.getEntity();
        if (!(credited instanceof SettlerEntity defender)
            || !defender.isAlive()
            || defender.level() != level) {
            return false;
        }
        Profession profession = defender.getProfession();
        if (profession != Profession.GUARD && profession != Profession.ARCHER) {
            return false;
        }

        UUID sourceId;
        if (victim.getPersistentData().contains(KILL_SOURCE_ID_TAG)) {
            if (!victim.getPersistentData().hasUUID(KILL_SOURCE_ID_TAG)) {
                return false;
            }
            sourceId = victim.getPersistentData().getUUID(KILL_SOURCE_ID_TAG);
        } else {
            sourceId = UUID.randomUUID();
            victim.getPersistentData().putUUID(KILL_SOURCE_ID_TAG, sourceId);
        }

        // A crash/save seam can preserve the source and defender receipt before
        // the dying victim's convenience boolean. The persisted defender ledger
        // remains the authority and refuses the replay.
        if (defender.hasCombatExperienceSource(sourceId)) {
            victim.getPersistentData().putBoolean(KILL_CREDIT_TAG, true);
            return false;
        }

        int award = experienceFor(victim);
        int before = defender.combatExperience();
        GuardCombatLedger.XpCommit commit =
            defender.commitCombatExperienceAward(sourceId, award);

        // At the hard cap the eligible death is still consumed once, but no
        // XP terminal and therefore no telemetry record exists. Any other
        // evidence refusal fails closed without granting progression.
        if (commit == null) {
            victim.getPersistentData().putBoolean(KILL_CREDIT_TAG, true);
            return before >= GuardExperience.MAX_EXPERIENCE;
        }
        victim.getPersistentData().putBoolean(KILL_CREDIT_TAG, true);
        int actuallyAdded = commit.added();

        Settlement settlement = defender.settlement();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.GUARD_XP_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(
                settlement == null ? null : settlement.id,
                "guard:" + defender.getUUID(),
                commit.revisionBefore(), commit.revisionAfter(),
                commit.experienceBefore(), commit.experienceAfter(),
                "combat_xp:" + commit.sourceId()));

        // At the hard cap this death has been consumed (and remains marked),
        // but no XP was gained. The cue must retain one trustworthy meaning:
        // "the number increased". Attribute training and snapshot refresh are
        // progression side effects, so they follow the same gate.
        if (actuallyAdded <= 0) {
            return true;
        }

        if (settlement != null) {
            DevelopmentQuests.noteGuardExperience(level, settlement, defender,
                actuallyAdded);
        }

        // Existing ability ranks remain attribute-driven. The kill is an
        // explicit extra learning event on top of hit/shot training, so the
        // new visible XP counter and the established abilities advance in the
        // same direction without changing either rank enum's thresholds.
        if (profession == Profession.GUARD) {
            defender.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT);
        } else {
            defender.train(Attribute.DEXTERITY, ArcherRank.TRAIN_HIT);
        }

        GuardExperience.Tier tier = GuardExperience.tierOf(
            defender.combatExperience());
        float pitch = 0.95F + (tier.level() - 1) * 0.05F;
        level.playSound(null, defender.blockPosition(),
            ModSounds.GUARD_EXPERIENCE.get(), SoundSource.NEUTRAL,
            0.58F, pitch);

        // Entity data carries the XP immediately; this event-driven refresh
        // also updates the snapshot-authored Strength/Dexterity row for a
        // player who happened to be inspecting the defender at the kill.
        InspectionViewers.refreshSettler(level, defender);
        return true;
    }

    /** Stable reward table, deliberately independent of vanilla XP drops. */
    public static int experienceFor(LivingEntity victim) {
        if (victim instanceof RaiderEntity raider) {
            return raider.isCaptain()
                ? GuardExperience.RAIDER_CAPTAIN_KILL_XP
                : GuardExperience.RAIDER_KILL_XP;
        }
        return GuardExperience.HOSTILE_KILL_XP;
    }

    private GuardExperienceEvents() {
    }
}
