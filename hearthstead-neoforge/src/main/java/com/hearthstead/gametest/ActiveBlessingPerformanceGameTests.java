package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.BlessingEvents;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.RecurringRaidRun;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Structural 1/25/50/100 matrix for permanent Blessing combat hot paths.
 *
 * <p>This holder is intentionally outside the normal {@code hearthstead}
 * namespace. The regular GameTest suite therefore retains its exact test
 * roster, while the performance harness opts into these four heavier cases.
 * Timing is informational; exact authority/effect/index counters are the
 * fail-closed regression contract.
 */
@GameTestHolder("hearthstead_active_perf")
@PrefixGameTestTemplate(false)
public final class ActiveBlessingPerformanceGameTests {
    private static final int HOT_PASSES = 25;
    private static final int MAX_RAIDERS_PER_RUN =
        RecurringRaidRun.MAX_PARTICIPANTS;

    @GameTest(templateNamespace = "hearthstead_active_perf", template = "empty16",
        timeoutTicks = 400, batch = "active_blessing_scale_001")
    public void activeBlessingScale001(GameTestHelper helper) {
        runScale(helper, 1, "active_blessing_scale_001");
    }

    @GameTest(templateNamespace = "hearthstead_active_perf", template = "empty16",
        timeoutTicks = 400, batch = "active_blessing_scale_025")
    public void activeBlessingScale025(GameTestHelper helper) {
        runScale(helper, 25, "active_blessing_scale_025");
    }

    @GameTest(templateNamespace = "hearthstead_active_perf", template = "empty16",
        timeoutTicks = 400, batch = "active_blessing_scale_050")
    public void activeBlessingScale050(GameTestHelper helper) {
        runScale(helper, 50, "active_blessing_scale_050");
    }

    @GameTest(templateNamespace = "hearthstead_active_perf", template = "empty16",
        timeoutTicks = 400, batch = "active_blessing_scale_100")
    public void activeBlessingScale100(GameTestHelper helper) {
        runScale(helper, 100, "active_blessing_scale_100");
    }

    private static void runScale(GameTestHelper helper, int scale,
                                 String caseId) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        List<Fixture> fixtures = new ArrayList<>(scale);
        List<Settlement> settlements = new ArrayList<>();
        List<Building> buildings = new ArrayList<>();
        RaiderEntity unauthorized = null;

        int personal = 0;
        int buildingZones = 0;
        int physicalZones = 0;
        int authorized = 0;
        int unauthorizedRejected = 0;
        int liveEntities = 0;
        int outgoing = 0;
        int incoming = 0;
        int snared = 0;
        int stableModifiers = 0;
        int records = 0;
        long observedLookups = 0L;
        long observedRebuilds = 0L;
        int observedCandidateChecks = 0;
        long durationNanos = 0L;
        Cleanup cleanup = null;

