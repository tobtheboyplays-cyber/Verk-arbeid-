package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Trade skill levels ({@link SkillLevels}): XP only from completed units,
 * the 1..10 curve, level-1 gating, persistence and old-save defaults.
 * Own batch; never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeSkillGameTests {

    private static SettlerEntity settler(GameTestHelper helper, Profession profession) {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        settler.setSettlerName("Ansgar");
        settler.setProfessionProjection(profession);
        // Baseline Intelligence: no WITS bonus, so XP is exactly the unit count.
        settler.attributes().pinForTest(Attribute.WITS, 5);
        return settler;
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    /** One completed unit = its XP, once; attribute-only train() awards none. */
    @GameTest(batch = "trade_skill", template = "empty16", timeoutTicks = 40)
    public void xpAccruesOncePerCompletedUnit(GameTestHelper helper) {
        SettlerEntity farmer = settler(helper, Profession.FARMER);
        // Non-unit training (e.g. tidying a warehouse) never feeds the level.
        farmer.train(Attribute.STAMINA, 1.0F);
        check(helper, farmer.tradeSkills().xp(Profession.FARMER) == 0,
            "train() alone must not award trade XP");
        for (int i = 0; i < 3; i++) {
            SkillLevels.completeUnit(farmer, 1, Attribute.STAMINA, Attribute.DEXTERITY);
        }
        check(helper, farmer.tradeSkills().xp(Profession.FARMER) == 3,
            "three harvested units must be exactly 3 XP, got "
                + farmer.tradeSkills().xp(Profession.FARMER));
        check(helper, farmer.tradeXp() == 3, "synced XP must follow the award");
        // Other trades' tracks are untouched; martial/unemployed never accrue.
        check(helper, farmer.tradeSkills().xp(Profession.COURIER) == 0,
            "courier track must stay empty");
        SettlerEntity guard = settler(helper, Profession.GUARD);
        check(helper, SkillLevels.completeUnit(guard, 5) == 0,
            "martial professions keep their rank system");
        helper.succeed();
    }

    /** Crossing a threshold fires onLevelUp exactly once per level. */
    @GameTest(batch = "trade_skill", template = "empty16", timeoutTicks = 40)
    public void levelUpFiresOncePerLevel(GameTestHelper helper) {
        SettlerEntity baker = settler(helper, Profession.BAKER);
        int step = SkillLevels.xpForLevel(2);
        for (int i = 0; i < step - 1; i++) {
            SkillLevels.completeUnit(baker, 1, Attribute.DEXTERITY);
        }
        check(helper, SkillLevels.levelOf(baker) == 1 && SkillLevels.levelUpCount(baker) == 0,
            "one XP short of level 2 must still be level 1");
        SkillLevels.completeUnit(baker, 1, Attribute.DEXTERITY);
        check(helper, SkillLevels.levelOf(baker) == 2 && SkillLevels.levelUpCount(baker) == 1,
            "the threshold unit must reach level 2 and fire once");
        SkillLevels.completeUnit(baker, 1, Attribute.DEXTERITY);
        check(helper, SkillLevels.levelUpCount(baker) == 1,
            "no second level-up without a new threshold");
        helper.succeed();
    }

    /** Curve is strictly increasing per step and capped at level 10. */
    @GameTest(batch = "trade_skill", template = "empty16", timeoutTicks = 20)
    public void curveIncreasesAndCaps(GameTestHelper helper) {
        int previousStep = 0;
        for (int level = 2; level <= SkillLevels.MAX_LEVEL; level++) {
            int step = SkillLevels.xpForLevel(level) - SkillLevels.xpForLevel(level - 1);
            check(helper, step > previousStep, "step to level " + level + " must grow");
            previousStep = step;
        }
        check(helper, SkillLevels.levelOf(Integer.MAX_VALUE) == SkillLevels.MAX_LEVEL,
            "level caps at 10");
        SettlerEntity cook = settler(helper, Profession.COOK);
        cook.tradeSkills().set(Profession.COOK, SkillLevels.MAX_XP);
        SkillLevels.completeUnit(cook, 50, Attribute.DEXTERITY);
        check(helper, cook.tradeSkills().xp(Profession.COOK) == SkillLevels.MAX_XP,
            "XP is bounded at the level-10 total");
        helper.succeed();
    }

    /** Level 1 is exactly the old behaviour; effects are capped and modest. */
    @GameTest(batch = "trade_skill", template = "empty16", timeoutTicks = 20)
    public void effectsGatedAtLevelOne(GameTestHelper helper) {
        for (int ticks : new int[] {0, 1, 7, 40, 200, 1234}) {
            check(helper, SkillLevels.shortenWait(ticks, 1, 99) == ticks,
                "level 1 wait must be unchanged for " + ticks);
            check(helper, SkillLevels.shortenLooped(ticks, 20, 1, 99) == ticks,
                "level 1 craft duration must be unchanged for " + ticks);
        }
        check(helper, SkillLevels.sideChance(4, 99) == 0.0D, "no side bonus below level 5");
        check(helper, SkillLevels.speedBonus(10, 99) <= SkillLevels.MAX_SPEED_BONUS + 1e-9,
            "speed capped at 18%");
        int looped = SkillLevels.shortenLooped(200, 20, 10, 99);
        check(helper, looped % 20 == 0 && looped < 200 && looped >= 20,
            "looped shortening removes whole clip loops only, got " + looped);
        SettlerEntity fresh = settler(helper, Profession.SMITH);
        check(helper, SkillLevels.shortenWait(fresh, 60) == 60 && !SkillLevels.rollSide(fresh),
            "a fresh level-1 worker behaves exactly as before");
        helper.succeed();
    }

    /** Round-trip keeps every track; a pre-skill save loads at level 1 / 0 XP. */
    @GameTest(batch = "trade_skill", template = "empty16", timeoutTicks = 40)
    public void nbtRoundTripAndOldSaveDefault(GameTestHelper helper) {
        SettlerEntity source = settler(helper, Profession.MINER);
        source.tradeSkills().set(Profession.MINER, 150);
        source.tradeSkills().set(Profession.FARMER, 30);
        CompoundTag saved = new CompoundTag();
        source.addAdditionalSaveData(saved);

        SettlerEntity loaded = settler(helper, Profession.NONE);
        loaded.readAdditionalSaveData(saved);
        check(helper, loaded.tradeSkills().xp(Profession.MINER) == 150
                && loaded.tradeSkills().xp(Profession.FARMER) == 30,
            "trade XP must survive a save/load");
        check(helper, loaded.tradeXp() == 150 && SkillLevels.levelOf(loaded) == 4,
            "loaded miner must sync XP 150 = level 4");
        check(helper, SkillLevels.levelUpCount(loaded) == 0,
            "loading must never replay a level-up");

        CompoundTag old = saved.copy();
        old.remove(SkillLevels.NBT_KEY);
        SettlerEntity legacy = settler(helper, Profession.NONE);
        legacy.readAdditionalSaveData(old);
        check(helper, legacy.tradeSkills().xp(Profession.MINER) == 0
                && SkillLevels.levelOf(legacy) == 1,
            "an old save defaults to level 1 with 0 XP");
        helper.succeed();
    }
}
