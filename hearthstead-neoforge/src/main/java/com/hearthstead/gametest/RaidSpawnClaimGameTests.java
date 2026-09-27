package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidHoldNotice;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidTelegraph;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Raiders form up OUTSIDE the settlement claim (owner bug 25 Sep: the old
 * fixed 26-38 blocks from the centre sat inside the 48-block claim), and a
 * held first raid tells players which blocker holds it instead of stalling
 * silently.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidSpawnClaimGameTests {

    /** High above the flat world so only the floors this test lays exist. */
    private static final BlockPos SKY_CENTER = new BlockPos(8, 100, 8);
    private static final int CLAIM = 24;

    private static Settlement skySettlement(GameTestHelper helper, String name) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(SKY_CENTER));
        settlement.radius = CLAIM;
        for (int i = 0; i < 6; i++) {
            settlement.putRecord(UUID.randomUUID(), name + " " + i, Profession.NONE);
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void forget(GameTestHelper helper, Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlement.id);
        data.setDirty();
    }

    /**
     * Production never loads a chunk for footing; the fixture does, so the
     * whole search area is inspectable. Returns what it forced, to release.
     */
    private static List<ChunkPos> forceArea(ServerLevel level, BlockPos center,
                                            int reach) {
        List<ChunkPos> forced = new ArrayList<>();
        int minX = SectionPos.blockToSectionCoord(center.getX() - reach);
        int maxX = SectionPos.blockToSectionCoord(center.getX() + reach);
        int minZ = SectionPos.blockToSectionCoord(center.getZ() - reach);
        int maxZ = SectionPos.blockToSectionCoord(center.getZ() + reach);
        Set<Long> already = new LinkedHashSet<>();
        for (long packed : level.getForcedChunks()) {
            already.add(packed);
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!already.contains(pos.toLong())) {
                    level.setChunkForced(x, z, true);
                    forced.add(pos);
                }
            }
        }
        return forced;
    }

    private static void release(ServerLevel level, List<ChunkPos> forced) {
        for (ChunkPos pos : forced) {
            level.setChunkForced(pos.x, pos.z, false);
        }
    }

    /**
     * Lays a one-block floor under every column a band of ANY size could
     * pick on this approach, from {@code from} to {@code to} blocks out.
     */
    private static Set<BlockPos> layBandRays(ServerLevel level, Settlement settlement,
                                             float approach, int from, int to) {
        Set<BlockPos> floors = new LinkedHashSet<>();
        for (int band = RaidDirector.MIN_BAND; band <= RaidDirector.MAX_BAND; band++) {
            for (int slot = 0; slot < band; slot++) {
                float spread = (slot / (float) (band - 1) - 0.5F)
                    * 2.0F * RaidDirector.SPAWN_ARC;
                layRay(level, settlement, approach + spread, from, to, floors);
            }
        }
        return floors;
    }

    private static void layRay(ServerLevel level, Settlement settlement,
                               float bearing, int from, int to,
                               Set<BlockPos> floors) {
        for (int d = from; d <= to; d++) {
            BlockPos floor = RaidDirector.formUpAt(settlement.center, bearing, d)
                .below();
            if (floors.add(floor)) {
                level.setBlock(floor, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
    }

    private static void clearFloors(ServerLevel level, Set<BlockPos> floors) {
        for (BlockPos floor : floors) {
            level.setBlock(floor, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static List<RaiderEntity> raidersInsideClaim(ServerLevel level,
                                                         Settlement settlement) {
        AABB claim = new AABB(settlement.center).inflate(settlement.radius);
        return level.getEntitiesOfClass(RaiderEntity.class, claim,
            raider -> settlement.inside(raider.blockPosition())
                || !RaidDirector.outsideClaim(settlement, raider.blockPosition()));
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "raid_spawn_band_forms_up_outside_the_claim")
    public void noRaiderSpawnsInsideTheClaim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The default claim, as a pure check of the geometry that shipped wrong.
        helper.assertTrue(RaidDirector.spawnMinDistance(Settlement.DEFAULT_RADIUS)
                    == Settlement.DEFAULT_RADIUS + RaidDirector.SPAWN_EDGE_MARGIN_MIN
                && RaidDirector.spawnMaxDistance(Settlement.DEFAULT_RADIUS)
                    == Settlement.DEFAULT_RADIUS + RaidDirector.SPAWN_EDGE_MARGIN_MAX
                && RaidDirector.spawnMinDistance(Settlement.DEFAULT_RADIUS)
                    > Settlement.DEFAULT_RADIUS,
            "the band must form up past the claim edge plus a margin");

        Settlement settlement = skySettlement(helper, "Randvik");
        int reach = RaidDirector.spawnMaxDistance(CLAIM)
            + RaidDirector.CAPTAIN_EXTRA_REACH + 2;
        List<ChunkPos> forced = forceArea(level, settlement.center, reach);
        List<RaiderEntity> all = new ArrayList<>();
        try {
            for (float approach : new float[] {0.0F, 90.0F, 180.0F, 270.0F}) {
                // Footing everywhere along the band's rays, INSIDE the claim
                // too: only the director's own choice keeps raiders out.
                Set<BlockPos> floors = layBandRays(level, settlement, approach, 0,
                    RaidDirector.spawnMaxDistance(CLAIM));
                try {
                    RaidCaptain captain = RaidDirector.pickCaptain(settlement,
                        level.getRandom());
                    RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD,
                        approach, 40L);
                    List<RaiderEntity> band = RaidDirector.spawnBand(level,
                        settlement, plan);
                    helper.assertTrue(band.size() >= RaidDirector.MIN_BAND,
                        "with footing on every ray the band must arrive, got "
                            + band.size() + " at approach " + approach);
                    for (RaiderEntity raider : band) {
                        BlockPos at = raider.blockPosition();
                        long dx = at.getX() - settlement.center.getX();
                        long dz = at.getZ() - settlement.center.getZ();
                        double horizontal = Math.sqrt(dx * dx + dz * dz);
                        helper.assertTrue(!settlement.inside(at)
                                && RaidDirector.outsideClaim(settlement, at)
                                && horizontal >= RaidDirector.spawnMinDistance(CLAIM) - 1.0D
                                && horizontal <= RaidDirector.spawnMaxDistance(CLAIM) + 1.0D,
                            "raider formed up at " + at + ", " + horizontal
                                + " blocks from the centre of a " + CLAIM
                                + "-block claim (captain=" + raider.isCaptain() + ")");
                    }
                    all.addAll(band);
                } finally {
                    for (RaiderEntity raider : all) {
                        raider.discard();
                    }
                    clearFloors(level, floors);
                }
            }

            // Footing ONLY inside the claim: nobody may form up at all -- the
            // old captain fallback onto the settlement's own ground is gone --
            // and the scout omen does not fall back inside either.
            Set<BlockPos> inner = new LinkedHashSet<>();
            for (float bearing = 0.0F; bearing < 360.0F; bearing += 15.0F) {
                layRay(level, settlement, bearing, 0, CLAIM - 1, inner);
            }
            try {
                RaidCaptain captain = RaidDirector.pickCaptain(settlement,
                    level.getRandom());
                RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.KORN,
                    45.0F, 41L);
                List<RaiderEntity> band = RaidDirector.spawnBand(level, settlement, plan);
                RaiderEntity scout = RaidTelegraph.spawnScout(level, settlement);
                if (scout != null) {
                    all.add(scout);
                }
                all.addAll(band);
                helper.assertTrue(band.isEmpty(),
                    "with footing only inside the claim no band may form, got "
                        + band.size());
                helper.assertTrue(scout == null
                        || RaidDirector.outsideClaim(settlement, scout.blockPosition()),
                    "a scout must never stand inside the claim, got "
                        + (scout == null ? null : scout.blockPosition()));
                helper.assertTrue(raidersInsideClaim(level, settlement).isEmpty(),
                    "no raider of any kind may exist inside the claim");
            } finally {
                clearFloors(level, inner);
            }
        } finally {
            for (RaiderEntity raider : all) {
                if (!raider.isRemoved()) {
                    raider.discard();
                }
            }
            release(level, forced);
            forget(helper, settlement);
        }
        helper.succeed();
    }

    /**
     * The silent stall: a scheduled first raid whose warning (or warned
     * attack) is due but held by readiness must say which blocker holds it,
     * throttled, while the tested gates keep the plan exactly as it was.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "raid_hold_notice_names_the_readiness_blocker")
    public void heldFirstRaidNamesItsBlockerAndKeepsThePlan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Ventevik",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        try {
            helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(10L, 4, 2)
                    && settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED
                    && settlement.raidLifecycle.firstWarningNight() == 12L
                    && settlement.raidLifecycle.firstAttackNight() == 14L,
                "fixture: a scheduled first raid warning on night 12, attack 14");

            helper.assertTrue(!RaidDirector.announceFirstRaidHold(level, settlement,
                    11L, RaidHoldNotice.Reason.FIRST_WARNING_READINESS)
                    && RaidHoldNotice.lastNotice(level, settlement).isEmpty(),
                "nothing is held before the warning night");

            helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(level, settlement, 12L),
                "fixture: this settlement has no Banner/Journey, so readiness fails");
            helper.assertTrue(RaidDirector.announceFirstRaidHold(level, settlement,
                    12L, RaidHoldNotice.Reason.FIRST_WARNING_READINESS),
                "a held warning must be announced");
            RaidHoldNotice.Last last = RaidHoldNotice.lastNotice(level, settlement)
                .orElse(null);
            helper.assertTrue(last != null
                    && last.reason() == RaidHoldNotice.Reason.FIRST_WARNING_READINESS
                    && last.night() == 12L,
                "the notice must record the readiness hold, got " + last);
            helper.assertTrue(!RaidDirector.announceFirstRaidHold(level, settlement,
                    12L, RaidHoldNotice.Reason.FIRST_WARNING_READINESS),
                "the once-a-second gate must not repeat the notice");
            helper.assertTrue(settlement.raidLifecycle.queuedPlan().isEmpty()
                    && settlement.raidLifecycle.firstWarningNight() == 12L
                    && settlement.raidLifecycle.firstAttackNight() == 14L,
                "announcing must never queue, reroll or move the calendar");

            // A warned plan whose attack night is held keeps that exact plan.
            RaidCaptain captain = RaidDirector.pickCaptain(settlement, level.getRandom());
            RaidPlan warned = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 14L);
            helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(warned),
                "fixture: a warned first plan");
            helper.assertTrue(RaidDirector.startQueuedFirstRaid(level, settlement, 14L)
                    .isEmpty(),
                "the unchanged start gate refuses an unready settlement");
            helper.assertTrue(RaidDirector.announceFirstRaidHold(level, settlement,
                    14L, RaidHoldNotice.Reason.FIRST_ATTACK_READINESS),
                "a held warned attack must be announced");
            last = RaidHoldNotice.lastNotice(level, settlement).orElse(null);
            helper.assertTrue(last != null
                    && last.reason() == RaidHoldNotice.Reason.FIRST_ATTACK_READINESS
                    && last.night() == 14L,
                "the attack-stage notice must be recorded, got " + last);
            helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED
                    && settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned)
                    && settlement.raidLifecycle.participants().isEmpty()
                    && settlement.pendingRaid == null,
                "the held plan stays queued, exactly as warned");
        } finally {
            RaidHoldNotice.clear(level, settlement);
            data.settlements.remove(settlement.id);
            data.setDirty();
        }
        helper.succeed();
    }
}
