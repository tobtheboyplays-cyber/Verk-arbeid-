package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.AttributeConfig;
import com.hearthstead.entity.AttributeRuntime;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.entity.Trait;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleMove;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Attributes rework (plan/ATTRIBUTES.md): a high-attribute and a
 * low-attribute settler in the same job, side by side in a live world,
 * through the same runtime hooks the work goals call, must differ
 * measurably. Batch {@code attributes_effects} opts in to
 * {@code [attributes] effectStrength = 1}; batch {@code attributes_neutral}
 * proves the GameTest default (strength 0) leaves every other suite alone.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AttributeGameTests {

    private static final String ON = "attributes_effects";
    private static final String OFF = "attributes_neutral";

    @BeforeBatch(batch = ON)
    public static void on(ServerLevel level) { AttributeConfig.testOverride = 1.0D; }
    @AfterBatch(batch = ON)
    public static void offAfter(ServerLevel level) { AttributeConfig.testOverride = null; }
    @BeforeBatch(batch = OFF)
    public static void neutral(ServerLevel level) { AttributeConfig.testOverride = null; }

    // ------------------------------------------------------------ fixture --

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** A settler in {@code job} with every attribute pinned to {@code value}. */
    private static SettlerEntity worker(GameTestHelper helper, Profession job, int value, int x) {
        SettlerEntity s = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, 2));
        s.setSettlerName(value >= 50 ? "Hild" : "Lodin");
        s.setProfessionProjection(job);
        for (Attribute a : Attribute.ALL) {
            s.attributes().pinForTest(a, value);
        }
        // A trait with no speed/sight/morale multiplier, so only attributes differ.
        java.util.EnumSet<Trait> traits = s.traits();
        traits.retainAll(java.util.EnumSet.of(Trait.STOIC));
        traits.add(Trait.STOIC);
        return s;
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    // ---------------------------------------------------------- work pace --

    /** Job fit: the strong, deft smith finishes the same batch and the same wait sooner. */
    @GameTest(batch = ON, template = "empty16", timeoutTicks = 40)
    public void highAttributeSmithWorksFaster(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.SMITH, 90, 1);
        SettlerEntity lo = worker(helper, Profession.SMITH, 2, 5);
        int batchHi = SkillLevels.shortenLooped(hi, 400, 20);
        int batchLo = SkillLevels.shortenLooped(lo, 400, 20);
        int waitHi = SkillLevels.shortenWait(hi, 100);
        int waitLo = SkillLevels.shortenWait(lo, 100);
        check(helper, batchHi < batchLo,
            "batch time must be shorter for the better smith: " + batchHi + " vs " + batchLo);
        // 90/90 smith: job fit ~14% + Focus ~11% -> at least 20% off a 400-tick batch.
        check(helper, batchHi <= 320, "a 90/90 smith should cut >= 20% of 400 ticks, got " + batchHi);
        check(helper, waitHi < waitLo, "work pause must be shorter: " + waitHi + " vs " + waitLo);
        check(helper, SkillLevels.shortenWait(hi, 100) >= 70,
            "level + job fit never cut more than 30%");
        int buildHi = AttributeRuntime.shortenWork(worker(helper, Profession.BUILDER, 90, 3), 12);
        int buildLo = AttributeRuntime.shortenWork(worker(helper, Profession.BUILDER, 2, 6), 12);
        check(helper, buildHi < buildLo, "builder placement: " + buildHi + " vs " + buildLo);
        helper.succeed();
    }

    // ------------------------------------------------------------ combat --

    @GameTest(batch = ON, template = "empty16", timeoutTicks = 40)
    public void highAttributeSpearmanHitsHarderAndRecoversSooner(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.SPEARMAN, 99, 1);
        SettlerEntity lo = worker(helper, Profession.SPEARMAN, 0, 5);
        // Same rank for both, so only the attribute effect differs.
        hi.attributes().pinForTest(Attribute.STRENGTH, 19);
        lo.attributes().pinForTest(Attribute.STRENGTH, 0);
        float dHi = RoleCombat.blowDamage(hi, RoleMove.SPEAR_THRUST, 1.0D);
        float dLo = RoleCombat.blowDamage(lo, RoleMove.SPEAR_THRUST, 1.0D);
        check(helper, dHi > dLo * 1.05F, "Strength 19 must add >5% melee damage: " + dHi + " vs " + dLo);
        int cHi = AttributeRuntime.meleeCadence(hi, RoleMove.SPEAR_THRUST.cadenceTicks());
        int cLo = AttributeRuntime.meleeCadence(lo, RoleMove.SPEAR_THRUST.cadenceTicks());
        check(helper, cHi < cLo, "Dexterity must shorten swing recovery: " + cHi + " vs " + cLo);
        float sHi = AttributeRuntime.spread(hi, 6.0F);
        check(helper, sHi < 6.0F * 0.75F, "Dexterity 99 must cut arrow spread by >25%: " + sHi);
        int drawHi = AttributeRuntime.drawCast(hi, 20);
        check(helper, drawHi <= 16, "Focus 99 must cut a 20-tick draw to 16: " + drawHi);
        helper.succeed();
    }

    // ------------------------------------------------------------- needs --

    /** Stamina reaches max health through the live once-a-second needs tick. */
    @GameTest(batch = ON, template = "empty16", timeoutTicks = 100)
    public void highStaminaSettlerHasMoreHealth(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.NONE, 99, 1);
        SettlerEntity lo = worker(helper, Profession.NONE, 0, 5);
        helper.succeedWhen(() -> {
            float diff = hi.getMaxHealth() - lo.getMaxHealth();
            check(helper, Math.abs(diff - 4.0F) < 0.01F,
                "Stamina 99 vs 0 must be exactly +4 max health, got " + diff);
            check(helper, AttributeRuntime.workingDrain(hi, 0.09F) < AttributeRuntime.workingDrain(lo, 0.09F),
                "Stamina must lower working energy drain");
        });
    }

    /** Spirit shrinks a morale loss through the real addMorale path. */
    @GameTest(batch = ON, template = "empty16", timeoutTicks = 40)
    public void highSpiritSettlerLosesLessMorale(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.NONE, 99, 1);
        SettlerEntity lo = worker(helper, Profession.NONE, 0, 5);
        hi.addMorale(100.0F);
        lo.addMorale(100.0F);
        float hiBefore = hi.getMorale();
        float loBefore = lo.getMorale();
        hi.addMorale(-20.0F);
        lo.addMorale(-20.0F);
        // Normalise away each settler's rolled temperament (STOIC etc.).
        double hiLoss = (hiBefore - hi.getMorale()) / Trait.moraleDecay(hi.traits());
        double loLoss = (loBefore - lo.getMorale()) / Trait.moraleDecay(lo.traits());
        check(helper, Math.abs(loLoss - 20.0D) < 0.01D, "Spirit 0 loses the full 20, got " + loLoss);
        check(helper, Math.abs(hiLoss - 16.0D) < 0.01D, "Spirit 99 loses 20% less (16), got " + hiLoss);
        helper.succeed();
    }

    // ------------------------------------------------ perception, output --

    @GameTest(batch = ON, template = "empty16", timeoutTicks = 40)
    public void highPerceptionHunterSearchesWiderAndFindsMore(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.HUNTER, 99, 1);
        SettlerEntity lo = worker(helper, Profession.HUNTER, 0, 5);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        AABB boxHi = HunterWorkGoal.huntBounds(anchor, hi);
        AABB boxLo = HunterWorkGoal.huntBounds(anchor, lo);
        check(helper, boxLo.getXsize() == HunterWorkGoal.huntBounds(anchor).getXsize(),
            "Perception 0 keeps the base hunting box");
        check(helper, boxHi.getXsize() > boxLo.getXsize() * 1.2D,
            "Perception 99 widens the box by 25%: " + boxHi.getXsize() + " vs " + boxLo.getXsize());
        int foundHi = 0;
        int foundLo = 0;
        for (int i = 0; i < 2000; i++) {
            if (AttributeRuntime.extraFind(hi)) foundHi++;
            if (AttributeRuntime.extraFind(lo)) foundLo++;
        }
        check(helper, foundLo == 0, "Perception 0 never finds extra, got " + foundLo);
        check(helper, foundHi > 120 && foundHi < 300, "Perception 99 finds ~10% extra, got " + foundHi);
        check(helper, AttributeRuntime.haulBonus(hi) == 4 && AttributeRuntime.haulBonus(lo) == 0,
            "Strength 99 hauls +4 per trip, Strength 0 none");
        check(helper, AttributeRuntime.heal(hi, 8.0F) > AttributeRuntime.heal(lo, 8.0F),
            "Spirit raises a healer's heal");
        check(helper, AttributeRuntime.hospitality(hi, 3.0F) > 3.5F,
            "Presence 99 raises an innkeeper's ale morale above 3.5");
        check(helper, AttributeRuntime.study(hi) > AttributeRuntime.study(lo),
            "Focus raises a scholar's research per session");
        helper.succeed();
    }

    /** Presence: the settlement's best speaker adds persuasion points. */
    @GameTest(batch = ON, template = "empty16", timeoutTicks = 40)
    public void bestSpeakerAddsPersuasion(GameTestHelper helper) {
        floor(helper);
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Talholm",
            helper.absolutePos(new BlockPos(4, 1, 4)));
        s.radius = 4;
        data.settlements.put(s.id, s);
        data.setDirty();
        int before = AttributeRuntime.persuasionPoints(helper.getLevel(), s);
        SettlerEntity trader = worker(helper, Profession.TRADER, 99, 2);
        trader.bindTo(s.id, s.center);
        s.putRecord(trader.getUUID(), "Hild", Profession.TRADER);
        helper.runAfterDelay(2, () -> {
            int after = AttributeRuntime.persuasionPoints(helper.getLevel(), s);
            check(helper, before == 0, "no speaker, no points: " + before);
            check(helper, after == 10, "a Presence-99 trader speaks for +10 points, got " + after);
            SettlerEntity poor = worker(helper, Profession.TRADER, 0, 5);
            int paidHi = 0;
            int paidLo = 0;
            for (int i = 0; i < 200; i++) {
                paidHi += AttributeRuntime.tradePayout(trader, 10);
                paidLo += AttributeRuntime.tradePayout(poor, 10);
            }
            check(helper, paidLo == 2000, "Presence 0 is paid exactly the quote, got " + paidLo);
            check(helper, paidHi >= 2150 && paidHi <= 2250,
                "Presence 99 is paid ~10% more (2200 of 2000), got " + paidHi);
            data.settlements.remove(s.id);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ neutral --

    /** The GameTest default: strength 0, so other suites' pinned numbers hold. */
    @GameTest(batch = OFF, template = "empty16", timeoutTicks = 100)
    public void effectsAreOffOnTheGameTestServerByDefault(GameTestHelper helper) {
        floor(helper);
        SettlerEntity hi = worker(helper, Profession.SMITH, 99, 1);
        SettlerEntity lo = worker(helper, Profession.SMITH, 0, 5);
        check(helper, AttributeConfig.strength(helper.getLevel().getServer()) == 0.0D,
            "GameTest server strength must default to 0");
        check(helper, SkillLevels.shortenLooped(hi, 400, 20) == 400,
            "no job fit or Focus when neutral");
        check(helper, AttributeRuntime.meleeGain(hi) == 1.0F && AttributeRuntime.haulBonus(hi) == 0,
            "no melee or haul bonus when neutral");
        helper.succeedWhen(() -> check(helper, hi.getMaxHealth() == lo.getMaxHealth(),
            "no Stamina health when neutral"));
    }
}
