package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.BlessingEvents;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** End-to-end server hooks for physical, target-bound Blessings. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingEffectGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_target_damage")
    public void personalAndBuildingDamageUseStrongestRankWithoutStacking(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Skjoldheim",
            new BlockPos(8, 1, 8));
        SettlerEntity guard = settler(helper, settlement, Profession.GUARD,
            new BlockPos(7, 1, 8));

        apply(helper, guard, BlessingId.WARDEN_OATH, 2);
        apply(helper, guard, BlessingId.HEARTHWARD, 3);
        Building hall = registeredBuilding(helper, settlement,
            new BlockPos(5, 0, 6), new BlockPos(10, 4, 10));
        apply(helper, hall, BlessingId.WARDEN_OATH, 3);
        apply(helper, hall, BlessingId.HEARTHWARD, 2);

        RaidPlan plan = plan(10L);
        RaiderEntity raider = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        armActiveMirror(settlement, plan, List.of(raider.getUUID()));

        helper.assertTrue(BlessingEffects.effectiveSettlerRank(settlement,
                guard, BlessingId.WARDEN_OATH) == 3,
            "personal rank II plus building rank III must resolve to III, not V");
        helper.assertTrue(BlessingEffects.effectiveSettlerRank(settlement,
                guard, BlessingId.HEARTHWARD) == 3,
            "personal rank III plus building rank II must resolve to III, not V");

        DamageContainer outgoing = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(guard), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(raider, outgoing));
        helper.assertTrue(Math.abs(outgoing.getNewDamage() - 13.0F) < 0.001F,
            "max-not-stack rank III Warden's Oath must deal exactly +30%; got "
                + outgoing.getNewDamage());

        DamageContainer incoming = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(raider), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(guard, incoming));
        helper.assertTrue(Math.abs(incoming.getNewDamage() - 7.0F) < 0.001F,
            "max-not-stack rank III Hearthward must prevent exactly 30%; got "
                + incoming.getNewDamage());

        SettlerEntity farmer = settler(helper, settlement, Profession.FARMER,
            new BlockPos(3, 1, 3));
        apply(helper, farmer, BlessingId.WARDEN_OATH, 3);
        DamageContainer civilian = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(farmer), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(raider, civilian));
        helper.assertTrue(civilian.getNewDamage() == 10.0F,
            "Warden's Oath must not turn a civilian profession into a fighter");

        SettlerEntity outside = settler(helper, settlement, Profession.GUARD,
            new BlockPos(2, 1, 2));
        DamageContainer unblessed = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(outside), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(raider, unblessed));
        helper.assertTrue(unblessed.getNewDamage() == 10.0F,
            "an unblessed settler outside every blessed building gets no legacy "
                + "settlement-wide fallback");

        cleanup(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_authority_scope")
    public void targetBlessingsRejectStraysScoutsMobsAndNeighbouringSettlements(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Velsignet",
            new BlockPos(8, 1, 8));
        SettlerEntity guard = settler(helper, settlement, Profession.GUARD,
            new BlockPos(7, 1, 8));
        apply(helper, guard, BlessingId.WARDEN_OATH, 1);
        apply(helper, guard, BlessingId.HEARTHWARD, 1);
        apply(helper, guard, BlessingId.THORNED_ROADS, 1);

        RaidPlan plan = plan(11L);
        RaiderEntity participant = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        RaiderEntity stray = raider(helper, settlement, plan,
            new BlockPos(10, 1, 8));
        RaiderEntity scout = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(11, 1, 8));
        scout.markScout(settlement.id);
        armActiveMirror(settlement, plan,
            List.of(participant.getUUID(), scout.getUUID()));

        helper.assertTrue(BlessingEffects.isAuthorizedRaidParticipant(
                settlement, participant),
            "the exact sealed recurring UUID must be authorized");
        helper.assertTrue(!BlessingEffects.isAuthorizedRaidParticipant(
                settlement, stray),
            "a same-plan UUID missing from the sealed set must be rejected");
        helper.assertTrue(!BlessingEffects.isAuthorizedRaidParticipant(
                settlement, scout),
            "a telegraph scout stays excluded even if malformed capture included it");

        DamageContainer strayHit = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(guard), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(stray, strayHit));
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(stray,
            strayHit));
        helper.assertTrue(strayHit.getNewDamage() == 10.0F
                && stray.transientBlessingSnareRank(
                    helper.getLevel().getGameTime()) == 0,
            "neither Warden damage nor personal snare may affect a stray");

        DamageContainer strayIncoming = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(stray), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(guard, strayIncoming));
        helper.assertTrue(strayIncoming.getNewDamage() == 10.0F,
            "Hearthward must not reduce an unsealed stray's hit");

        DamageContainer scoutHit = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(guard), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(scout, scoutHit));
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(scout,
            scoutHit));
        helper.assertTrue(scoutHit.getNewDamage() == 10.0F
                && scout.transientBlessingSnareRank(
                    helper.getLevel().getGameTime()) == 0,
            "a scout may never inherit either offensive Blessing effect");

        Settlement neighbour = settlement(helper, "Nabobygd",
            new BlockPos(3, 1, 3));
        SettlerEntity foreign = settler(helper, neighbour, Profession.GUARD,
            new BlockPos(4, 1, 3));
        apply(helper, foreign, BlessingId.WARDEN_OATH, 3);
        apply(helper, foreign, BlessingId.HEARTHWARD, 3);
        apply(helper, foreign, BlessingId.THORNED_ROADS, 3);
        DamageContainer foreignAttack = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(foreign), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(participant, foreignAttack));
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(participant,
            foreignAttack));
        helper.assertTrue(foreignAttack.getNewDamage() == 10.0F
                && participant.transientBlessingSnareRank(
                    helper.getLevel().getGameTime()) == 0,
            "a blessed neighbour must grant neither damage nor snare against "
                + "this settlement's raid");

        DamageContainer foreignTarget = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(participant), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(foreign, foreignTarget));
        helper.assertTrue(foreignTarget.getNewDamage() == 10.0F,
            "Hearthward must not protect a neighbouring settlement");

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(6, 1, 6));
        DamageContainer vanillaMob = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(guard), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(zombie, vanillaMob));
        helper.assertTrue(vanillaMob.getNewDamage() == 10.0F,
            "target Blessings must not become a broad bonus against vanilla mobs");

        settlement.pendingRaid = null;
        DamageContainer afterRaid = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(guard), 10.0F);
        BlessingEvents.onIncomingDamage(
            new LivingIncomingDamageEvent(participant, afterRaid));
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(participant,
            afterRaid));
        helper.assertTrue(afterRaid.getNewDamage() == 10.0F
                && participant.transientBlessingSnareRank(
                    helper.getLevel().getGameTime()) == 0,
            "permanent target rank is armed only during an authorized raid");

        cleanup(helper, settlement, neighbour);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_personal_snare")
    public void personalThornedRoadsIsTransientNonStackingAndClearsOnReloadAndRaidEnd(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Tornevik",
            new BlockPos(8, 1, 8));
        SettlerEntity defender = settler(helper, settlement, Profession.FARMER,
            new BlockPos(7, 1, 8));
        apply(helper, defender, BlessingId.THORNED_ROADS, 3);
        RaidPlan plan = plan(12L);
        RaiderEntity raider = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        armActiveMirror(settlement, plan, List.of(raider.getUUID()));

        // A post event with no inflicted health loss must never create a free
        // snare. This models a hit zeroed by shield/reduction after incoming.
        DamageContainer zeroed = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(defender), 10.0F);
        zeroed.setNewDamage(0.0F);
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(raider,
            zeroed));
        helper.assertTrue(raider.transientBlessingSnareRank(
                helper.getLevel().getGameTime()) == 0,
            "a zero-final-damage hit must not apply personal Thorned Roads");

        // Use the real LivingEntity damage pipeline once. Besides end-to-end
        // damage ordering, this proves the annotated subscriber is registered:
        // no direct BlessingEvents handler call creates the first snare.
        float healthBefore = raider.getHealth();
        boolean damaged = raider.hurt(
            helper.getLevel().damageSources().mobAttack(defender), 2.0F);
        long startedAt = helper.getLevel().getGameTime();
        helper.assertTrue(damaged && raider.getHealth() < healthBefore
                && raider.transientBlessingSnareRank(startedAt) == 3,
            "a real inflicted settler hit must reach the event bus and arm "
                + "rank-III personal Thorned Roads");
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        AttributeModifier first = raider.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER);
        helper.assertTrue(first != null
                && Math.abs(first.amount() + 0.24D) < 1.0E-9D,
            "rank-III personal Thorned Roads must apply one transient -24% snare");

        // Refreshing the same rank updates one fixed modifier id, never stacks.
        DamageContainer repeatedHit = new DamageContainer(
            helper.getLevel().damageSources().mobAttack(defender), 1.0F);
        BlessingEvents.onDamageApplied(new LivingDamageEvent.Post(raider,
            repeatedHit));
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        AttributeModifier repeated = raider.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER);
        helper.assertTrue(repeated != null
                && Math.abs(repeated.amount() + 0.24D) < 1.0E-9D,
            "repeat hits must retain one identical rank-III modifier");

        raider.applyTransientBlessingSnare(1, startedAt, 100);
        helper.assertTrue(raider.transientBlessingSnareRank(startedAt) == 3,
            "a weaker snare must neither replace nor extend rank III");

        // Let the actual server clock cross the bounded expiry. The normal
        // tick handler, rather than a future-time getter in the test, must
        // clear both runtime state and the transient attribute modifier.
        helper.runAfterDelay(BlessingEvents.PERSONAL_SNARE_DURATION_TICKS + 1,
            () -> {
                long now = helper.getLevel().getGameTime();
                BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
                helper.assertTrue(raider.transientBlessingSnareRank(now) == 0
                        && raider.getAttribute(Attributes.MOVEMENT_SPEED)
                            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER)
                            == null,
                    "real server time expiry must clear state and modifier");

                // readAdditionalSaveData explicitly clears runtime state: even
                // an already-used object cannot carry a snare through reload.
                CompoundTag saved = new CompoundTag();
                raider.addAdditionalSaveData(saved);
                RaiderEntity reloaded = helper.spawn(ModEntities.RAIDER.get(),
                    new BlockPos(10, 1, 8));
                reloaded.setNoAi(true);
                reloaded.applyTransientBlessingSnare(3, now, 100);
                reloaded.readAdditionalSaveData(saved);
                helper.assertTrue(
                    reloaded.transientBlessingSnareRank(now) == 0,
                    "transient snare state must never survive entity NBT reload");

                raider.applyTransientBlessingSnare(3, now, 100);
                BlessingEvents.onRaiderTick(
                    new EntityTickEvent.Post(raider));
                settlement.pendingRaid = null;
                BlessingEvents.onRaiderTick(
                    new EntityTickEvent.Post(raider));
                helper.assertTrue(
                    raider.transientBlessingSnareRank(now) == 0
                        && raider.getAttribute(Attributes.MOVEMENT_SPEED)
                            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER)
                            == null,
                    "raid end must clear runtime state and modifier immediately");

                cleanup(helper, settlement);
                helper.succeed();
            });
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_thorned_max")
    public void personalAndBuildingThornedRoadsUseMaxInsteadOfAdding(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Tornemaks",
            new BlockPos(8, 1, 8));
        Building road = registeredBuilding(helper, settlement,
            new BlockPos(6, 0, 6), new BlockPos(10, 4, 10));
        apply(helper, road, BlessingId.THORNED_ROADS, 2);
        RaidPlan plan = plan(14L);
        RaiderEntity raider = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        armActiveMirror(settlement, plan, List.of(raider.getUUID()));
        raider.applyTransientBlessingSnare(3, helper.getLevel().getGameTime(),
            BlessingEvents.PERSONAL_SNARE_DURATION_TICKS);

        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        AttributeModifier modifier = raider.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER);
        helper.assertTrue(modifier != null
                && Math.abs(modifier.amount() + 0.24D) < 1.0E-9D,
            "personal rank III plus building rank II must resolve to -24%, "
                + "never add to an invalid -40%");

        cleanup(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_building_zone")
    public void buildingThornedRoadsRefreshesOnInvalidationAndThrottlesHotTicks(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Torneborg",
            new BlockPos(8, 1, 8));
        Building gate = registeredBuilding(helper, settlement,
            new BlockPos(6, 0, 6), new BlockPos(10, 4, 10));
        apply(helper, gate, BlessingId.THORNED_ROADS, 2);
        RaidPlan plan = plan(13L);
        RaiderEntity raider = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        armActiveMirror(settlement, plan, List.of(raider.getUUID()));

        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        AttributeModifier modifier = raider.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER);
        helper.assertTrue(modifier != null
                && Math.abs(modifier.amount() + 0.16D) < 1.0E-9D,
            "a rank-II building zone must slow its authorized raider by 16%");
        long lookups = settlement.buildingBlessingIndexLookups();
        long rebuilds = settlement.buildingBlessingIndexRebuilds();

        for (int i = 0; i < 20; i++) {
            BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        }
        helper.assertTrue(settlement.buildingBlessingIndexLookups() == lookups
                && settlement.buildingBlessingIndexRebuilds() == rebuilds,
            "unchanged same-tick hooks must reuse the raider cache: no spatial "
                + "lookup, rebuild or packet-producing modifier update");

        apply(helper, gate, BlessingId.THORNED_ROADS, 1);
        settlement.invalidateBuildingBlessingIndex();
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        AttributeModifier upgraded = raider.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER);
        helper.assertTrue(upgraded != null
                && Math.abs(upgraded.amount() + 0.24D) < 1.0E-9D
                && settlement.buildingBlessingIndexLookups() == lookups + 1,
            "rank binding invalidation must bypass the throttle and expose III now");

        gate.bounds = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(1, 0, 1)),
            helper.absolutePos(new BlockPos(3, 4, 3)));
        settlement.invalidateBuildingBlessingIndex();
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        helper.assertTrue(raider.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER) == null,
            "resurveyed bounds invalidation must remove a stale zone immediately");

        gate.bounds = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(6, 0, 6)),
            helper.absolutePos(new BlockPos(10, 4, 10)));
        settlement.invalidateBuildingBlessingIndex();
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        helper.assertTrue(raider.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER) != null,
            "a healthy re-survey must restore the valid zone on the same revision path");

        gate.valid = false;
        settlement.invalidateBuildingBlessingIndex();
        BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
        helper.assertTrue(raider.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(BlessingEvents.THORNED_ROADS_MODIFIER) == null,
            "unlink invalidation must remove a stale building slow immediately");

        cleanup(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_effect_spatial_bound")
    public void buildingIndexIsLazyInvalidatedAndCandidateBounded(
        GameTestHelper helper) {
        Settlement overflow = new Settlement(UUID.randomUUID(), "Overlapp",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(4, 4, 4));
        for (int i = 0; i < 96; i++) {
            Building overlapping = building(min, max);
            apply(helper, overlapping, BlessingId.WARDEN_OATH, 1);
            overflow.buildings.add(overlapping);
        }

        int rank = overflow.strongestBuildingBlessingAt(
            min.getX() + 2, min.getY() + 1, min.getZ() + 2,
            BlessingId.WARDEN_OATH);
        helper.assertTrue(rank == 0
                && overflow.buildingBlessingOverflowedBuckets() > 0
                && overflow.lastBuildingBlessingCandidateChecks() == 0,
            "pathological overlap must fail closed for the affected Blessing "
                + "instead of choosing a partial, order-dependent building");
        long overflowRebuilds = overflow.buildingBlessingIndexRebuilds();
        for (int i = 0; i < 512; i++) {
            helper.assertTrue(overflow.strongestBuildingBlessingAt(
                    min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                    BlessingId.WARDEN_OATH) == 0
                    && overflow.lastBuildingBlessingCandidateChecks() == 0,
                "an overflowed bucket must stay deterministic and scan-free");
        }
        helper.assertTrue(overflow.buildingBlessingIndexRebuilds()
                == overflowRebuilds,
            "512 hot reads must not rescan or rebuild the building list");

        // Normal, under-cap overlap retains full semantics and proves tracked
        // add/remove invalidation independently of overflow quarantine.
        Settlement bounded = new Settlement(UUID.randomUUID(), "Indeks",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        for (int i = 0;
             i < Settlement.maxBuildingBlessingCandidatesPerChunk(); i++) {
            Building overlapping = building(min, max);
            apply(helper, overlapping, BlessingId.WARDEN_OATH, 1);
            bounded.buildings.add(overlapping);
        }
        helper.assertTrue(bounded.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 1
                && bounded.lastBuildingBlessingCandidateChecks()
                    <= Settlement.maxBuildingBlessingCandidatesPerChunk()
                && bounded.buildingBlessingOverflowedBuckets() == 0,
            "an exactly-full bucket must preserve rank with bounded checks");
        long rebuilds = bounded.buildingBlessingIndexRebuilds();

        Building stronger = building(min, max);
        apply(helper, stronger, BlessingId.WARDEN_OATH, 3);
        // Remove one first so the new building remains under the hard cap.
        bounded.buildings.remove(0);
        bounded.buildings.add(stronger); // tracked structural invalidation
        helper.assertTrue(bounded.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 3
                && bounded.buildingBlessingIndexRebuilds() == rebuilds + 1,
            "tracked remove/add must invalidate once at the next lazy lookup "
                + "and preserve strongest-rank semantics");

        bounded.buildings.remove(stronger); // dissolve path uses this API
        helper.assertTrue(bounded.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 1
                && bounded.buildingBlessingIndexRebuilds() == rebuilds + 2,
            "structural removal must invalidate and remove the dissolved zone");

        Building previous = bounded.buildings.get(0);
        Building replacement = building(min, max);
        apply(helper, replacement, BlessingId.WARDEN_OATH, 2);
        long beforeReplace = bounded.buildingBlessingIndexRebuilds();
        bounded.buildings.replaceAll(candidate -> candidate == previous
            ? replacement : candidate);
        helper.assertTrue(bounded.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 2
                && bounded.buildingBlessingIndexRebuilds() == beforeReplace + 1,
            "replaceAll must invalidate the lazy index even though ArrayList "
                + "mutates its backing array without calling set");

        boolean subListMutationBlocked = false;
        try {
            bounded.buildings.subList(0, 1).clear();
        } catch (UnsupportedOperationException expected) {
            subListMutationBlocked = true;
        }
        helper.assertTrue(subListMutationBlocked
                && bounded.strongestBuildingBlessingAt(
                    min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                    BlessingId.WARDEN_OATH) == 2,
            "mutable subList views must be blocked so they cannot bypass "
                + "root-list spatial invalidation");

        Building oversized = building(
            new BlockPos(min.getX() - 2048, min.getY(), min.getZ()),
            new BlockPos(min.getX() + 2048, max.getY(), max.getZ()));
        apply(helper, oversized, BlessingId.HEARTHWARD, 3);
        bounded.buildings.add(oversized);
        helper.assertTrue(bounded.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.HEARTHWARD) == 0
                && bounded.buildingBlessingRejectedOversizedBuildings() == 1,
            "malformed giant bounds must be explicitly rejected and telemetered "
                + "instead of allocating an unbounded chunk index");

        Settlement restored = Settlement.readNbt(bounded.writeNbt());
        helper.assertTrue(restored.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 2
                && restored.buildingBlessingIndexRebuilds() == 1
                && restored.buildingBlessingRejectedOversizedBuildings() == 1,
            "NBT load must start dirty, lazily rebuild once, retain valid zones "
                + "and reject the same giant bound again");

        Settlement tooMany = new Settlement(UUID.randomUUID(), "Avgrenset",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        for (int i = 0; i <= Settlement.maxBuildingsInBlessingIndex(); i++) {
            Building record = building(min, max);
            if (i == 0) {
                apply(helper, record, BlessingId.WARDEN_OATH, 3);
            }
            tooMany.buildings.add(record);
        }
        helper.assertTrue(tooMany.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 0
                && tooMany.buildingBlessingIndexGloballyDisabled()
                && tooMany.lastBuildingBlessingCandidateChecks() == 0,
            "a settlement beyond the total record cap must disable the whole "
                + "index without scanning a partial, order-dependent prefix");

        tooMany.buildings.remove(tooMany.buildings.size() - 1);
        helper.assertTrue(tooMany.strongestBuildingBlessingAt(
                min.getX() + 2, min.getY() + 1, min.getZ() + 2,
                BlessingId.WARDEN_OATH) == 3
                && !tooMany.buildingBlessingIndexGloballyDisabled(),
            "after pathological record input is removed and invalidated, the "
                + "index must rebuild normally instead of staying poisoned");

        Settlement tooManyBuckets = new Settlement(UUID.randomUUID(), "Karttak",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        int chunkSpan = Settlement.maxChunksPerBlessedBuilding();
        int requiredBuildings = Settlement.maxChunkBucketsInBlessingIndex()
            / chunkSpan + 1;
        int baseChunkX = min.getX() >> 4;
        int baseChunkZ = min.getZ() >> 4;
        for (int i = 0; i < requiredBuildings; i++) {
            int firstChunkX = baseChunkX + i * (chunkSpan + 1);
            BlockPos longMin = new BlockPos(firstChunkX << 4, min.getY(),
                baseChunkZ << 4);
            BlockPos longMax = new BlockPos(
                ((firstChunkX + chunkSpan - 1) << 4) + 15,
                max.getY(), (baseChunkZ << 4) + 15);
            Building longBuilding = building(longMin, longMax);
            apply(helper, longBuilding, BlessingId.HEARTHWARD, 1);
            tooManyBuckets.buildings.add(longBuilding);
        }
        int firstIndexedX = baseChunkX << 4;
        helper.assertTrue(tooManyBuckets.strongestBuildingBlessingAt(
                firstIndexedX + 1, min.getY() + 1, (baseChunkZ << 4) + 1,
                BlessingId.HEARTHWARD) == 0
                && tooManyBuckets.buildingBlessingIndexGloballyDisabled(),
            "more than 2048 generated chunk buckets must clear and disable the "
                + "whole partial index deterministically");

        tooManyBuckets.buildings.remove(tooManyBuckets.buildings.size() - 1);
        helper.assertTrue(tooManyBuckets.strongestBuildingBlessingAt(
                firstIndexedX + 1, min.getY() + 1, (baseChunkZ << 4) + 1,
                BlessingId.HEARTHWARD) == 1
                && !tooManyBuckets.buildingBlessingIndexGloballyDisabled(),
            "removing the bucket overflow must invalidate and recover a normal "
                + "exactly-2048-bucket index");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_first_raid_authority")
    public void firstRaidUsesItsSealedParticipantUuid(GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Fyrvakt",
            new BlockPos(8, 1, 8));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        RaiderEntity participant = raider(helper, settlement, plan,
            new BlockPos(9, 1, 8));
        RaiderEntity stray = raider(helper, settlement, plan,
            new BlockPos(10, 1, 8));
        armActiveFirstRaid(settlement, plan, List.of(participant.getUUID()));

        helper.assertTrue(BlessingEffects.isAuthorizedRaidParticipant(
                settlement, participant),
            "a UUID sealed by authored first-raid authority must be authorized");
        helper.assertTrue(!BlessingEffects.isAuthorizedRaidParticipant(
                settlement, stray),
            "an identically assigned first-raid stray must fail UUID authority");

        cleanup(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_effect_legacy_authority")
    public void legacyBridgeRequiresExactSurvivingAssignment(GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Gamlevakt",
            new BlockPos(8, 1, 8));
        RaidPlan legacy = plan(3L);
        settlement.pendingRaid = legacy;
        settlement.raidLifecycle = RaidLifecycle.migrateV0(legacy, false, false);

        RaiderEntity matching = raider(helper, settlement, legacy,
            new BlockPos(9, 1, 8));
        RaidPlan wrongCaptain = new RaidPlan(UUID.randomUUID(), legacy.objective(),
            legacy.approachDegrees(), legacy.night());
        RaiderEntity stray = raider(helper, settlement, wrongCaptain,
            new BlockPos(10, 1, 8));
        RaiderEntity scout = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(11, 1, 8));
        scout.markScout(settlement.id);

        helper.assertTrue(settlement.raidLifecycle.isLegacyBridgeActive()
                && BlessingEffects.isAuthorizedRaidParticipant(settlement, matching),
            "the v0 bridge may authorize only its exact surviving assignment");
        helper.assertTrue(!BlessingEffects.isAuthorizedRaidParticipant(
                settlement, stray)
                && !BlessingEffects.isAuthorizedRaidParticipant(settlement, scout),
            "wrong captain and scout must both fail the narrow legacy bridge");

        settlement.pendingRaid = new RaidPlan(legacy.captainId(),
            legacy.objective(), legacy.approachDegrees(), legacy.night() + 1L);
        helper.assertTrue(!BlessingEffects.isAuthorizedRaidParticipant(
                settlement, matching),
            "a mismatched compatibility mirror must fail the bridge closed");

        Settlement recurring = settlement(helper, "Gammelhamn",
            new BlockPos(3, 1, 3));
        recurring.raidLifecycle = completedFirstLifecycle();
        recurring.recurringRaidRun = RecurringRaidRun.migrateLegacy(legacy);
        recurring.pendingRaid = legacy;
        RaiderEntity recurringMatch = raider(helper, recurring, legacy,
            new BlockPos(4, 1, 3));
        helper.assertTrue(recurring.recurringRaidRun.isLegacyBridgeActive()
                && BlessingEffects.isAuthorizedRaidParticipant(
                    recurring, recurringMatch),
            "the recurring migration bridge must use the same exact fallback");

        cleanup(helper, settlement, recurring);
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper, String name,
                                         BlockPos relativeCenter) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(relativeCenter));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static RaidPlan plan(long night) {
        return new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, night);
    }

    private static void armActiveMirror(Settlement settlement, RaidPlan plan,
                                        List<UUID> participants) {
        RaidAuthorityFixtures.armActive(settlement, plan, participants);
    }

    private static void armActiveFirstRaid(Settlement settlement, RaidPlan plan,
                                           List<UUID> participants) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        boolean armed = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(plan)
            && lifecycle.beginFirstRaid(plan);
        for (UUID participant : participants) {
            armed &= lifecycle.recordParticipant(participant);
        }
        armed &= lifecycle.sealParticipants();
        if (!armed) {
            throw new IllegalStateException("could not arm strict first-raid fixture");
        }
        settlement.raidLifecycle = lifecycle;
        settlement.pendingRaid = plan;
    }

    private static RaidLifecycle completedFirstLifecycle() {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID participant = UUID.randomUUID();
        boolean completed = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(first)
            && lifecycle.beginFirstRaid(first)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false);
        if (!completed) {
            throw new IllegalStateException("could not complete first-raid fixture");
        }
        return lifecycle;
    }

    private static void apply(GameTestHelper helper, SettlerEntity settler,
                              BlessingId blessing, int ranks) {
        for (int i = 0; i < ranks; i++) {
            helper.assertTrue(settler.applyBlessing(blessing)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "fixture must apply one permanent settler rank");
        }
    }

    private static void apply(GameTestHelper helper, Building building,
                              BlessingId blessing, int ranks) {
        for (int i = 0; i < ranks; i++) {
            helper.assertTrue(building.applyBlessing(blessing)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "fixture must apply one permanent building rank");
        }
    }

    private static Building registeredBuilding(GameTestHelper helper,
                                               Settlement settlement,
                                               BlockPos relativeMin,
                                               BlockPos relativeMax) {
        BlockPos plaque = new BlockPos(relativeMin.getX(), 6,
            relativeMin.getZ());
        return GameTestFixtures.registerWithBounds(helper, settlement,
            BuildingType.HOUSE, relativeMin, plaque,
            BoundingBox.fromCorners(helper.absolutePos(relativeMin),
                helper.absolutePos(relativeMax)));
    }

    private static Building building(BlockPos min, BlockPos max) {
        Building building = new Building(UUID.randomUUID(), BuildingType.HOUSE,
            min, min, BoundingBox.fromCorners(min, max));
        building.valid = true;
        return building;
    }

    private static SettlerEntity settler(GameTestHelper helper,
                                         Settlement settlement,
                                         Profession profession,
                                         BlockPos relativePos) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relativePos);
        settler.setNoAi(true);
        settler.setSettlerName("Vakt");
        settler.bindTo(settlement.id, settlement.center);
        settler.assignProfession(profession);
        settlement.putRecord(settler.getUUID(), settler.getSettlerName(), profession);
        return settler;
    }

    private static RaiderEntity raider(GameTestHelper helper,
                                       Settlement settlement,
                                       RaidPlan plan,
                                       BlockPos relativePos) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), relativePos);
        raider.setNoAi(true);
        raider.assign(plan.captainId(), settlement.id, plan.objective(),
            1.0F, false);
        return raider;
    }

    private static void cleanup(GameTestHelper helper,
                                Settlement... settlements) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        for (Settlement settlement : settlements) {
            data.settlements.remove(settlement.id);
        }
        data.setDirty();
    }
}
