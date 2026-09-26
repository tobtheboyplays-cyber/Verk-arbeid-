package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.gear.GearGate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Gear Tiers end to end (owner, 26 Sep: "I don't want to be able to give him
 * a diamond sword right away"). A settler refuses gear above their clearance
 * WITHOUT losing it (it stays in the pack), and puts it on unprompted once
 * both halves hold: their own rank AND the settlement's knowledge.
 *
 * <p>Lives in the development package so the Iron Arms Drill can be learned
 * through the real (package-private) state, the same way the upgrade
 * catalogue tests do. The Castle Charter and Master Armoury are not in code
 * yet; the diamond test grants them through {@link GearGate#grantForTest}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GearProgressionGameTests {

    private static final String BATCH = "gear_progression";

    public GearProgressionGameTests() {
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 400)
    public void recruitRefusesDiamondUntilSergeantAndCastleKnowledge(GameTestHelper helper) {
        arena(helper);
        Settlement s = settlement(helper, "Diamond Test");
        SettlerEntity guard = guard(helper, s, "Brenna");
        guard.bag.setItem(0, new ItemStack(Items.DIAMOND_CHESTPLATE));
        guard.bag.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        helper.assertTrue(GuardRank.of(guard) == GuardRank.RECRUIT,
            "a fresh guard must be a Recruit");

        helper.runAfterDelay(45, () -> {
            helper.assertTrue(!guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "a Recruit must refuse the diamond chestplate");
            helper.assertTrue(!GearGate.allows(guard, new ItemStack(Items.DIAMOND_SWORD)),
                "a Recruit must refuse the diamond sword");
            helper.assertTrue(count(guard, Items.DIAMOND_CHESTPLATE) == 1
                    && count(guard, Items.DIAMOND_SWORD) == 1,
                "refused gear must stay in the pack, not vanish or duplicate");
            // Promotion alone is not enough: the settlement lacks the knowledge.
            trainStrengthTo(guard, GuardRank.SERGEANT.threshold());
        });
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(GuardRank.of(guard).atLeast(GuardRank.SERGEANT),
                "training must have earned Sergeant");
            helper.assertTrue(!guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "a Sergeant without the Castle Charter and Master Armoury still refuses");
            GearGate.grantForTest(s.id, "node:castle_charter");
            GearGate.grantForTest(s.id, "node:master_armoury");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "once promoted and the tech is learned the guard wears the diamond, found "
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(GearGate.allows(guard, new ItemStack(Items.DIAMOND_SWORD)),
                "and may now wield the diamond sword");
            helper.assertTrue(count(guard, Items.DIAMOND_CHESTPLATE) == 1,
                "exactly one diamond chestplate across pack and body");
            GearGate.clearTestGrants(s.id);
        });
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 400)
    public void ironPlateNeedsVeteranAndTheIronArmsDrill(GameTestHelper helper) {
        arena(helper);
        Settlement s = settlement(helper, "Plate Test");
        SettlerEntity guard = guard(helper, s, "Aldric");
        guard.bag.setItem(3, new ItemStack(Items.IRON_CHESTPLATE));
        guard.bag.setItem(4, new ItemStack(Items.CHAINMAIL_HELMET));

        helper.runAfterDelay(45, () -> {
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                    && guard.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                "a Recruit wears neither plate nor mail");
            helper.assertTrue(count(guard, Items.IRON_CHESTPLATE) == 1
                    && count(guard, Items.CHAINMAIL_HELMET) == 1,
                "refused pieces stay in the pack");
            trainStrengthTo(guard, GuardRank.VETERAN.threshold());
            DevelopmentState state = Development.of(helper.getLevel(), s);
            state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
            state.unlockUpgrade(PostRaidUpgrade.GUARD_ARMS_IRON);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "a Veteran with the Iron Arms Drill wears the handed-over plate, found "
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.CHAINMAIL_HELMET),
                "and the mail coif (Village Charter), found "
                    + guard.getItemBySlot(EquipmentSlot.HEAD));
            helper.assertTrue(count(guard, Items.IRON_CHESTPLATE) == 1
                    && count(guard, Items.CHAINMAIL_HELMET) == 1,
                "nothing duplicated on the way on");
        });
    }

    /**
     * Kill switch ({@code [features] gearTiers=false}): the pre-tier rules,
     * safely. Nothing is gated, nobody dresses from the pack, the request cap
     * is lifted, and the handed-over diamond is neither worn nor lost.
     * Own batch: the override is global while it is set.
     */
    @GameTest(template = "empty16", batch = "gear_killswitch", timeoutTicks = 200)
    public void gearTiersDisabledFallsBackSafely(GameTestHelper helper) {
        arena(helper);
        Settlement s = settlement(helper, "Switch Test");
        SettlerEntity guard = guard(helper, s, "Osric");
        GearGate.setEnabledOverrideForTest(false);
        guard.bag.setItem(0, new ItemStack(Items.DIAMOND_CHESTPLATE));
        helper.runAfterDelay(45, () -> {
            try {
                helper.assertTrue(!GearGate.enabled(), "the switch must read as off");
                helper.assertTrue(GearGate.allows(guard, new ItemStack(Items.NETHERITE_SWORD)),
                    "switched off, nothing is gated (pre-tier rules)");
                com.hearthstead.settlement.equipment.EquipmentRequirement swords =
                    new com.hearthstead.settlement.equipment.EquipmentRequirement(
                        Items.WOODEN_SWORD, net.minecraft.tags.ItemTags.SWORDS.location(), 8);
                helper.assertTrue(GearGate.limit(swords, guard) == swords,
                    "switched off, requests carry no tier cap");
                helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                    "switched off, guards do not dress from their pack");
                helper.assertTrue(count(guard, Items.DIAMOND_CHESTPLATE) == 1,
                    "switched off, the handed-over piece is kept, never deleted");
                helper.assertTrue(guard.gearClearancePacked() == GearGate.open(
                        GearGate.roleOf(Profession.GUARD)).pack(),
                    "the synced projection shows every tier open");
            } finally {
                GearGate.setEnabledOverrideForTest(null);
            }
            helper.succeed();
        });
    }

    // --------------------------------------------------------------- fixture

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity guard(GameTestHelper helper, Settlement s, String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        settler.assignProfession(Profession.GUARD);
        return settler;
    }

    private static void trainStrengthTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.STRENGTH) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.STRENGTH, 5.0F, 1.0F);
        }
    }

    /** Pack plus every worn slot: the exact physical count of {@code item}. */
    private static int count(SettlerEntity settler, Item item) {
        int total = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (settler.bag.getItem(i).is(item)) {
                total += settler.bag.getItem(i).getCount();
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (settler.getItemBySlot(slot).is(item)) {
                total += settler.getItemBySlot(slot).getCount();
            }
        }
        return total;
    }
}
