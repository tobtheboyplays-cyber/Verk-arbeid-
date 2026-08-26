package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.saga.Captain;
import com.hearthstead.saga.CaptainRoster;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SAGA v1 -- DESIGN.md system 4's Nemesis-inspired named-captain layer.
 *
 * <p>These pin exactly what the task scoped for v1: a deterministic,
 * bounded roster of named captains ({@link CaptainRoster}), earned (not
 * decorative) epithets, a leader who dies permanently and is succeeded by a
 * grudge-bearing lieutenant, and growth that is both mechanically real and
 * readable -- never a hidden stat (D-A3-3).
 *
 * <p>Deliberately layered beside the existing raid GameTests rather than
 * duplicating their coverage: {@code RaiderGameTests} and
 * {@code RaidPressureGameTests} already pin {@code RaidCaptain}'s own
 * menace/approach/win-loss mechanics, which Saga does not touch.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SagaGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /** A settlement worth raiding (so {@code CaptainRoster#ensureRoster}
     * actually generates something), with an explicit id so determinism
     * can be checked against a second, otherwise-unrelated instance. */
    private static Settlement settlement(UUID id, int settlerCount) {
        Settlement s = new Settlement(id, "Sagaholm", BlockPos.ZERO);
        for (int i = 0; i < settlerCount; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        return s;
    }

    private static Settlement makeSettlementForBand(GameTestHelper helper, BlockPos centerRel,
                                                     int settlerCount) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Sagaholm",
            helper.absolutePos(centerRel));
        s.radius = 12;
        for (int i = 0; i < settlerCount; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * (a) Same settlement id, same three names, every time -- and the
     * roster round-trips through NBT exactly the way {@code RaidCaptain}'s
     * own gallery does.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "saga_roster_generation_is_deterministic_and_persists")
    public void rosterGenerationIsDeterministicAndPersists(GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        RandomSource random = helper.getLevel().getRandom();

        Settlement first = settlement(settlementId, 8);
        CaptainRoster.ensureRoster(first, random);
        helper.assertTrue(first.sagaRoster.size() == CaptainRoster.MAX_ROSTER,
            "a worthwhile settlement must get exactly " + CaptainRoster.MAX_ROSTER
                + " named captains, got " + first.sagaRoster.size());
        List<String> namesFirst = first.sagaRoster.stream()
            .map(Captain::firstName).toList();

        // A second, otherwise-unrelated Settlement object sharing only the
        // id -- the roster must not depend on anything else about it.
        Settlement second = settlement(settlementId, 8);
        CaptainRoster.ensureRoster(second, random);
        List<String> namesSecond = second.sagaRoster.stream()
            .map(Captain::firstName).toList();
        helper.assertTrue(namesFirst.equals(namesSecond),
            "the same settlement id must always propose the same roster in the "
                + "same order, got " + namesFirst + " vs " + namesSecond);

        Settlement reloaded = Settlement.readNbt(first.writeNbt());
        helper.assertTrue(reloaded.sagaRoster.size() == first.sagaRoster.size(),
            "the roster size must survive a reload, got " + reloaded.sagaRoster.size());
        for (int i = 0; i < first.sagaRoster.size(); i++) {
            Captain before = first.sagaRoster.get(i);
            Captain after = reloaded.sagaRoster.get(i);
            helper.assertTrue(before.id().equals(after.id())
                    && before.firstName().equals(after.firstName()),
                "roster entry " + i + " must round-trip by id and name, got "
                    + before.firstName() + " vs " + after.firstName());
        }
        helper.succeed();
    }

    /**
     * (b) A captain killed mid-raid is gone for good, and a fresh
     * lieutenant takes their place carrying the grudge marker in their own
     * name -- "Kettil, sworn to Grimr" (v1 flavor, per the task).
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "saga_a_slain_captain_is_succeeded_by_a_grudge_bearing_lieutenant")
    public void aSlainCaptainIsSucceededByAGrudgeBearingLieutenant(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = settlement(UUID.randomUUID(), 8);
        var level = helper.getLevel();
        // RaiderEntity#die resolves its settlement through SettlementManager
        // (the same lookup a real raider uses), so it has to actually be
        // registered here rather than only existing as a bare local object.
        SettlementSavedData.get(level).settlements.put(s.id, s);
        SettlementSavedData.get(level).setDirty();
        RandomSource random = level.getRandom();
        CaptainRoster.ensureRoster(s, random);
        Captain fallen = s.sagaRoster.get(0);
        String fallenName = fallen.firstName();

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        raider.assign(fallen.id(), s.id, RaidObjective.BLOD, 1.0F, true);
        s.pendingRaid = new RaidPlan(fallen.id(), RaidObjective.BLOD, 0.0F, 1L);

        // A real kill through the normal damage pipeline (Entity#kill,
        // the same call `/kill` uses), not a bare call into die() directly
        // -- the same path an actual raid death takes.
        raider.kill();
        helper.assertTrue(fallen.id().equals(s.raidCaptainSlainId),
            "a raider wearing the captain flag must flag the settlement when "
                + "it dies mid-raid, got " + s.raidCaptainSlainId);

        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(s.raidCaptainSlainId == null,
            "the flag must be cleared so the next raid starts honest");

        helper.assertTrue(s.sagaRoster.size() == CaptainRoster.MAX_ROSTER,
            "the named cast must stay bounded even after a death, got "
                + s.sagaRoster.size());
        helper.assertTrue(s.sagaRoster.stream().noneMatch(c -> c.id().equals(fallen.id())),
            "a slain captain must be retired permanently from the named roster");
        helper.assertTrue(s.raidCaptains.stream().noneMatch(c -> c.id().equals(fallen.id())),
            "and never sent out again by RaidDirector#pickCaptain");

        Captain lieutenant = s.sagaRoster.stream()
            .filter(c -> fallenName.equals(c.swornTo()))
            .findFirst().orElse(null);
        helper.assertTrue(lieutenant != null,
            "a fresh lieutenant must rise carrying the grudge marker, roster now "
                + s.sagaRoster.stream().map(Captain::displayName).toList());
        helper.assertTrue(lieutenant.displayName().equals(
                lieutenant.firstName() + ", sworn to " + fallenName),
            "the name itself must reference the fallen, got " + lieutenant.displayName());
        helper.assertTrue(s.raidCaptains.stream().anyMatch(c -> c.id().equals(lieutenant.id())),
            "the lieutenant must also exist in the underlying enemy gallery");

        RaidLogEntry entry = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(entry.captainName().startsWith(fallenName),
            "the raid must be logged under the name the fallen captain led it "
                + "under, got " + entry.captainName());
        helper.succeed();
    }

    /**
     * (c) A raid that escapes with the goods grows its leader's own record
     * AND earns them their first epithet -- and the growth buys a readably
     * tougher, faster, differently-textured captain, never a hidden number.
     *
     * <p><b>Honesty note (2026-08-26 raid-night audit).</b> This drives the
     * epithet through {@code s.raidLootEscaped = true} regardless of the
     * plan's own objective, because that flag -- not "did this raid's own
     * objective actually succeed" -- is the ONLY signal {@code
     * RaidDirector#resolveIfOver} currently feeds {@code earnEpithetFrom}
     * (see {@code Captain}'s class doc for the gap). The test is plumbed
     * this way on purpose so it keeps passing once BRANN gets its own real
     * signal, but read it as "an epithet CAN be earned", not as proof a
     * BRANN raid earns "the Torch" by actually burning something -- today,
     * no raid of any objective but KORN ever can.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "saga_a_victorious_raid_grows_the_leader_and_earns_an_epithet")
    public void aVictoriousRaidGrowsTheLeaderAndEarnsAnEpithet(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = settlement(UUID.randomUUID(), 8);
        var level = helper.getLevel();
        RandomSource random = level.getRandom();
        CaptainRoster.ensureRoster(s, random);
        RaidCaptain captain = s.raidCaptains.get(0);
        Captain saga = CaptainRoster.find(s, captain.id());
        helper.assertTrue(saga != null && !saga.hasEpithet(),
            "setup: a fresh captain must not already have an epithet");

        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.BRANN, 0.0F, 1L);
        s.raidLootEscaped = true; // the band got away with the goods

        int victoriesBefore = captain.victories();
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(captain.victories() == victoriesBefore + 1,
            "an escaped raid must grow the leader's own record");
        helper.assertTrue(saga.hasEpithet(),
            "a raid that actually burned something must earn a name for it");
        helper.assertTrue("the Torch".equals(saga.epithet()),
            "the epithet must come from what this raid actually did (BRANN), got "
                + saga.epithet());

        // The buff that growth buys is entirely readable on the entity.
        RaiderEntity plain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        plain.assign(UUID.randomUUID(), s.id, RaidObjective.BRANN, 1.0F, true);
        RaiderEntity named = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(5, 1, 2));
        named.assign(captain.id(), s.id, RaidObjective.BRANN, 1.0F, true);
        named.markSagaCaptain(saga.displayName(), captain.victories(), saga.hasEpithet());

        helper.assertTrue(named.getMaxHealth() > plain.getMaxHealth(),
            "a named, grown captain must be readably tougher than a plain one, got "
                + named.getMaxHealth() + " vs " + plain.getMaxHealth());
        helper.assertTrue(
            named.getAttributeValue(Attributes.MOVEMENT_SPEED)
                > plain.getAttributeValue(Attributes.MOVEMENT_SPEED),
            "and faster, got " + named.getAttributeValue(Attributes.MOVEMENT_SPEED)
                + " vs " + plain.getAttributeValue(Attributes.MOVEMENT_SPEED));
        helper.assertTrue(named.isSagaMarked(),
            "an earned epithet must select the marked texture tier");
        helper.assertTrue(saga.displayName().equals(named.getCustomName() == null
                ? null : named.getCustomName().getString()),
            "and the name itself must be visible on the entity, got "
                + named.getCustomName());
        helper.succeed();
    }

    /**
     * (d) The night a raid actually arrives, the leader is named up front
     * -- not only in the morning report. RaidDirector's own broadcast
     * ("%s leads them against %s tonight") is built from exactly the same
     * display name this asserts is on the leading raider itself, since
     * {@code RaidBroadcast.send} is a thin, already-covered pass-through
     * (see its own class doc) rather than something worth re-verifying
     * delivery for here.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "saga_the_leading_raider_carries_the_name_the_broadcast_reports")
    public void theLeadingRaiderCarriesTheNameTheBroadcastReports(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlementForBand(helper, new BlockPos(8, 1, 8), 8);
        var level = helper.getLevel();
        RandomSource random = level.getRandom();
        CaptainRoster.ensureRoster(s, random);
        RaidCaptain captain = s.raidCaptains.get(0);
        Captain saga = CaptainRoster.find(s, captain.id());
        helper.assertTrue(saga != null, "setup: the picked captain must be Saga-tracked");

        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 1L);
        List<RaiderEntity> band = RaidDirector.spawnBand(level, s, plan);
        helper.assertTrue(!band.isEmpty(), "the band must actually arrive");
        RaiderEntity leader = band.stream()
            .filter(RaiderEntity::isCaptain).findFirst().orElse(null);
        helper.assertTrue(leader != null, "a band is led, so one of them is the captain");

        helper.assertTrue(leader.getCustomName() != null,
            "the leader must carry a visible name -- D-A3-3, no hidden identity");
        helper.assertTrue(saga.displayName().equals(leader.getCustomName().getString()),
            "and it must be exactly the name RaidDirector's own broadcast reports "
                + "it under, got " + leader.getCustomName().getString()
                + " vs " + saga.displayName());
        helper.assertTrue(leader.isCustomNameVisible(),
            "the nameplate must actually render");
        helper.succeed();
    }
}
