package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.AttributeConfig;
import com.hearthstead.entity.AttributeRuntime;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.entity.Trait;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Attributes lane, part 3 (plan/ATTRIBUTES.md Part 5): the trait parts that
 * used to be unread -- Trait.work (BIG_EATER, WELCOMING) and the flags
 * EARLY_RISER, NIGHT_OWL, GREEN_FINGERS and WELCOMING. Every test compares a
 * settler WITH the trait to an otherwise identical settler WITHOUT it
 * (all attributes 0, the neutral STOIC trait). Batch
 * {@code attributes_traits} opts in to {@code effectStrength = 1};
 * {@link #traitsAreOffByDefault} (batch attributes_neutral) proves the
 * GameTest default leaves every other suite alone. The world clock is never
 * moved: time-of-day effects are read through their {@code ...At} seams.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TraitGameTests {

    private static final String BATCH = "attributes_traits";

    @BeforeBatch(batch = BATCH)
    public static void on(ServerLevel level) { AttributeConfig.testOverride = 1.0D; }
    @AfterBatch(batch = BATCH)
    public static void off(ServerLevel level) { AttributeConfig.testOverride = null; }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(6, 1, 6)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, Profession job,
                                         Trait trait, int x, int z) {
        SettlerEntity e = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        e.setSettlerName("Tove" + x + z);
        if (s != null) {
            e.bindTo(s.id, s.center);
            s.putRecord(e.getUUID(), "Tove" + x + z, job);
        }
        e.setProfessionProjection(job);
        for (Attribute a : Attribute.ALL) {
            e.attributes().pinForTest(a, 0);
        }
        EnumSet<Trait> traits = e.traits();
        traits.retainAll(EnumSet.of(trait));
        traits.add(trait);
        return e;
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    private static void cleanup(GameTestHelper helper, Settlement s) {
        if (s != null) {
            AttributeRuntime.forgetWelcome(s);
            SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        }
    }

    // ------------------------------------------------------------ Trait.work --

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 40)
    public void bigEaterWorksFasterOnlyWhenFed(GameTestHelper helper) {
        floor(helper);
        SettlerEntity eater = settler(helper, null, Profession.SMITH, Trait.BIG_EATER, 1, 1);
        SettlerEntity plain = settler(helper, null, Profession.SMITH, Trait.STOIC, 4, 1);
        eater.setHunger(100.0F);
        plain.setHunger(100.0F);
        check(helper, SkillLevels.shortenWait(plain, 100) == 100, "plain smith: 100");
        check(helper, SkillLevels.shortenWait(eater, 100) == 85,
            "a fed BIG_EATER works 15% faster: " + SkillLevels.shortenWait(eater, 100));
        eater.setHunger(20.0F);
        check(helper, SkillLevels.shortenWait(eater, 100) == 100, "a hungry BIG_EATER gets no bonus");
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 40)
    public void welcomingTalksAtTheBenchButShinesInSocialJobs(GameTestHelper helper) {
        floor(helper);
        SettlerEntity smith = settler(helper, null, Profession.SMITH, Trait.WELCOMING, 1, 1);
        SettlerEntity host = settler(helper, null, Profession.INNKEEPER, Trait.WELCOMING, 4, 1);
        SettlerEntity plain = settler(helper, null, Profession.INNKEEPER, Trait.STOIC, 7, 1);
        check(helper, SkillLevels.shortenWait(smith, 100) == 108,
            "WELCOMING at a bench is 8% slower: " + SkillLevels.shortenWait(smith, 100));
        check(helper, SkillLevels.shortenWait(host, 100) == 95,
            "WELCOMING innkeeper is 5% faster: " + SkillLevels.shortenWait(host, 100));
        check(helper, SkillLevels.shortenWait(plain, 100) == 100, "plain innkeeper: 100");
        check(helper, AttributeRuntime.shortenWork(smith, 100) == 108, "builder/healer path agrees");
        helper.succeed();
    }

    // ------------------------------------------------------- clock traits --

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 40)
    public void earlyRiserAndNightOwlKeepTheirOwnHours(GameTestHelper helper) {
        floor(helper);
        SettlerEntity lark = settler(helper, null, Profession.FARMER, Trait.EARLY_RISER, 1, 1);
        SettlerEntity owl = settler(helper, null, Profession.FARMER, Trait.NIGHT_OWL, 4, 1);
        SettlerEntity watchOwl = settler(helper, null, Profession.GUARD, Trait.NIGHT_OWL, 10, 1);
        SettlerEntity plain = settler(helper, null, Profession.FARMER, Trait.STOIC, 7, 1);
        // 06:30: the village is still rising, the early riser is at work.
        check(helper, AttributeRuntime.dayPhaseOf(plain, 500L) == DayPhase.RISE, "plain rises at 06:30");
        check(helper, AttributeRuntime.dayPhaseOf(lark, 500L) == DayPhase.MORNING_WORK,
            "EARLY_RISER works at 06:30: " + AttributeRuntime.dayPhaseOf(lark, 500L));
        // 07:30: the village works, the night owl is still rising.
        check(helper, AttributeRuntime.dayPhaseOf(plain, 1500L) == DayPhase.MORNING_WORK, "plain works 07:30");
        check(helper, AttributeRuntime.dayPhaseOf(owl, 1500L) == DayPhase.RISE, "NIGHT_OWL still rises");
        // 17:30: evening for the village, the owl still works.
        check(helper, AttributeRuntime.dayPhaseOf(plain, 11500L) == DayPhase.EVENING, "plain evening");
        check(helper, AttributeRuntime.dayPhaseOf(owl, 11500L) == DayPhase.AFTERNOON_WORK,
            "NIGHT_OWL works late");
        // A guard keeps the watch rota: no shift, but still tires less at night.
        check(helper, AttributeRuntime.dayPhaseOf(watchOwl, 1500L) == DayPhase.MORNING_WORK,
            "a NIGHT_OWL guard keeps the rota clock");
        check(helper, AttributeRuntime.nightDrainAt(watchOwl, 0.08F, 18000L) < 0.07F,
            "a NIGHT_OWL guard tires less on the night watch");
        // The live entity path uses the same clock.
        check(helper, lark.dayPhase() == AttributeRuntime.dayPhaseOf(lark, helper.getLevel().getDayTime()),
            "SettlerEntity.dayPhase follows the trait clock");
        // Morning bonus for the lark, late-hours bonus and night fatigue for the owl.
        check(helper, AttributeRuntime.traitWorkCutAt(lark, 2000L) > 0.04D
            && AttributeRuntime.traitWorkCutAt(lark, 8000L) == 0.0D, "EARLY_RISER morning bonus only");
        check(helper, AttributeRuntime.traitWorkCutAt(owl, 10000L) > 0.04D
            && AttributeRuntime.traitWorkCutAt(owl, 2000L) == 0.0D, "NIGHT_OWL late bonus only");
        check(helper, AttributeRuntime.nightDrainAt(owl, 0.08F, 18000L) < 0.07F
            && AttributeRuntime.nightDrainAt(plain, 0.08F, 18000L) == 0.08F
            && AttributeRuntime.nightDrainAt(owl, 0.08F, 3000L) == 0.08F,
            "NIGHT_OWL tires a quarter less at night only");
        helper.succeed();
    }

    // ------------------------------------------------------ green fingers --

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 60)
    public void greenFingersMakeTheirFieldGrow(GameTestHelper helper) {
        floor(helper);
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.FARMLAND);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WHEAT);
            }
        }
        SettlerEntity gf = settler(helper, null, Profession.FARMER, Trait.GREEN_FINGERS, 8, 8);
        SettlerEntity plain = settler(helper, null, Profession.FARMER, Trait.STOIC, 9, 9);
        check(helper, AttributeRuntime.greenFingers(gf) && !AttributeRuntime.greenFingers(plain),
            "only the GREEN_FINGERS farmer tends");
        WorkZone zone = new WorkZone(UUID.randomUUID(), UUID.randomUUID(), WorkZone.Type.FARM,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(2, 1, 2)), helper.absolutePos(new BlockPos(4, 2, 4)), 1);
        int ticked = 0;
        for (int i = 0; i < 400; i++) {
            if (AttributeRuntime.tendOnce(helper.getLevel(), zone, gf.getRandom())) ticked++;
        }
        int ages = 0;
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                BlockState state = helper.getBlockState(new BlockPos(x, 2, z));
                ages += state.getBlock() instanceof CropBlock crop ? crop.getAge(state) : 0;
            }
        }
        // A crop that reaches full age stops random ticking, so a few late tends miss.
        check(helper, ticked > 300, "tends land on the field's crops: " + ticked);
        check(helper, ages > 0, "extra random ticks must grow the field, total age " + ages);
        int foundGf = 0;
        int foundPlain = 0;
        for (int i = 0; i < 4000; i++) {
            if (AttributeRuntime.extraFind(gf)) foundGf++;
            if (AttributeRuntime.extraFind(plain)) foundPlain++;
        }
        check(helper, foundPlain == 0, "plain farmer at Perception 0 finds no extra, got " + foundPlain);
        check(helper, foundGf > 120 && foundGf < 300, "GREEN_FINGERS finds ~5% extra, got " + foundGf);
        helper.succeed();
    }

    // ---------------------------------------------------------- welcoming --

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 60)
    public void aWelcomingMemberHoldsGuestsAndWarmsVisitors(GameTestHelper helper) {
        floor(helper);
        Settlement warm = settlement(helper, "Varmholm");
        SettlerEntity host = settler(helper, warm, Profession.FARMER, Trait.WELCOMING, 3, 3);
        helper.runAfterDelay(2, () -> {
            ServerLevel level = helper.getLevel();
            check(helper, AttributeRuntime.guestPatience(level, warm, 60_000L) == 90_000L,
                "a guest waits half again as long");
            check(helper, AttributeRuntime.tavernVisitTicks(host, TavernSeating.VISIT_TICKS)
                    == TavernSeating.VISIT_TICKS * 3 / 2, "tavern visits last half again as long");
            check(helper, AttributeRuntime.welcomingPoints(level, warm, "traveller") == 3,
                "+3 persuasion with a traveller");
            check(helper, AttributeRuntime.welcomingPoints(level, warm, "raider") == 0,
                "no welcome for raiders");
            // Without the trait: the same settlement, the host now STOIC.
            EnumSet<Trait> traits = host.traits();
            traits.retainAll(EnumSet.of(Trait.STOIC));
            traits.add(Trait.STOIC);
            AttributeRuntime.forgetWelcome(warm);
            check(helper, AttributeRuntime.guestPatience(level, warm, 60_000L) == 60_000L,
                "no WELCOMING member, ordinary patience");
            check(helper, AttributeRuntime.welcomingPoints(level, warm, "traveller") == 0,
                "no WELCOMING member, no welcome");
            cleanup(helper, warm);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ neutral --

    @GameTest(batch = "attributes_neutral", template = "empty16", timeoutTicks = 40)
    public void traitsAreOffByDefault(GameTestHelper helper) {
        floor(helper);
        check(helper, AttributeConfig.strength(helper.getLevel().getServer()) == 0.0D, "strength 0");
        SettlerEntity eater = settler(helper, null, Profession.SMITH, Trait.BIG_EATER, 1, 1);
        eater.setHunger(100.0F);
        SettlerEntity lark = settler(helper, null, Profession.FARMER, Trait.EARLY_RISER, 4, 1);
        SettlerEntity gf = settler(helper, null, Profession.FARMER, Trait.GREEN_FINGERS, 7, 1);
        check(helper, SkillLevels.shortenWait(eater, 100) == 100, "no BIG_EATER pace");
        check(helper, AttributeRuntime.dayPhaseOf(lark, 500L) == DayPhase.RISE, "no clock shift");
        check(helper, !AttributeRuntime.greenFingers(gf), "no green fingers");
        helper.succeed();
    }
}
