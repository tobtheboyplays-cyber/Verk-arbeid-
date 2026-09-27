package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardRecoveryGoal;
import com.hearthstead.entity.ai.GuardRecoveryPolicy;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardRecoveryGameTests {
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "guard_food_recovery")
    public void safeGuardConsumesRealMealsThenRelinquishesRecovery(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        }
        BlockPos pos = new BlockPos(8, 1, 8);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Recovery", helper.absolutePos(pos));
        settlement.radius = 6;
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id, settlement);
        helper.setBlock(pos, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(pos);
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 16));
        var barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 8));
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), "Recovery", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(),
            "fixture must employ a real Guard");
        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        sword.setDamageValue(2);
        guard.setItemSlot(EquipmentSlot.MAINHAND, sword);
        // Owner decision 25 Sep: a well-fed settler also heals slowly on its own.
        // These tests measure meal healing only, so that trickle is switched off.
        guard.suppressWellFedRegenForTests();
        float initial = guard.getMaxHealth() * 0.25F;
        guard.setHealth(initial);
        GuardRecoveryGoal goal = guard.goalSelector.getAvailableGoals().stream()
            .map(wrapped -> wrapped.getGoal()).filter(GuardRecoveryGoal.class::isInstance)
            .map(GuardRecoveryGoal.class::cast).findFirst().orElse(null);
        if (goal == null) {
            goal = new GuardRecoveryGoal(guard);
            guard.goalSelector.addGoal(1, goal);
        }
        GuardRecoveryGoal recovery = goal;
        boolean[] eating = {false};
        boolean[] reloaded = {false};
        helper.onEachTick(() -> {
            int stock = hearth.getInventory().getStackInSlot(0).getCount();
            int mealsConsumed = 16 - stock - (guard.hasMeal() ? 1 : 0);
            helper.assertTrue(mealsConsumed >= 0 && guard.getHealth()
                    <= initial + mealsConsumed * GuardRecoveryPolicy.MAX_HEAL_PER_MEAL + 0.001F,
                "HP requires a completed physical meal, never withdrawal or a timer: hp=" + guard.getHealth()
                    + " initial=" + initial + " stock=" + stock + " meal=" + guard.hasMeal() + " hunger=" + guard.getHunger()
                    + " activity=" + guard.getActivity() + " t=" + helper.getTick());
            helper.assertTrue(guard.getHealth() <= guard.getMaxHealth()
                    * GuardRecoveryPolicy.RETURN_FRACTION + 0.001F,
                "recovery must stop at the bounded return threshold");
            eating[0] |= guard.hasMeal();
            if (!reloaded[0] && guard.getHealth() > guard.getMaxHealth() * 0.30F
                    && guard.getHealth() < guard.getMaxHealth() * 0.65F) {
                // Decode an unpublished entity: no second world actor or inventory
                // owner is introduced by checking the actual saved recovery latch.
                var saved = guard.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                SettlerEntity decoded = ModEntities.SETTLER.get().create(helper.getLevel());
                helper.assertTrue(decoded != null, "saved Guard must decode");
                decoded.load(saved);
                helper.assertTrue(new GuardRecoveryGoal(decoded).canUse(),
                    "saved recovery must continue above entry threshold until the return threshold");
                reloaded[0] = true;
            }

            helper.assertTrue(guard.getMainHandItem() == sword && sword.getDamageValue() == 2,
                "meal ownership must never replace or erase the physical sword");
            if (guard.getHealth() >= guard.getMaxHealth() * GuardRecoveryPolicy.RETURN_FRACTION) {
                helper.assertTrue(eating[0] && reloaded[0] && mealsConsumed > 0 && !recovery.canUse(),
                    "after paid recovery the combat/order selector must regain ownership");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 400, batch = "guard_food_recovery")
    public void emptyHearthAndNearbyThreatNeverGrantHealing(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        }
        BlockPos pos = new BlockPos(8, 1, 8);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Recovery", helper.absolutePos(pos));
        settlement.radius = 6;
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id, settlement);
        helper.setBlock(pos, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(pos);
        hearth.bindSettlement(settlement.id);
        var barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 8));
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), "Recovery", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(),
            "fixture must employ a real Guard");
        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        sword.setDamageValue(2);
        guard.setItemSlot(EquipmentSlot.MAINHAND, sword);
        // Owner decision 25 Sep: a well-fed settler also heals slowly on its own.
        // These tests measure meal healing only, so that trickle is switched off.
        guard.suppressWellFedRegenForTests();
        float initial = guard.getMaxHealth() * 0.25F;
        guard.setHealth(initial);
        GuardRecoveryGoal goal = guard.goalSelector.getAvailableGoals().stream()
            .map(wrapped -> wrapped.getGoal()).filter(GuardRecoveryGoal.class::isInstance)
            .map(GuardRecoveryGoal.class::cast).findFirst().orElse(null);
        if (goal == null) {
            goal = new GuardRecoveryGoal(guard);
            guard.goalSelector.addGoal(1, goal);
        }

        // A contained, roofed room prevents a safe six-block escape. The hostile
        // is controlled test setup, while the ordinary goal selector owns recovery.
        for (int x = 5; x <= 10; x++) for (int z = 5; z <= 10; z++) {
            helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS);
            if (x == 5 || x == 10 || z == 5 || z == 10) {
                for (int y = 1; y < 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
            }
        }
        GuardRecoveryGoal recovery = goal;
        int[] ticks = {0};
        int[] dangerStart = {-1};
        int[] remaining = {-1};
        net.minecraft.world.entity.monster.Zombie[] hostile = {null};
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] <= 25) {
                helper.assertTrue(guard.getHealth() == initial && !guard.hasMeal(),
                    "an empty Hearth must not create food or free healing");
                if (ticks[0] == 25) {
                    helper.assertTrue(!recovery.canUse(),
                        "unfunded emergency recovery must leave combat authority intact");
                    hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 16));
                }
                return;
            }
            if (dangerStart[0] < 0 && guard.hasMeal()) {
                hostile[0] = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(7, 1, 6));
                hostile[0].setNoAi(true);
                remaining[0] = guard.mealRemainingTicks();
                dangerStart[0] = ticks[0];
                return;
            }
            if (hostile[0] != null) {
                helper.assertTrue(guard.getActivity() == com.hearthstead.entity.SettlerActivity.RETREATING,
                    "actual Guard danger recovery must project tactical retreat, never civilian panic");
                helper.assertTrue(!guard.prefersRoads(),
                    "tactical retreat retains direct escape routing");
                helper.assertTrue(guard.hasMeal() && guard.mealRemainingTicks() == remaining[0]
                        && guard.getHealth() == initial,
                    "visible nearby danger must pause the exact owned meal without consuming or healing");
                if (ticks[0] - dangerStart[0] >= 25) {
                    hostile[0].discard();
                    hostile[0] = null;
                }
                return;
            }
            if (dangerStart[0] >= 0 && guard.getHealth() >= guard.getMaxHealth()
                    * GuardRecoveryPolicy.RETURN_FRACTION) {
                helper.assertTrue(!recovery.canUse(), "safe paid recovery must relinquish control");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "guard_food_recovery")
    public void freshGuardPreparesFullyWithRealFoodAndYieldsToUnsafeHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x=0; x<16; x++) for (int z=0; z<16; z++) {
            helper.setBlock(new BlockPos(x,0,z), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x,4,z), Blocks.STONE_BRICKS);
            for (int y=1; y<4; y++) helper.setBlock(new BlockPos(x,y,z),
                x==0 || z==0 || x==15 || z==15 ? Blocks.STONE_BRICKS : Blocks.AIR);
        }
        BlockPos pos = new BlockPos(8,1,8);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Physical readiness", helper.absolutePos(pos));
        settlement.radius = 6;
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id, settlement);
        SettlementManager.data(helper.getLevel()).setDirty();
        helper.setBlock(pos, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(pos);
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD,16));
        var barracks = GameTestFixtures.register(helper,settlement,BuildingType.BARRACKS,2,2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7,1,8));
        guard.bindTo(settlement.id,settlement.center);
        settlement.putRecord(guard.getUUID(), "Readiness", Profession.NONE);
        helper.assertTrue(guard.getHealth()==24 && Employment.hire(helper.getLevel(),settlement,barracks,guard).ok()
                && guard.getHealth()==24 && guard.getMaxHealth()==80, "actual civilian hire preserves24HP; no ready-health grant");
        guard.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.WOODEN_SWORD));
        guard.setHunger(100);
        guard.suppressWellFedRegenForTests(); // meal healing only (see above)
        var peace = guard.goalSelector.getAvailableGoals().stream().map(wrapped->wrapped.getGoal())
            .filter(GuardRecoveryGoal.class::isInstance).map(GuardRecoveryGoal.class::cast)
            .filter(goal -> goal.peacetime()).findFirst().orElseThrow();
        int[] tick={0}, unsafeStart={-1}, fullAt={-1};
        net.minecraft.world.entity.monster.Zombie[] threat={null};
        helper.onEachTick(()->{
            tick[0]++;
            int stock=hearth.getInventory().getStackInSlot(0).getCount();
            int consumed=16-stock-(guard.hasMeal()?1:0);
            helper.assertTrue(consumed>=0 && guard.getHealth()<=24+4*consumed+.001F,
                "every HP requires a completed actual food item, never just withdrawal: hp=" + guard.getHealth()
                    + " stock=" + stock + " meal=" + guard.hasMeal() + " hunger=" + guard.getHunger()
                    + " activity=" + guard.getActivity() + " t=" + tick[0]);
            if (unsafeStart[0]<0 && guard.getHealth()>=52) {
                helper.assertTrue(guard.getHealth()==52 && stock==9 && !guard.hasMeal(),
                    "emergency recovery ends at52 after exactly7 bread");
                threat[0]=helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,new BlockPos(8,1,6));
                threat[0].setNoAi(true); threat[0].setInvulnerable(true);
                unsafeStart[0]=tick[0];
            }
            if (threat[0]!=null) {
                helper.assertTrue(guard.getHealth()==52 && stock==9 && !guard.hasMeal()
                        && !peace.canUse() && !peace.canContinueToUse(),
                    "real hostile at Hearth must stop paid peacetime healing and additional withdrawal");
                if (tick[0]-unsafeStart[0]>=30) { threat[0].discard(); threat[0]=null; }
                return;
            }
            if (guard.getHealth()==80) {
                helper.assertTrue(unsafeStart[0]>=0 && consumed==14 && stock==2 && !guard.hasMeal(),
                    "24 to80 requires exactly14 actual bread with no extra charge at full health");
                if (fullAt[0]<0) fullAt[0]=tick[0];
                helper.assertTrue(!peace.canUse(), "full Guard cannot restart paid preparation");
                if (tick[0]-fullAt[0]>=60) {
                    guard.discard();
                    SettlementManager.data(helper.getLevel()).settlements.remove(settlement.id);
                    SettlementManager.data(helper.getLevel()).setDirty();
                    helper.succeed();
                }
            }
        });
    }
}
