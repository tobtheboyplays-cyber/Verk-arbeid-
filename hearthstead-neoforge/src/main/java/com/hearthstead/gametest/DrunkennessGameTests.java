package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Drunkenness;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tavern lane: drunkenness on real entities and real blocks. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class DrunkennessGameTests {

    /**
     * The drunkenness switch is one global for the whole batch (QA-TEST-03): the four
     * tavern_drunk tests run at once, and a test that cleared it on finishing turned it off
     * under a slower test still waiting on its speed modifier.
     */
    @BeforeBatch(batch = "tavern_drunk")
    public static void drunkennessOn(ServerLevel level) {
        Drunkenness.testOverride = true;
    }

    @AfterBatch(batch = "tavern_drunk")
    public static void drunkennessOff(ServerLevel level) {
        Drunkenness.testOverride = null;
    }

    /** The empty16 template has no floor: lay a real stone floor (y=0) with air above. */
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    /** 1 ale = no slowdown; 3 = -40%; a guard on duty never gets drunk at all. */
    @GameTest(batch = "tavern_drunk", template = "empty16", timeoutTicks = 120)
    public void aleLevelsSlowCiviliansButNeverTheWatch(GameTestHelper h) {
        floor(h);
        SettlerEntity guest = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        SettlerEntity guard = h.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 8));
        guard.setProfessionProjection(Profession.GUARD);
        guest.setNoAi(true);
        guard.setNoAi(true);
        long now = h.getLevel().getGameTime();
        for (int i = 0; i < 3; i++) guard.markAleDrunk(now, 2400);
        guest.markAleDrunk(now, 2400);
        h.runAfterDelay(21, () -> {
            h.assertTrue(guest.drunkLevel() == Drunkenness.TIPSY, "one ale is a tipsy glow");
            h.assertTrue(guest.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Drunkenness.SPEED_ID) == null,
                "a tipsy glow does not slow anyone");
            h.assertTrue(guard.drunkLevel() == Drunkenness.SOBER, "a guard on duty never gets drunk");
            long t = h.getLevel().getGameTime();
            guest.markAleDrunk(t, 2400);
            guest.markAleDrunk(t, 2400);
        });
        h.runAfterDelay(62, () -> {
            var mod = guest.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Drunkenness.SPEED_ID);
            h.assertTrue(guest.drunkLevel() == Drunkenness.VERY && mod != null && Math.abs(mod.amount() + 0.40) < 1e-6,
                "three ales: very drunk, -40% speed");
            h.succeed();
        });
    }

    /** A raid alarm, a target or panic sobers the MOVEMENT on the settler's very next tick. */
    @GameTest(batch = "tavern_drunk", template = "empty16", timeoutTicks = 80)
    public void alarmRemovesTheDrunkSlowdownAtOnce(GameTestHelper h) {
        floor(h);
        SettlerEntity guest = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        SettlerEntity other = h.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 8));
        guest.setNoAi(true);
        other.setNoAi(true);
        long now = h.getLevel().getGameTime();
        for (int i = 0; i < 3; i++) guest.markAleDrunk(now, 2400);
        h.runAfterDelay(25, () -> {
            h.assertTrue(guest.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Drunkenness.SPEED_ID) != null,
                "very drunk: slowed");
            guest.setTarget(other);
        });
        h.runAfterDelay(26, () -> {
            h.assertTrue(guest.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Drunkenness.SPEED_ID) == null,
                "the first tick after the alarm: full speed (not up to 19 ticks later)");
            h.assertTrue(guest.drunkLevel() == Drunkenness.VERY, "still drunk - only the movement is sobered");
            guest.setTarget(null);
            h.succeed();
        });
    }

    /** Drunkenness survives a save/load with the elapsed wear-off; legacy saves load sober. */
    @GameTest(batch = "tavern_drunk", template = "empty16", timeoutTicks = 40)
    public void drunkennessSurvivesAReload(GameTestHelper h) {
        floor(h);
        SettlerEntity guest = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        guest.setNoAi(true);
        long now = h.getLevel().getGameTime();
        guest.markAleDrunk(now, 2400);
        guest.markAleDrunk(now, 2400);
        var tag = new net.minecraft.nbt.CompoundTag();
        guest.saveWithoutId(tag);
        h.assertTrue(tag.contains("DrunkPoints") && tag.contains("DrunkStamp"), "points and stamp saved");
        SettlerEntity copy = ModEntities.SETTLER.get().create(h.getLevel());
        copy.load(tag);
        h.assertTrue(copy.drunkLevel() == Drunkenness.DRUNK && Math.abs(copy.drunkPoints() - 2.0) < 0.05,
            "two ales still count after the reload");
        tag.remove("DrunkPoints");
        tag.remove("DrunkStamp");
        SettlerEntity legacy = ModEntities.SETTLER.get().create(h.getLevel());
        legacy.load(tag);
        h.assertTrue(legacy.drunkLevel() == Drunkenness.SOBER && legacy.drunkPoints() == 0.0, "legacy save: sober");
        copy.discard();
        legacy.discard();
        h.succeed();
    }

    /** The weave (and a fall) never target a cell with no floor, a drop, water or lava. */
    @GameTest(batch = "tavern_drunk", template = "empty16", timeoutTicks = 40)
    public void weavingNeverTargetsUnsafeCells(GameTestHelper h) {
        floor(h);
        BlockPos floor = new BlockPos(5, 1, 5);
        BlockPos water = new BlockPos(6, 1, 5), lava = new BlockPos(5, 1, 7), hole = new BlockPos(7, 1, 7);
        h.setBlock(water, Blocks.WATER);
        h.setBlock(lava, Blocks.LAVA);
        h.setBlock(hole.below(), Blocks.AIR);
        var level = h.getLevel();
        h.assertTrue(Drunkenness.safeCell(level, h.absolutePos(floor)), "plain floor is safe");
        h.assertFalse(Drunkenness.safeCell(level, h.absolutePos(water)), "never into water");
        h.assertFalse(Drunkenness.safeCell(level, h.absolutePos(lava)), "never into lava");
        h.assertFalse(Drunkenness.safeCell(level, h.absolutePos(hole)), "never off a ledge");
        h.assertFalse(Drunkenness.safeToFall(level, h.absolutePos(floor)), "no fall next to water");
        h.assertTrue(Drunkenness.safeToFall(level, h.absolutePos(new BlockPos(11, 1, 11))), "flat open floor");
        h.succeed();
    }
}