        try {
            int remaining = scale;
            int globalIndex = 0;
            while (remaining > 0) {
                int groupSize = Math.min(MAX_RAIDERS_PER_RUN, remaining);
                int groupIndex = settlements.size();
                Settlement settlement = new Settlement(UUID.randomUUID(),
                    "Blessing perf " + scale + "/" + groupIndex,
                    helper.absolutePos(new BlockPos(8, 1, 8)));
                settlement.radius = 24;
                settlements.add(settlement);

                BlockPos minimumRel = new BlockPos(1, 0, 1);
                BlockPos maximumRel = new BlockPos(14, 6, 14);
                // empty16 is 16x8x16 (relative y 0..7). Keep every fixture,
                // including its south support, inside the arena cleanup box.
                BlockPos plaqueRel = new BlockPos(1 + groupIndex, 6, 1);
                Building building = GameTestFixtures.registerWithBounds(
                    helper, settlement, BuildingType.HOUSE,
                    new BlockPos(1 + groupIndex, 1, 1), plaqueRel,
                    BoundingBox.fromCorners(helper.absolutePos(minimumRel),
                        helper.absolutePos(maximumRel)));
                linkPhysicalPlaque(helper, settlement, building, plaqueRel);
                physicalZones++;
                apply(building, BlessingId.WARDEN_OATH, 2);
                apply(building, BlessingId.HEARTHWARD, 2);
                apply(building, BlessingId.THORNED_ROADS, 2);
                buildings.add(building);

                RaidPlan plan = new RaidPlan(UUID.randomUUID(),
                    RaidObjective.KORN, 0.0F, 20L + groupIndex);
                List<UUID> participantIds = new ArrayList<>(groupSize);
                for (int localIndex = 0; localIndex < groupSize; localIndex++) {
                    BlockPos position = new BlockPos(
                        2 + globalIndex % 10, 1, 2 + globalIndex / 10);
                    SettlerEntity settler = helper.spawn(
                        ModEntities.SETTLER.get(), position);
                    settler.setNoAi(true);
                    settler.setNoGravity(true);
                    settler.setPersistenceRequired();
                    settler.setSettlerName("Perf guard " + globalIndex);
                    settler.bindTo(settlement.id, settlement.center);
                    settler.assignProfession(Profession.GUARD);
                    settlement.putRecord(settler.getUUID(),
                        settler.getSettlerName(), Profession.GUARD);
                    apply(settler, BlessingId.WARDEN_OATH, 1);
                    apply(settler, BlessingId.HEARTHWARD, 1);
                    apply(settler, BlessingId.THORNED_ROADS, 3);

                    RaiderEntity raider = helper.spawn(
                        ModEntities.RAIDER.get(), position);
                    raider.setNoAi(true);
                    raider.setNoGravity(true);
                    raider.setPersistenceRequired();
                    raider.assign(plan.captainId(), settlement.id,
                        plan.objective(), 1.0F, false);
                    participantIds.add(raider.getUUID());
                    fixtures.add(new Fixture(settlement, settler, raider));
                    globalIndex++;
                }
                RaidAuthorityFixtures.armActive(settlement, plan,
                    participantIds);
                remaining -= groupSize;
            }
            // Keep insertPlan's real setup survey isolated from every saved
            // settlement; the active hot-path phase begins only after all
            // physical plaque identities are complete and registered.
            for (Settlement settlement : settlements) {
                data.settlements.put(settlement.id, settlement);
            }
            data.setDirty();

            Fixture first = fixtures.getFirst();
            unauthorized = ModEntities.RAIDER.get().create(helper.getLevel());
            if (unauthorized == null) {
                throw new IllegalStateException(
                    "could not construct unauthorized control raider");
            }
            unauthorized.setNoAi(true);
            unauthorized.setNoGravity(true);
            RaidPlan firstPlan = first.settlement().pendingRaid;
            unauthorized.assign(firstPlan.captainId(), first.settlement().id,
                firstPlan.objective(), 1.0F, false);
            if (!BlessingEffects.isAuthorizedRaidParticipant(
                    first.settlement(), unauthorized)) {
                unauthorizedRejected = 1;
            }

            for (Fixture fixture : fixtures) {
                SettlerEntity settler = fixture.settler();
                RaiderEntity raider = fixture.raider();
                if (helper.getLevel().getEntity(settler.getUUID()) == settler
                    && helper.getLevel().getEntity(raider.getUUID()) == raider
                    && settler.isAlive() && raider.isAlive()) {
                    liveEntities += 2;
                }
                if (settler.blessingRank(BlessingId.WARDEN_OATH) == 1
                    && settler.blessingRank(BlessingId.HEARTHWARD) == 1
                    && settler.blessingRank(BlessingId.THORNED_ROADS) == 3) {
                    personal++;
                }
                if (BlessingEffects.isAuthorizedRaidParticipant(
                        fixture.settlement(), raider)) {
                    authorized++;
                }

                BlessingEvents.onRaiderTick(new EntityTickEvent.Post(raider));
                AttributeModifier modifier = raider.getAttribute(
                    Attributes.MOVEMENT_SPEED).getModifier(
                        BlessingEvents.THORNED_ROADS_MODIFIER);
                if (raider.cachedBlessingZoneRank() == 2
                    && modifier != null
                    && Math.abs(modifier.amount() + 0.16D) < 1.0E-9D) {
                    buildingZones++;
                }
            }

            long started = System.nanoTime();
            for (int pass = 0; pass < HOT_PASSES; pass++) {
                for (Fixture fixture : fixtures) {
                    SettlerEntity settler = fixture.settler();
                    RaiderEntity raider = fixture.raider();

                    DamageContainer dealt = new DamageContainer(
                        helper.getLevel().damageSources().mobAttack(settler),
                        10.0F);
                    BlessingEvents.onIncomingDamage(
                        new LivingIncomingDamageEvent(raider, dealt));
                    if (Math.abs(dealt.getNewDamage() - 12.0F) < 0.001F) {
                        outgoing++;
                    }
                    BlessingEvents.onDamageApplied(
                        new LivingDamageEvent.Post(raider, dealt));
                    BlessingEvents.onRaiderTick(
                        new EntityTickEvent.Post(raider));
                    AttributeModifier modifier = raider.getAttribute(
                        Attributes.MOVEMENT_SPEED).getModifier(
                            BlessingEvents.THORNED_ROADS_MODIFIER);
                    if (pass == 0) {
                        fixture.snareModifier = modifier;
                    } else if (modifier != null
                            && modifier == fixture.snareModifier) {
                        stableModifiers++;
                    }
                    if (raider.transientBlessingSnareRank(
                            helper.getLevel().getGameTime()) == 3
                        && modifier != null
                        && Math.abs(modifier.amount() + 0.24D) < 1.0E-9D) {
                        snared++;
                    }

                    DamageContainer received = new DamageContainer(
                        helper.getLevel().damageSources().mobAttack(raider),
                        10.0F);
                    BlessingEvents.onIncomingDamage(
                        new LivingIncomingDamageEvent(settler, received));
                    if (Math.abs(received.getNewDamage() - 8.0F) < 0.001F) {
                        incoming++;
                    }
                }
            }
            durationNanos = System.nanoTime() - started;

            observedLookups = sumLookups(settlements);
            observedRebuilds = sumRebuilds(settlements);
            observedCandidateChecks = sumLastCandidateChecks(settlements);
            records = sumPopulation(settlements);
            int groups = settlements.size();
            int expectedEffects = scale * HOT_PASSES;
            int expectedStableModifiers = scale * (HOT_PASSES - 1);
            long expectedLookups = (1L + HOT_PASSES * 2L) * scale;
            helper.assertTrue(fixtures.size() == scale
                    && groups == groupsFor(scale),
                "scale " + scale + " must create exact bounded raid groups");
            helper.assertTrue(liveEntities == scale * 2 && records == scale
                    && personal == scale && buildingZones == scale
                    && physicalZones == groups
                    && authorized == scale && unauthorizedRejected == 1,
                "scale " + scale + " must cover exact records and every "
                    + "physical personal/building/authority path");
            helper.assertTrue(outgoing == expectedEffects
                    && incoming == expectedEffects && snared == expectedEffects
                    && stableModifiers == expectedStableModifiers,
                "scale " + scale + " must produce every active effect without "
                    + "replacing an unchanged modifier after its first snare");
            helper.assertTrue(observedLookups == expectedLookups
                    && observedRebuilds == groups
                    && observedCandidateChecks == groups,
                "scale " + scale + " must keep indexed work exact and bounded");
            helper.assertTrue(durationNanos > 0L,
                "scale " + scale + " informational duration must be observable");

            for (Building building : buildings) {
                helper.assertTrue(building.valid
                        && building.blessingRank(BlessingId.WARDEN_OATH) == 2
                        && building.blessingRank(BlessingId.HEARTHWARD) == 2
                        && building.blessingRank(BlessingId.THORNED_ROADS) == 2,
                    "every active zone must retain its permanent rank-II ledger");
            }
        } finally {
            cleanup = cleanup(data, fixtures, unauthorized, settlements);
        }

