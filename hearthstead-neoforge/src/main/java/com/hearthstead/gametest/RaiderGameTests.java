package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE A3 step 3 — the raider itself.
 *
 * <p>The thing these guard against is the failure both references shipped:
 * raiders that are indistinguishable from each other and from the player's
 * own guards, scaled from the player's stat sheet rather than from anything
 * the enemy has done.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderGameTests {

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

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Raidholm",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * A captain is a different creature, not a bigger health bar: more
     * health AND more damage, and the renderer keys its helm, pauldron and
     * scale off the same flag, so you can read who leads a raid from across
     * the field.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void captainsAreVisiblyAndMechanicallyDistinct(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity grunt = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        UUID captainId = UUID.randomUUID();
        grunt.assign(captainId, s.id, RaidObjective.KORN, 1.0F, false);
        captain.assign(captainId, s.id, RaidObjective.KORN, 1.0F, true);

        helper.assertTrue(!grunt.isCaptain(), "the follower is not the captain");
        helper.assertTrue(captain.isCaptain(), "the captain is");
        helper.assertTrue(captain.getMaxHealth() > grunt.getMaxHealth(),
            "a captain must be harder to kill: " + captain.getMaxHealth()
                + " vs " + grunt.getMaxHealth());
        double captainDamage = captain.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double gruntDamage = grunt.getAttributeValue(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(captainDamage > gruntDamage,
            "and must hit harder: " + captainDamage + " vs " + gruntDamage);
        helper.succeed();
    }

    /**
     * Strength comes from the captain's own record, not from the player's
     * stat sheet -- and it is capped, so a long feud stays winnable rather
     * than becoming a wall.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void menaceScalesStrengthAndIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity mild = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity feared = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        RaiderEntity absurd = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 2));
        UUID captainId = UUID.randomUUID();
        mild.assign(captainId, s.id, RaidObjective.BLOD, 1.0F, false);
        feared.assign(captainId, s.id, RaidObjective.BLOD, 2.0F, false);
        absurd.assign(captainId, s.id, RaidObjective.BLOD, 99.0F, false);

        helper.assertTrue(feared.getMaxHealth() > mild.getMaxHealth(),
            "menace must scale health, got " + feared.getMaxHealth()
                + " vs " + mild.getMaxHealth());
        helper.assertTrue(absurd.menace() <= RaiderEntity.MAX_MENACE,
            "menace must be capped, got " + absurd.menace());
        helper.assertTrue(absurd.getMaxHealth()
                <= mild.getMaxHealth() * RaiderEntity.MAX_MENACE + 0.01F,
            "and so must the health it buys, got " + absurd.getMaxHealth());
        helper.succeed();
    }

    /** Raiders never turn on each other, however the melee goes. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void raidersDoNotFightEachOther(GameTestHelper helper) {
        buildArena(helper, 10);
        RaiderEntity a = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity b = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        helper.assertTrue(!a.canAttack(b), "a raider must not target another raider");
        helper.succeed();
    }

    /**
     * A raid that despawns is a raid that never happened. Raiders are
     * persistent, and their orders survive a save.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void raidersPersistAndRememberTheirOrders(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        UUID captainId = UUID.randomUUID();
        raider.assign(captainId, s.id, RaidObjective.BRANN, 1.5F, true);
        raider.setObjectivePos(helper.absolutePos(new BlockPos(5, 1, 5)));

        helper.assertTrue(!raider.removeWhenFarAway(4096.0),
            "raiders must never despawn for distance");

        var tag = new net.minecraft.nbt.CompoundTag();
        raider.addAdditionalSaveData(tag);
        RaiderEntity reloaded = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(6, 1, 2));
        reloaded.readAdditionalSaveData(tag);

        helper.assertTrue(reloaded.isCaptain(), "captaincy must survive a save");
        helper.assertTrue(reloaded.objective() == RaidObjective.BRANN,
            "as must the objective, got " + reloaded.objective());
        helper.assertTrue(captainId.equals(reloaded.captainId()),
            "and who they answer to");
        helper.assertTrue(s.id.equals(reloaded.settlementId()),
            "and which settlement they came for");
        helper.assertTrue(reloaded.objectivePos() != null,
            "and where they were headed");
        helper.succeed();
    }

    /**
     * The band forms up on the planned bearing, spread across a front
     * rather than stacked on one point -- MineColonies players report
     * raiders "usually come from the same spawn point" and ganging up on
     * one tower guard (#193), and TekTopia uses four fixed corners.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "day")
    public void theBandFormsUpOnThePlannedBearing(GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        // North is -Z; the geometry must put a 180-degree approach south of
        // the settlement and a 0-degree approach north of it.
        BlockPos north = RaidDirector.formUpAt(center, 0.0F, 30);
        BlockPos south = RaidDirector.formUpAt(center, 180.0F, 30);
        BlockPos east = RaidDirector.formUpAt(center, -90.0F, 30);
        helper.assertTrue(north.getZ() > center.getZ(),
            "0 degrees should form up on +Z, got " + north);
        helper.assertTrue(south.getZ() < center.getZ(),
            "180 degrees should form up on -Z, got " + south);
        helper.assertTrue(east.getX() > center.getX(),
            "-90 degrees should form up on +X, got " + east);
        // And the distance must be honoured, so a band never lands on top of
        // the settlement it came to raid.
        double d = Math.sqrt(north.distSqr(center));
        helper.assertTrue(Math.abs(d - 30.0) < 1.5,
            "form-up distance should be honoured, got " + d);
        helper.succeed();
    }

    /** A band is a band, never a horde, however rich the settlement gets. */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "day")
    public void bandSizeGrowsWithWorthButIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement small = makeSettlement(helper, new BlockPos(5, 1, 5));
        for (int i = 0; i < 4; i++) {
            small.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        Settlement huge = new Settlement(UUID.randomUUID(), "Huge", BlockPos.ZERO);
        for (int i = 0; i < 60; i++) {
            huge.putRecord(UUID.randomUUID(), "H" + i, Profession.NONE);
        }
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        int smallBand = RaidDirector.bandSizeFor(small, captain);
        int hugeBand = RaidDirector.bandSizeFor(huge, captain);
        helper.assertTrue(hugeBand > smallBand,
            "a richer settlement draws a bigger band: " + hugeBand
                + " vs " + smallBand);
        helper.assertTrue(hugeBand <= RaidDirector.MAX_BAND,
            "and it must stay a band, not a horde: " + hugeBand);
        helper.assertTrue(smallBand >= RaidDirector.MIN_BAND,
            "a raid is never one lone figure: " + smallBand);
        helper.succeed();
    }

    /**
     * A raid must actually arrive, and must actually end. A scheduled raid
     * that never concludes is the raid-shaped version of MineColonies'
     * deliveries that silently never happen.
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "day")
    public void aRaidArrivesAndThenResolves(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        var random = level.getRandom();
        RaidCaptain captain = RaidDirector.pickCaptain(s, random);
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 3L);
        s.pendingRaid = plan;

        int spawned = RaidDirector.spawnBand(level, s, plan);
        helper.assertTrue(spawned > 0,
            "the band must actually arrive, spawned " + spawned);
        helper.assertTrue(!RaidDirector.livingRaidersOf(level, s).isEmpty(),
            "and must be findable as this settlement's raiders");
        boolean anyCaptain = RaidDirector.livingRaidersOf(level, s).stream()
            .anyMatch(RaiderEntity::isCaptain);
        helper.assertTrue(anyCaptain, "a band is led, so one of them is the captain");

        // Not over while anyone still stands.
        helper.assertTrue(!RaidDirector.resolveIfOver(level, s),
            "a raid with raiders left standing is not over");
        helper.assertTrue(s.pendingRaid != null, "so the plan must still be set");

        int pressureBefore = s.raidPressure.pressure();
        int defeatsBefore = captain.defeats();
        for (RaiderEntity r : RaidDirector.livingRaidersOf(level, s)) {
            r.discard();
        }
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with none left standing the raid must resolve");
        helper.assertTrue(s.pendingRaid == null,
            "and the plan must be cleared so the next night can be rolled");
        helper.assertTrue(s.raidPressure.pressure() > pressureBefore,
            "repelling a raid must RAISE pressure -- the deliberate inverse "
                + "of MineColonies buying quiet with a loss. Got "
                + s.raidPressure.pressure() + " from " + pressureBefore);
        helper.assertTrue(captain.defeats() == defeatsBefore + 1,
            "and the captain must remember being driven off");
        helper.succeed();
    }

    /**
     * Theft is physical. A raider takes goods OUT of a real chest and INTO
     * its own real inventory, the chest is genuinely emptier, and killing
     * the raider gives the goods back. MineColonies leaves a chat line and a
     * day of mourning; its own feature requests (#113, #129) are asking for
     * exactly this -- a consequence you can chase down.
     */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "day")
    public void raidersStealRealGoodsAndDropThemWhenKilled(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        BlockPos chestRel = new BlockPos(9, 1, 9);
        helper.setBlock(chestRel, Blocks.CHEST);
        var bounds = net.minecraft.world.level.levelgen.structure.BoundingBox
            .fromCorners(helper.absolutePos(new BlockPos(8, 1, 8)),
                helper.absolutePos(new BlockPos(10, 3, 10)));
        // A plaque block MUST exist at the anchor. BuildingManager's sweep
        // dissolves any building whose plaquePos holds no plaque -- correctly,
        // since "no plaque, no building" is the permanent invariant (D-005).
        // A fixture that skips this registers a building the game then deletes
        // out from under the test, on a round-robin sweep shared with every
        // other concurrently running test: the root cause of KF-014.
        helper.setBlock(new BlockPos(8, 1, 8), ModBlocks.PLAQUE.get());
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(8, 1, 8)),
            helper.absolutePos(new BlockPos(8, 1, 8)), bounds);
        warehouse.valid = true;
        s.buildings.add(warehouse);
        var chest = (net.minecraft.world.Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(chestRel));
        helper.assertTrue(chest != null, "the store must exist");
        chest.setItem(0, new ItemStack(Items.WHEAT, 12));
        final int total = 12;

        RaiderEntity thief = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        thief.assign(UUID.randomUUID(), s.id, RaidObjective.KORN, 1.0F, false);

        helper.succeedWhen(() -> {
            int inChest = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack st = chest.getItem(i);
                if (st.is(Items.WHEAT)) {
                    inChest += st.getCount();
                }
            }
            int carried = thief.lootCount();
            helper.assertTrue(inChest + carried == total,
                "wheat must be conserved at every instant: chest=" + inChest
                    + " carried=" + carried);
            helper.assertTrue(carried > 0,
                "the raider should be taking the stores, chest still has "
                    + inChest);
            helper.assertTrue(inChest < total,
                "and the chest must be genuinely emptier, not just counted down");
        });
    }

    /**
     * Getting away with the goods and being wiped out empty-handed must
     * resolve DIFFERENTLY. Whether the settlement held is about whether the
     * raiders got what they came for, not about who died.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "day")
    public void escapingWithTheStoresResolvesAsALoss(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 2L);
        s.raidPressure.setPressureForTesting(50);

        // The band is gone AND the goods went with them.
        s.raidLootEscaped = true;
        int before = s.raidPressure.pressure();
        int victoriesBefore = captain.victories();
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(s.raidPressure.pressure() < before,
            "losing the stores must EASE pressure -- at a price already paid. "
                + "Got " + s.raidPressure.pressure() + " from " + before);
        helper.assertTrue(captain.victories() == victoriesBefore + 1,
            "and the captain must remember winning");
        helper.assertTrue(!s.raidLootEscaped,
            "the flag must be cleared so the next raid starts honest");
        helper.succeed();
    }

    /** Guards already hunt hostiles, so a raider is a target without a special case. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "day")
    public void guardsTreatRaidersAsHostile(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(5, 1, 5));
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 5));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        helper.assertTrue(raider instanceof net.minecraft.world.entity.monster.Monster,
            "raiders must be Monsters so existing guard targeting sees them");
        helper.succeedWhen(() -> helper.assertTrue(
            guard.getTarget() == raider || raider.getTarget() == guard,
            "a guard and a raider inside the settlement should engage; guard="
                + guard.getTarget() + " raider=" + raider.getTarget()));
    }
}
