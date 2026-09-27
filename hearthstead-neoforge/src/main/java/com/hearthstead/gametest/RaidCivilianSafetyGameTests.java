package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RaiderSettlerTargetGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Civilian safety in a raid (26 Sep, after captain2: 3 of 34 settlers died,
 * 6 in an earlier soak raid). Mirrors the soak geometry that killed them:
 * civilians at work on one side of the village, their claimed homes on the
 * far side, and the band arriving from that far side. A staffed guard post
 * (two Guards on normal duty) defends. Target: at most one civilian death.
 * Every second the civilians' activity and distance to the nearest raider are
 * logged as {@code HSQA_CIVILIAN} lines; the result is one {@code HSQA_TTK}
 * line. Batches start with {@code raid_civilians_}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidCivilianSafetyGameTests {
    private static final BlockPos SKY_CENTER = new BlockPos(8, 110, 8);
    private static final int CLAIM = 16;
    private static final int CIVILIANS = 8;
    private static final long OBSERVE_TICKS = 20L * 90L;

    private static List<ChunkPos> forceArea(ServerLevel level, BlockPos center, int reach) {
        List<ChunkPos> forced = new ArrayList<>();
        Set<Long> already = new LinkedHashSet<>();
        for (long packed : level.getForcedChunks()) already.add(packed);
        for (int x = SectionPos.blockToSectionCoord(center.getX() - reach);
             x <= SectionPos.blockToSectionCoord(center.getX() + reach); x++) {
            for (int z = SectionPos.blockToSectionCoord(center.getZ() - reach);
                 z <= SectionPos.blockToSectionCoord(center.getZ() + reach); z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!already.contains(pos.toLong())) {
                    level.setChunkForced(x, z, true);
                    forced.add(pos);
                }
            }
        }
        return forced;
    }

    /** A walled 7x7 room (door gap facing the centre) registered as a building. */
    private static Building room(ServerLevel level, Settlement settlement, BuildingType type,
                                 BlockPos corner, List<BlockPos> laid) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                boolean door = x == 3 && (z == 0 || z == 6);
                for (int y = 0; y < 3; y++) {
                    BlockPos pos = corner.offset(x, y, z);
                    level.setBlock(pos, wall && !door ? Blocks.OAK_PLANKS.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), 2);
                    laid.add(pos);
                }
                BlockPos roof = corner.offset(x, 3, z);
                level.setBlock(roof, Blocks.OAK_PLANKS.defaultBlockState(), 2);
                laid.add(roof);
            }
        }
        Building building = new Building(UUID.randomUUID(), type, corner.offset(3, 1, 0),
            corner.offset(3, 0, 3), BoundingBox.fromCorners(corner.offset(0, -1, 0), corner.offset(6, 3, 6)));
        building.valid = true;
        settlement.buildings.add(building);
        return building;
    }

    private static BlockPos bed(ServerLevel level, Building home, int index, List<BlockPos> laid) {
        BlockPos foot = home.anchor.offset(-2 + index, 0, -1);
        BlockPos head = foot.south();
        var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), 2);
        laid.add(foot);
        laid.add(head);
        home.beds.add(head);
        return head;
    }

    private static SettlerEntity settler(ServerLevel level, Settlement settlement, BlockPos at,
                                         String name, Profession profession) {
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        settler.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(settler);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        if (profession != Profession.NONE) {
            settler.assignProfession(profession);
        }
        return settler;
    }

    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "raid_civilians_survive_far_side_raid")
    public void civiliansDoNotRunIntoARaidOnTheirHomeSide(GameTestHelper helper) {
        scenario(helper, true);
    }

    /**
     * The same scenario with the safety pass switched off: the measured
     * "before" (not asserted, logged as fight=civilian_safety_before).
     * Batches run one after another, so the switch cannot leak into the
     * asserted run; it is restored when this one ends.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "raid_civilians_before_safety_pass")
    public void sameRaidWithoutTheSafetyPass(GameTestHelper helper) {
        com.hearthstead.entity.ai.CivilianSafety.enabled = false;
        scenario(helper, false);
    }

    private static void scenario(GameTestHelper helper, boolean assertSafety) {
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Tryggvik",
            helper.absolutePos(SKY_CENTER));
        settlement.radius = CLAIM;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        int reach = RaidDirector.spawnMaxDistance(CLAIM) + RaidDirector.CAPTAIN_EXTRA_REACH + 2;
        List<ChunkPos> forced = forceArea(level, settlement.center, reach);
        List<BlockPos> laid = new ArrayList<>();
        BlockPos c = settlement.center;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > reach * reach) continue;
                BlockPos pos = c.offset(dx, -1, dz);
                level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                laid.add(pos);
            }
        }
        // Homes on the raid side (south, +Z), at the claim edge; a warehouse
        // and the workplaces on the north side.
        Building homeWest = room(level, settlement, BuildingType.HOUSE, c.offset(-9, 0, 11), laid);
        Building homeEast = room(level, settlement, BuildingType.HOUSE, c.offset(2, 0, 11), laid);
        room(level, settlement, BuildingType.WAREHOUSE, c.offset(-3, 0, -17), laid);
        List<SettlerEntity> civilians = new ArrayList<>();
        for (int i = 0; i < CIVILIANS; i++) {
            SettlerEntity civilian = settler(level, settlement, c.offset(-7 + i * 2, 0, -8),
                "Borger " + i, Profession.NONE);
            Building home = i % 2 == 0 ? homeWest : homeEast;
            civilian.claimBed(bed(level, home, i / 2, laid));
            civilians.add(civilian);
        }
        List<SettlerEntity> guards = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            SettlerEntity guard = settler(level, settlement, c.offset(-2 + i * 4, 0, 2),
                "Vakt " + i, Profession.GUARD);
            guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            guard.attributes().pinForTest(Attribute.STRENGTH, 0);
            guards.add(guard);
        }
        RaidCaptain captain = RaidDirector.pickCaptain(settlement, level.getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2)
                && settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "fixture: an authored first raid is active");
        List<RaiderEntity> band = RaidDirector.spawnFirstBandForQa(level, settlement, plan);
        helper.assertTrue(band.size() == RaidDirector.FIRST_RAID_BAND_SIZE,
            "the authored five-raider band must form up south, got " + band.size());
        for (RaiderEntity raider : band) {
            helper.assertTrue(settlement.raidLifecycle.recordParticipant(raider.getUUID()),
                "band member enters the ledger");
        }
        helper.assertTrue(settlement.raidLifecycle.sealParticipants(), "the band seals");
        settlement.pendingRaid = plan;

        long[] start = {-1L};
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            long now = helper.getTick();
            if (start[0] < 0L) start[0] = now;
            long elapsed = now - start[0];
            if (elapsed % 20L == 0L) {
                for (SettlerEntity civilian : civilians) {
                    if (!civilian.isAlive()) continue;
                    double nearest = band.stream().filter(LivingEntity::isAlive)
                        .mapToDouble(r -> Math.sqrt(r.distanceToSqr(civilian))).min().orElse(-1);
                    Hearthstead.LOGGER.info(
                        "HSQA_CIVILIAN" + (assertSafety ? "" : "_BEFORE") + " t={}s name={} hp={} activity={} dz={} nearestRaider={} note={}",
                        elapsed / 20.0, civilian.getSettlerName(), civilian.getHealth(),
                        civilian.getActivity(),
                        String.format(java.util.Locale.ROOT, "%.1f", civilian.getZ() - c.getZ()),
                        String.format(java.util.Locale.ROOT, "%.1f", nearest),
                        civilian.routeFailureNote());
                }
            }
            boolean bandDown = band.stream().noneMatch(LivingEntity::isAlive);
            if (!bandDown && elapsed < OBSERVE_TICKS) return;
            done[0] = true;
            long dead = civilians.stream().filter(s -> !s.isAlive()).count();
            long guardsDead = guards.stream().filter(s -> !s.isAlive()).count();
            long raidersLeft = band.stream().filter(LivingEntity::isAlive).count();
            Hearthstead.LOGGER.info(
                "HSQA_TTK fight=civilian_safety{} seconds={} civilians_dead={}/{} guards_dead={}/{} raiders_left={}/{}",
                assertSafety ? "" : "_before", elapsed / 20.0, dead, civilians.size(), guardsDead,
                guards.size(), raidersLeft, band.size());
            try {
                if (assertSafety) {
                    helper.assertTrue(dead <= 1, "at most one civilian may die in a raid with a staffed "
                        + "guard post, died " + dead + " of " + civilians.size());
                }
            } finally {
                com.hearthstead.entity.ai.CivilianSafety.enabled = true;
                for (RaiderEntity raider : band) if (!raider.isRemoved()) raider.discard();
                for (SettlerEntity s : civilians) if (!s.isRemoved()) s.discard();
                for (SettlerEntity s : guards) if (!s.isRemoved()) s.discard();
                for (BlockPos pos : laid) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                for (ChunkPos pos : forced) level.setChunkForced(pos.x, pos.z, false);
                data.settlements.remove(settlement.id);
                data.setDirty();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "raid_civilians_target_ranking")
    public void grainRaidersLetFleeingCiviliansGo(GameTestHelper helper) {
        // Pure ranking contract of RaiderSettlerTargetGoal.
        double fleeingAt4Blood = RaiderSettlerTargetGoal.effectiveDistance(4, false, true, RaidObjective.BLOD);
        double guardAt8Blood = RaiderSettlerTargetGoal.effectiveDistance(8, true, false, RaidObjective.BLOD);
        double fleeingAt4Grain = RaiderSettlerTargetGoal.effectiveDistance(4, false, true, RaidObjective.KORN);
        double guardAt8Grain = RaiderSettlerTargetGoal.effectiveDistance(8, true, false, RaidObjective.KORN);
        helper.assertTrue(fleeingAt4Blood == 4 && guardAt8Blood == 8
                && guardAt8Grain < fleeingAt4Grain
                && SettlerActivity.FLEEING != SettlerActivity.COMBAT,
            "Blood raids stay nearest-first; Grain raids let a fleeing civilian go for the Guard");
        helper.succeed();
    }

    /** Flat stone floor, air above, in absolute coordinates; returns what was laid. */
    private static List<BlockPos> flatFloor(ServerLevel level, BlockPos centre, int halfX, int halfZ) {
        List<BlockPos> laid = new ArrayList<>();
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                BlockPos pos = centre.offset(dx, -1, dz);
                level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                laid.add(pos);
            }
        }
        return laid;
    }

    /**
     * Codex review P2 regression: a civilian already running a safe route
     * home must not keep following it once a raider stands in the corridor,
     * even though every replacement query is refused (the only home lies
     * behind the raider, so every path to it fails the raid clearance).
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raid_civilians_stop_on_blocked_corridor")
    public void civilianStopsWhenARaiderStepsIntoItsRoute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(8, 130, 8));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Korridor", centre);
        settlement.radius = 30;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        List<ChunkPos> forced = forceArea(level, centre, 40);
        List<BlockPos> laid = flatFloor(level, centre, 30, 6);
        Building home = room(level, settlement, BuildingType.HOUSE, centre.offset(20, 0, -3), laid);
        SettlerEntity civilian = settler(level, settlement, centre.offset(-20, 0, 0), "Loper", Profession.NONE);
        civilian.claimBed(bed(level, home, 0, laid));
        RaidCaptain captain = RaidDirector.pickCaptain(settlement, level.getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 90.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2)
                && settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "fixture: an authored first raid is active");
        // The raid is on, but the band stands far behind the civilian: the
        // route east to its home is safe and it starts running.
        RaiderEntity raider = ModEntities.RAIDER.get().create(level);
        BlockPos parked = centre.offset(-29, 0, 5);
        raider.moveTo(parked.getX() + 0.5, parked.getY(), parked.getZ() + 0.5, 0, 0);
        raider.assign(plan.captainId(), settlement.id, plan.objective(), 1.0F, false);
        raider.setNoAi(true);
        level.addFreshEntity(raider);
        helper.assertTrue(settlement.raidLifecycle.recordParticipant(raider.getUUID())
            && settlement.raidLifecycle.sealParticipants(), "fixture: sealed one-raider band");
        settlement.pendingRaid = plan;
        double[] closest = {Double.MAX_VALUE};
        boolean[] moved = {false};
        long[] placedAt = {-1L};
        helper.onEachTick(() -> {
            long now = helper.getTick();
            if (placedAt[0] < 0L && civilian.getX() > centre.getX() - 12) {
                // The civilian is under way east: step the raider into the corridor ahead of it.
                BlockPos ahead = centre.offset(4, 0, 0);
                raider.teleportTo(ahead.getX() + 0.5, ahead.getY(), ahead.getZ() + 0.5);
                placedAt[0] = now;
                moved[0] = true;
            }
            if (placedAt[0] >= 0L) {
                closest[0] = Math.min(closest[0], Math.sqrt(civilian.distanceToSqr(raider)));
            }
            if (placedAt[0] >= 0L && now >= placedAt[0] + 160L) {
                Hearthstead.LOGGER.info("HSQA_CIVILIAN corridor closest={} note={} x={}",
                    String.format(java.util.Locale.ROOT, "%.1f", closest[0]),
                    civilian.routeFailureNote(), civilian.getX() - centre.getX());
                try {
                    helper.assertTrue(moved[0], "the civilian must have started its route home");
                    helper.assertTrue(closest[0] >= 5.0D,
                        "the civilian kept following its old route into the raider: closest "
                            + String.format(java.util.Locale.ROOT, "%.1f", closest[0]) + " blocks, note="
                            + civilian.routeFailureNote());
                } finally {
                    raider.discard();
                    civilian.discard();
                    for (BlockPos pos : laid) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    for (ChunkPos pos : forced) level.setChunkForced(pos.x, pos.z, false);
                    data.settlements.remove(settlement.id);
                    data.setDirty();
                }
                helper.succeed();
            }
        });
    }

    /**
     * Codex review P2 regression: a Guard already fighting a zombie that
     * re-targets to a raid raider on re-evaluation must raise the ALARM, once.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_civilians_alarm_on_retarget")
    public void guardSwitchingFromZombieToRaiderRaisesTheAlarmOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Vaktskifte",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        SettlerEntity guard = settler(level, settlement, helper.absolutePos(new BlockPos(2, 1, 8)),
            "Skifte", Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.setNoAi(true);
        var zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(10, 1, 8));
        zombie.setNoAi(true);
        var goal = new com.hearthstead.entity.ai.SettlerDefenseTargetGoal(guard);
        RaiderEntity[] brute = {null};
        int[] phase = {0};
        long[] alarmUntil = {-1L};
        int[] reevaluations = {0};
        // Real server ticks: the RaidThreatBoard refreshes on a 10-tick cadence
        // and the target goal's own acquisition is randomly throttled.
        helper.onEachTick(() -> {
            if (phase[0] == 0) {
                if (goal.canUse()) {
                    goal.start();
                    helper.assertTrue(guard.getTarget() == zombie
                            && !settlement.alertActive(level.getGameTime()),
                        "a zombie is ordinary defence: no raid ALARM, target " + guard.getTarget());
                    RaiderEntity spawned = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 8));
                    spawned.setVariant(RaiderEntity.Variant.BRUTE);
                    spawned.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
                    spawned.setNoAi(true);
                    brute[0] = spawned;
                    phase[0] = 1;
                }
                return;
            }
            helper.assertTrue(goal.canContinueToUse(), "the Guard keeps a target while enemies stand");
            reevaluations[0]++;
            if (guard.getTarget() == brute[0] && alarmUntil[0] < 0L) {
                helper.assertTrue(settlement.alertActive(level.getGameTime()),
                    "switching to a raid raider must raise the ALARM at once");
                alarmUntil[0] = settlement.alertUntilGameTime;
            }
            if (reevaluations[0] < 60) {
                return;
            }
            try {
                helper.assertTrue(guard.getTarget() == brute[0],
                    "the nearer Brute must win the re-evaluation, got " + guard.getTarget());
                helper.assertTrue(settlement.alertUntilGameTime == alarmUntil[0],
                    "the ALARM is raised once, not re-extended every re-evaluation");
            } finally {
                brute[0].discard();
                zombie.discard();
                guard.discard();
                data.settlements.remove(settlement.id);
                data.setDirty();
            }
            helper.succeed();
        });
    }

    /**
     * Regression for the alarm_bell / CivilianAlarm flake (captain P5F and C2,
     * 26 Sep 17:31-17:37). A bell ALARM makes a civilian avoid the nearest
     * monster within 32 blocks -- in a busy GameTest run usually a
     * NEIGHBOURING test's zombie. The v3 shelter filter treated that point as
     * a raid and closed the civilian's only home when the monster stood nearer
     * to it, leaving panic_home_unavailable. Outside a raid a single monster
     * must not close a home; only the live raid band does.
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "raid_civilians_alarm_home_not_closed_by_stray_monster")
    public void aStrayMonsterOutsideARaidDoesNotCloseTheHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(8, 140, 8));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Streifvik", centre);
        settlement.radius = 30;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        List<ChunkPos> forced = forceArea(level, centre, 30);
        List<BlockPos> laid = flatFloor(level, centre, 24, 6);
        Building home = room(level, settlement, BuildingType.HOUSE, centre.offset(8, 0, -3), laid);
        SettlerEntity civilian = settler(level, settlement, centre.offset(-12, 0, 0), "Hjemme", Profession.NONE);
        BlockPos bedHead = bed(level, home, 0, laid);
        civilian.claimBed(bedHead);
        // A monster beyond the home, far from the civilian: 5 blocks from the
        // home's centre, about 26 from the civilian -- inside the ALARM's
        // 32-block threat scan, never in a raid of this settlement.
        var stray = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        BlockPos strayAt = centre.offset(16, 0, 0);
        stray.moveTo(strayAt.getX() + 0.5, strayAt.getY(), strayAt.getZ() + 0.5, 0, 0);
        stray.setNoAi(true);
        level.addFreshEntity(stray);
        // Bell-style ALARM with no raid.
        settlement.alertUntilGameTime = level.getGameTime() + 2400L;
        settlement.alertPos = centre;
        double[] closest = {Double.MAX_VALUE};
        long[] started = {-1L};
        helper.onEachTick(() -> {
            long now = level.getGameTime();
            if (started[0] < 0L) started[0] = now;
            closest[0] = Math.min(closest[0], civilian.blockPosition().distSqr(bedHead));
            if ((now - started[0]) % 20L == 0L || now - started[0] < 12L) {
                var early = civilian.getNavigation().createPath(bedHead.east(), 0);
                Hearthstead.LOGGER.info("HSQA_CIVILIAN stray_monster t={} onGround={} path={} pos={} bedSqr={} activity={} note={} running={}",
                    now - started[0], civilian.onGround(),
                    early == null ? "null" : "reach=" + early.canReach(),
                    civilian.blockPosition().subtract(centre).toShortString(),
                    civilian.blockPosition().distSqr(bedHead), civilian.getActivity(),
                    civilian.routeFailureNote(), civilian.goalSelector.getAvailableGoals().stream()
                        .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                        .map(g -> g.getGoal().getClass().getSimpleName()).toList());
            }
            // Real server ticks, not the helper's test clock: the home trip is
            // up to two 240-tick panic legs.
            if (now - started[0] < 300L) {
                return;
            }
            var probe = civilian.getNavigation().createPath(bedHead.east(), 0);
            Hearthstead.LOGGER.info("HSQA_CIVILIAN stray_monster closestSqr={} note={} directPath={}",
                closest[0], civilian.routeFailureNote(),
                probe == null ? "null" : "reach=" + probe.canReach() + ",nodes=" + probe.getNodeCount());
            try {
                helper.assertTrue(closest[0] <= 9.0D,
                    "a stray monster outside a raid must not close the civilian's home: closest "
                        + closest[0] + " (sqr) note=" + civilian.routeFailureNote());
            } finally {
                stray.discard();
                civilian.discard();
                for (BlockPos pos : laid) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                for (ChunkPos pos : forced) level.setChunkForced(pos.x, pos.z, false);
                data.settlements.remove(settlement.id);
                data.setDirty();
            }
            helper.succeed();
        });
    }
}