        int groups = groupsFor(scale);
        int expectedEffects = scale * HOT_PASSES;
        long lookups = (1L + HOT_PASSES * 2L) * scale;
        helper.assertTrue(cleanup != null
                && cleanup.entitiesDiscarded() == scale * 2 + 1
                && cleanup.settlementsRemoved() == groups
                && cleanup.allEntitiesRemoved()
                && cleanup.allSettlementsRemoved(),
            "scale " + scale + " teardown must touch only its bounded fixtures");
        Hearthstead.LOGGER.info(
            "HSQA_ACTIVE_BLESSING_RESULT case={} scale={} passes={} groups={} "
                + "live_entities={} records={} personal={} building_zones={} "
                + "physical_zones={} buildings={} authorized={} "
                + "unauthorized_rejected={} outgoing={} incoming={} snared={} "
                + "stable_modifiers={} lookups={} rebuilds={} candidate_checks={} "
                + "entities_discarded={} settlements_removed={} duration_ns={}",
            caseId, scale, HOT_PASSES, groups, liveEntities, records, personal,
            buildingZones, physicalZones, groups, authorized,
            unauthorizedRejected, outgoing, incoming, snared, stableModifiers,
            observedLookups, observedRebuilds,
            observedCandidateChecks, cleanup.entitiesDiscarded(),
            cleanup.settlementsRemoved(), durationNanos);
        helper.assertTrue(outgoing == expectedEffects
                && incoming == expectedEffects && snared == expectedEffects,
            "machine marker may only follow complete effect coverage");
        helper.succeed();
    }

    private static int groupsFor(int scale) {
        return (scale + MAX_RAIDERS_PER_RUN - 1) / MAX_RAIDERS_PER_RUN;
    }

    private static long sumLookups(List<Settlement> settlements) {
        long sum = 0L;
        for (Settlement settlement : settlements) {
            sum += settlement.buildingBlessingIndexLookups();
        }
        return sum;
    }

    private static long sumRebuilds(List<Settlement> settlements) {
        long sum = 0L;
        for (Settlement settlement : settlements) {
            sum += settlement.buildingBlessingIndexRebuilds();
        }
        return sum;
    }

    private static int sumLastCandidateChecks(List<Settlement> settlements) {
        int sum = 0;
        for (Settlement settlement : settlements) {
            sum += settlement.lastBuildingBlessingCandidateChecks();
        }
        return sum;
    }

    private static int sumPopulation(List<Settlement> settlements) {
        int sum = 0;
        for (Settlement settlement : settlements) {
            sum += settlement.population();
        }
        return sum;
    }

    /**
     * Completes the shared physical-building fixture with the exact plaque
     * projection that a successful survey owns. The performance case is not
     * a room-scanner test, but its blessed zone must still be a legal,
     * identity-consistent LINKED_VALID plaque rather than a naked index row.
     */
    private static void linkPhysicalPlaque(GameTestHelper helper,
                                           Settlement settlement,
                                           Building building,
                                           BlockPos plaqueRel) {
        if (!(helper.getLevel().getBlockEntity(building.plaquePos)
                instanceof PlaqueBlockEntity plaque)) {
            throw new IllegalStateException(
                "physical Blessing fixture plaque has no block entity");
        }
        helper.assertTrue(plaque.insertPlan(helper.getLevel(),
                PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),
                    BuildingType.HOUSE)),
            "physical Blessing fixture must fit one authored HOUSE plan");
        try {
            setField(plaque, "buildingId", building.id);
            setField(plaque, "type", building.type);
            setField(plaque, "state", PlaqueState.LINKED_VALID);
            plaque.setChanged();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                "could not bind physical Blessing fixture plaque", error);
        }
        BlockState linkedState = helper.getLevel().getBlockState(
            building.plaquePos)
            .setValue(PlaqueBlock.GLOW, PlaqueBlock.Glow.GREEN)
            .setValue(PlaqueBlock.REGISTERED, true);
        helper.getLevel().setBlock(building.plaquePos, linkedState, 3);
        BlockState actual = helper.getLevel().getBlockState(building.plaquePos);
        long identityMatches = settlement.buildings.stream()
            .filter(candidate -> candidate == building
                && candidate.id.equals(building.id)
                && candidate.plaquePos.equals(building.plaquePos))
            .count();
        helper.assertTrue(actual.getBlock() instanceof PlaqueBlock
                && actual.canSurvive(helper.getLevel(), building.plaquePos)
                && actual.getValue(PlaqueBlock.REGISTERED)
                && actual.getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.GREEN
                && plaque.state() == PlaqueState.LINKED_VALID
                && plaque.type() == BuildingType.HOUSE
                && plaque.insertedPlan().getCount() == 1
                && PlaqueItemData.buildingType(plaque.insertedPlan())
                    == BuildingType.HOUSE
                && building.id.equals(plaque.buildingId())
                && building.plaquePos.equals(helper.absolutePos(plaqueRel))
                && building.plaquePos.equals(plaque.getBlockPos())
                && building.valid && identityMatches == 1L,
            "every active building zone must have one legal, linked, typed "
                + "physical plaque with exact saved identity");
    }

    private static void setField(PlaqueBlockEntity plaque, String name,
                                 Object value)
            throws ReflectiveOperationException {
        Field field = PlaqueBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plaque, value);
    }

    private static void apply(SettlerEntity settler, BlessingId blessing,
                              int ranks) {
        for (int rank = 0; rank < ranks; rank++) {
            if (settler.applyBlessing(blessing)
                    != TargetBlessingState.ApplyResult.APPLIED) {
                throw new IllegalStateException(
                    "could not apply permanent personal Blessing fixture");
            }
        }
    }

    private static void apply(Building building, BlessingId blessing,
                              int ranks) {
        for (int rank = 0; rank < ranks; rank++) {
            if (building.applyBlessing(blessing)
                    != TargetBlessingState.ApplyResult.APPLIED) {
                throw new IllegalStateException(
                    "could not apply permanent building Blessing fixture");
            }
        }
    }

    private static Cleanup cleanup(SettlementSavedData data,
                                   List<Fixture> fixtures,
                                   RaiderEntity unauthorized,
                                   List<Settlement> settlements) {
        int entitiesDiscarded = 0;
        for (Fixture fixture : fixtures) {
            fixture.settler().discard();
            entitiesDiscarded++;
            fixture.raider().discard();
            entitiesDiscarded++;
        }
        if (unauthorized != null) {
            unauthorized.discard();
            entitiesDiscarded++;
        }
        boolean allEntitiesRemoved = true;
        for (Fixture fixture : fixtures) {
            allEntitiesRemoved &= fixture.settler().isRemoved()
                && fixture.raider().isRemoved();
        }
        allEntitiesRemoved &= unauthorized == null || unauthorized.isRemoved();

        int settlementsRemoved = 0;
        for (Settlement settlement : settlements) {
            if (data.settlements.remove(settlement.id) != null) {
                settlementsRemoved++;
            }
        }
        boolean allSettlementsRemoved = true;
        for (Settlement settlement : settlements) {
            allSettlementsRemoved &= !data.settlements.containsKey(settlement.id);
        }
        data.setDirty();
        return new Cleanup(entitiesDiscarded, settlementsRemoved,
            allEntitiesRemoved, allSettlementsRemoved);
    }

    private static final class Fixture {
        private final Settlement settlement;
        private final SettlerEntity settler;
        private final RaiderEntity raider;
        private AttributeModifier snareModifier;

        private Fixture(Settlement settlement, SettlerEntity settler,
                        RaiderEntity raider) {
            this.settlement = settlement;
            this.settler = settler;
            this.raider = raider;
        }

        Settlement settlement() {
            return settlement;
        }

        SettlerEntity settler() {
            return settler;
        }

        RaiderEntity raider() {
            return raider;
        }
    }

    private record Cleanup(int entitiesDiscarded, int settlementsRemoved,
                           boolean allEntitiesRemoved,
                           boolean allSettlementsRemoved) {
    }

}
