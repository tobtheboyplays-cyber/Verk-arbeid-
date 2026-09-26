package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Five simultaneous, spread approaching threats; no sequential substitution or scripted damage. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardFiveZombieGameTests {
    @GameTest(template="empty32",timeoutTicks=1000,batch="guard_five_zombie_encounter")
    public void woodenStarterFacesFiveApproachingZombies(GameTestHelper helper) { encounter(helper,false); }
    @GameTest(template="empty32",timeoutTicks=1000,batch="guard_five_zombie_encounter")
    public void ordinaryLeatherGuardFacesFiveApproachingZombies(GameTestHelper helper) { encounter(helper,true); }

    @GameTest(template="empty32", timeoutTicks=40, batch="guard_health_capacity")
    public void guardCapacitySurvivesSaveWithoutHiringHeal(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Guard capacity",
            helper.absolutePos(new BlockPos(6,1,16)));
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id, settlement);
        var barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(10,1,16));
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), "Capacity", Profession.NONE);
        guard.setHealth(12);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(), "real employment");
        helper.assertTrue(guard.getMaxHealth()==80 && guard.getHealth()==12, "hire increases capacity without healing");
        guard.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        guard.setOnGround(true);
        guard.knockback(1, 1, 0);
        helper.assertTrue(Math.abs(guard.getDeltaMovement().x) > .2
                && Math.abs(guard.getDeltaMovement().x) < .5,
            "actual vanilla knockback is reduced, never erased, by trained Guard footing");
        guard.setHealth(37); // Controlled saved-state fixture, not a gameplay heal.
        var saved = new net.minecraft.nbt.CompoundTag();
        guard.saveWithoutId(saved);
        SettlerEntity restored = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "reload entity created");
        restored.load(saved);
        com.hearthstead.entity.GuardHealth.refresh(restored);
        helper.assertTrue(restored.getMaxHealth()==80 && restored.getHealth()==37,
            "real NBT reload preserves capacity and wounded HP above civilian maximum");
        // Upgrade a genuine old 64-HP attribute encoding without minting current health.
        var legacyMaximum = restored.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        var capacityId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_health_capacity");
        legacyMaximum.removeModifier(capacityId);
        legacyMaximum.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            capacityId, 40, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        var legacySaved = new net.minecraft.nbt.CompoundTag();
        restored.saveWithoutId(legacySaved);
        SettlerEntity migrated = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(migrated != null, "legacy entity created");
        migrated.load(legacySaved);
        com.hearthstead.entity.GuardHealth.refresh(migrated);
        helper.assertTrue(migrated.getMaxHealth()==80 && migrated.getHealth()==37,
            "old 64-HP employment capacity migrates without healing or retaining duplicate modifiers");
        barracks.workers.remove(guard.getUUID());
        Employment.refresh(settlement, guard);
        helper.assertTrue(guard.getMaxHealth()==24 && guard.getHealth()==24, "dismissal removes capacity and clamps health");
        guard.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        guard.setOnGround(true);
        guard.knockback(1, 1, 0);
        helper.assertTrue(Math.abs(guard.getDeltaMovement().x) > .9,
            "dismissal restores ordinary physical knockback, without a retained Guard modifier");
        guard.setHealth(9);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(), "rehire succeeds");
        Employment.refresh(settlement, guard);
        helper.assertTrue(guard.getMaxHealth()==80 && guard.getHealth()==9, "rehire and repeated refresh cannot heal");
        SettlerEntity civilian = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(12,1,16));
        civilian.assignProfession(Profession.GUARD);
        com.hearthstead.entity.GuardHealth.refresh(civilian);
        helper.assertTrue(civilian.getMaxHealth()==24, "projection alone never grants Guard capacity");
        SettlementManager.data(helper.getLevel()).settlements.remove(settlement.id);
        SettlementManager.data(helper.getLevel()).setDirty();
        helper.succeed();
    }

    private static void encounter(GameTestHelper helper,boolean leather) {
        helper.assertTrue(helper.getLevel().getDifficulty()==Difficulty.NORMAL,
            "this balance acceptance requires actual Normal difficulty; never relabel another difficulty");
        for(int x=0;x<32;x++) for(int z=0;z<32;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x,4,z),Blocks.STONE_BRICKS);
            for(int y=1;y<4;y++) helper.setBlock(new BlockPos(x,y,z),
                x==0||z==0||x==31||z==31 ? Blocks.STONE_BRICKS : Blocks.AIR);
        }
        BlockPos hearthPos=new BlockPos(6,1,16);
        Settlement settlement=new Settlement(UUID.randomUUID(),"Five approaching",helper.absolutePos(hearthPos));
        settlement.radius=24;
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id,settlement);
        SettlementManager.data(helper.getLevel()).setDirty();
        helper.setBlock(hearthPos,ModBlocks.HEARTH.get());
        HearthBlockEntity hearth=(HearthBlockEntity)helper.getBlockEntity(hearthPos);
        hearth.bindSettlement(settlement.id);
        var barracks=GameTestFixtures.register(helper,settlement,BuildingType.BARRACKS,2,2);
        SettlerEntity guard=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(10,1,16));
        guard.bindTo(settlement.id,settlement.center);
        settlement.putRecord(guard.getUUID(),"Five encounter",Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(),settlement,barracks,guard).ok(),"actual Guard employment required");
        guard.attributes().pinForTest(Attribute.STRENGTH,leather?20:10);
        guard.attributes().pinForTest(Attribute.WITS,10);
        guard.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.WOODEN_SWORD));
        guard.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))
            guard.setItemSlot(slot,ItemStack.EMPTY);
        if(leather) guard.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.LEATHER_CHESTPLATE));
        guard.setHealth(guard.getMaxHealth());
        List<Zombie> enemies=new ArrayList<>();
        for(int z:new int[]{8,12,16,20,24}) {
            Zombie zombie=helper.spawn(EntityType.ZOMBIE,new BlockPos(24,1,z));
            zombie.setBaby(false);
            zombie.setCanPickUpLoot(false);
            for(EquipmentSlot slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)) zombie.setItemSlot(slot,ItemStack.EMPTY);
            zombie.setTarget(guard);
            enemies.add(zombie);
        }
        Hearthstead.LOGGER.info("GUARD_FIVE_START leather={} guard={} hp={} max={} knockbackResistance={} tick={}",
            leather, guard.getUUID(), guard.getHealth(), guard.getMaxHealth(),
            guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE),
            helper.getLevel().getGameTime());
        int[] damageSamples={0};
        float[] lastHealth={guard.getHealth()};
        helper.onEachTick(()->{
            long killed=enemies.stream().filter(enemy->!enemy.isAlive()).count();
            if ((guard.getHealth() < lastHealth[0] || !guard.isAlive()) && damageSamples[0]++ < 32) {
                var target = guard.getTarget();
                Hearthstead.LOGGER.info("GUARD_FIVE_DAMAGE leather={} guard={} tick={} before={} after={} max={} kills={} activity={} pos={} velocity={} hurtTime={} target={} targetHp={} targetDistance={} navDone={}",
                    leather, guard.getUUID(), helper.getLevel().getGameTime(), lastHealth[0], guard.getHealth(),
                    guard.getMaxHealth(), killed, guard.getActivity(), guard.position(), guard.getDeltaMovement(),
                    guard.hurtTime, target == null ? "none" : target.getUUID(),
                    target == null ? -1 : target.getHealth(), target == null ? -1 : guard.distanceTo(target),
                    guard.getNavigation().isDone());
            }
            helper.assertTrue(guard.isAlive(),"Guard died: actual kills="+killed+"/5, leather="+leather);
            helper.assertTrue(guard.getHealth()<=lastHealth[0]+0.001F,"no free between-enemy healing allowed");
            lastHealth[0]=guard.getHealth();
            helper.assertTrue(!guard.hasMeal() && hearth.countFoodUnits()==0,"food-retreat is a separate acceptance, not hidden assistance");
            helper.assertTrue(guard.getMainHandItem().is(Items.WOODEN_SWORD)
                && guard.getOffhandItem().isEmpty(),"only the actual wooden sword may fight; no shield or gear replacement");
            if(killed==5) {
                Hearthstead.LOGGER.info("GUARD_FIVE_ENCOUNTER_PASS leather={} kills=5 health={} swordDamage={} food=0",
                    leather,guard.getHealth(),guard.getMainHandItem().getDamageValue());
                SettlementManager.data(helper.getLevel()).settlements.remove(settlement.id);
                SettlementManager.data(helper.getLevel()).setDirty();
                helper.succeed();
            }
        });
    }
}
