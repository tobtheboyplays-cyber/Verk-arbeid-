package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.AttributeConfig;
import com.hearthstead.entity.AttributeRuntime;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Attributes lane, part 2 (plan/ATTRIBUTES.md Part 4): the Trait fields
 * speed / sight / FEARFUL / WATCHFUL that used to be unread. (The six Mayor
 * boon tests left with the retired Mayor office.)
 * Batch {@code attributes_boons} opts in to {@code effectStrength = 1}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MayorBoonTraitGameTests {

    private static final String BATCH = "attributes_boons";

    @BeforeBatch(batch = BATCH)
    public static void on(ServerLevel level) { AttributeConfig.testOverride = 1.0D; }
    @AfterBatch(batch = BATCH)
    public static void off(ServerLevel level) { AttributeConfig.testOverride = null; }

    // ------------------------------------------------------------ fixture --

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(5, 1, 5)));
        s.radius = 5;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /** A member with every attribute at {@code value} and one neutral trait. */
    private static SettlerEntity member(GameTestHelper helper, Settlement s, Profession job,
                                        int value, Trait trait, int x, int z) {
        SettlerEntity e = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        e.setSettlerName("Ragna" + x);
        e.bindTo(s.id, s.center);
        s.putRecord(e.getUUID(), "Ragna" + x, job);
        e.setProfessionProjection(job);
        for (Attribute a : Attribute.ALL) {
            e.attributes().pinForTest(a, value);
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

    // ------------------------------------------------------------- traits --

    /** Trait speed reaches the real movement attribute through the needs tick. */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void strongBackWalksSlowerAndFearfulFaster(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper, "Fothold");
        SettlerEntity slow = member(helper, s, Profession.NONE, 0, Trait.STRONG_BACK, 1, 1);
        SettlerEntity fast = member(helper, s, Profession.NONE, 0, Trait.FEARFUL, 4, 1);
        SettlerEntity plain = member(helper, s, Profession.NONE, 0, Trait.STOIC, 7, 1);
        helper.succeedWhen(() -> {
            AttributeModifier ms = slow.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(AttributeRuntime.TRAIT_SPEED_ID);
            AttributeModifier mf = fast.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(AttributeRuntime.TRAIT_SPEED_ID);
            check(helper, ms != null && Math.abs(ms.amount() + 0.08D) < 1e-4, "STRONG_BACK walks 8% slower");
            check(helper, mf != null && Math.abs(mf.amount() - 0.15D) < 1e-4, "FEARFUL runs 15% faster");
            check(helper, plain.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(AttributeRuntime.TRAIT_SPEED_ID) == null, "no speed trait, no modifier");
            SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        });
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 40)
    public void watchfulSeesFartherAndFearfulPanicsEarlier(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper, "Sjaholm");
        SettlerEntity watch = member(helper, s, Profession.HUNTER, 0, Trait.WATCHFUL, 1, 1);
        SettlerEntity fear = member(helper, s, Profession.NONE, 0, Trait.FEARFUL, 4, 1);
        SettlerEntity plain = member(helper, s, Profession.HUNTER, 0, Trait.STOIC, 7, 1);
        check(helper, Math.abs(AttributeRuntime.range(watch, 20.0D) - 26.0D) < 1e-6,
            "WATCHFUL hunts and shoots 30% farther: " + AttributeRuntime.range(watch, 20.0D));
        check(helper, AttributeRuntime.range(plain, 20.0D) == 20.0D, "no trait, no extra range");
        check(helper, AttributeRuntime.panicScanRadius(fear) == 16.0D, "FEARFUL scans 16 blocks");
        check(helper, AttributeRuntime.panicScanRadius(plain) == 12.0D, "an ordinary civilian scans 12");
        check(helper, AttributeRuntime.panicScanRadius(watch) > 15.0D, "WATCHFUL scans ~15.6");
        check(helper, AttributeRuntime.criesAlarm(watch) && !AttributeRuntime.criesAlarm(plain),
            "only the WATCHFUL cry the alarm on sight");
        SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        helper.succeed();
    }

    /**
     * Live: a WATCHFUL civilian who sees a monster raises the settlement's
     * alarm through SettlerPanicGoal, with no Guard anywhere to report to.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 120)
    public void watchfulCivilianRaisesTheAlarmOnSight(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper, "Varsel");
        member(helper, s, Profession.NONE, 0, Trait.WATCHFUL, 2, 2);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(8, 1, 8));
        zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        helper.succeedWhen(() -> {
            check(helper, s.alertActive(helper.getLevel().getGameTime()),
                "the WATCHFUL settler must raise the alarm on seeing the zombie");
            SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        });
    }
}
