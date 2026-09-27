package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.*;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.work.FarmWorkApproach;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FarmerBagLifecycleGameTests {
    @GameTest(template="empty16",timeoutTicks=1400,batch="farmer_bag_lifecycle")
    public void actualFieldHandBagAndTimedChestSurviveReloadAndFullTarget(GameTestHelper helper) {
        runLifecycle(helper, false, false);
    }

    @GameTest(template="empty16",timeoutTicks=1400,batch="farmer_bag_lifecycle")
    public void driftedOwnedCropSurvivesReloadOutsideExactField(GameTestHelper helper) {
        runLifecycle(helper, true, false);
    }

    @GameTest(template="empty16",timeoutTicks=1400,batch="farmer_bag_lifecycle")
    public void expiredFieldLeaseResumesOnlyOriginalFarmerDropAndDeposits(GameTestHelper helper) {
        runLifecycle(helper, false, true);
    }

    /**
     * The direct crop line is water, but the actual Farmer-only plant route
     * must take a dry detour and retain its normal fractional farmland feet.
     */
    @GameTest(template="empty16", timeoutTicks=240, batch="farmer_bag_lifecycle")
    public void farmerPlantRouteDetoursAroundWaterWithoutWetContact(GameTestHelper helper) {
        var level = helper.getLevel();
        for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
        }
        BlockPos cropRel = new BlockPos(13, 1, 8);
        BlockPos farmlandContactRel = new BlockPos(11, 1, 8);
        helper.setBlock(cropRel.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRel, ((CropBlock) Blocks.WHEAT).getStateForAge(CropBlock.MAX_AGE));
        helper.setBlock(farmlandContactRel.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        // Recessed, stone-bounded water cannot flood the dry detours at z=2 and z=13.
        for (int z = 3; z <= 12; z++) {
            helper.setBlock(new BlockPos(7, -1, z), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(7, 0, z), Blocks.WATER);
        }

        SettlerEntity farmer = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 8));
        SettlerEntity farmlandFarmer = helper.spawn(ModEntities.SETTLER.get(), farmlandContactRel);
        helper.setBlock(new BlockPos(12, -1, 9), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(12, 0, 9), Blocks.WATER);
        SettlerEntity wetFarmer = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(12, 1, 9));
        helper.runAfterDelay(2, () -> {
            BlockPos crop = helper.absolutePos(cropRel);
            BlockPos water = helper.absolutePos(new BlockPos(7, 0, 8));
            BlockPos farmlandFeet = helper.absolutePos(farmlandContactRel);
            // Vanilla places a body standing on farmland at the fractional top surface.
            farmlandFarmer.moveTo(farmlandFeet.getX() + 0.5D, farmlandFeet.getY() - 0.0625D,
                farmlandFeet.getZ() + 0.5D, farmlandFarmer.getYRot(), farmlandFarmer.getXRot());
            helper.assertTrue(FarmWorkApproach.canContact(level, farmlandFarmer, crop,
                    FarmWorkApproach.Contact.PLANT)
                    && FarmWorkApproach.canDryPlantContact(level, farmlandFarmer, crop),
                "fractional farmland feet must remain a valid dry crop contact");
            helper.assertTrue(FarmWorkApproach.canContact(level, wetFarmer, crop,
                    FarmWorkApproach.Contact.PLANT)
                    && !FarmWorkApproach.canDryPlantContact(level, wetFarmer, crop),
                "a visible crop across water must not authorize a wet field-bag anchor");
            // These are contact probes, not traffic. Both occupy valid final
            // crop stands, so remove them before the one real Farmer begins.
            farmlandFarmer.discard();
            wetFarmer.discard();
            // A raw navigation call has no MOVE owner, leaving BoundedStroll
            // free to replace it. This test goal owns only that flag while the
            // production FarmWorkApproach supplies the actual path and move.
            farmer.goalSelector.addGoal(0, new Goal() {
                { setFlags(EnumSet.of(Flag.MOVE)); }
                @Override public boolean canUse() { return true; }
                @Override public boolean canContinueToUse() { return true; }
                @Override public void start() {
                    float waterCost = farmer.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER);
                    helper.assertTrue(FarmWorkApproach.moveToDryPlantContact(level, farmer, null, crop, 1.0D),
                        "the real Farmer-only route must start a dry water detour");
                    helper.assertTrue(farmer.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER) == waterCost,
                        "field planning must restore the worker's ordinary navigation policy");
                    var route = farmer.getNavigation().getPath();
                    helper.assertTrue(route != null && route.canReach(),
                        "dry Farmer route must be complete before it can approach the crop");
                    for (int node = 0; node < route.getNodeCount(); node++) {
                        BlockPos feet = route.getNode(node).asBlockPos();
                        helper.assertTrue(level.getFluidState(feet).isEmpty()
                                && level.getFluidState(feet.below()).isEmpty(),
                            "dry Farmer route must reject the water candidate [water=" + water + ", node=" + feet + "]");
                    }
                }
            });
            helper.succeedWhen(() -> helper.assertTrue(
                FarmWorkApproach.canDryPlantContact(level, farmer, crop),
                "Farmer must reach a dry actual crop contact by detouring around water"
                    + " [position=" + farmer.position() + ", water=" + farmer.isInWater()
                    + ", navigationDone=" + farmer.getNavigation().isDone()
                    + ", target=" + (farmer.getNavigation().getPath() == null ? "none"
                        : farmer.getNavigation().getPath().getTarget()) + "]"));
        });
    }

    private void runLifecycle(GameTestHelper helper, boolean driftAndReload, boolean interruptPastLease) {
        var level=helper.getLevel();level.setDayTime(2000);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE_BRICKS);
            for(int y=1;y<=4;y++)helper.setBlock(new BlockPos(x,y,z),
                (x==0||x==15||z==0||z==15)&&y<4?Blocks.STONE_BRICKS:Blocks.AIR);
        }
        var data=SettlementSavedData.get(level);
        Settlement owner=new Settlement(UUID.randomUUID(),"Farmer bag QA",helper.absolutePos(new BlockPos(8,1,8)));
        owner.radius=6;data.settlements.put(owner.id,owner);data.setDirty();
        Building farm=GameTestFixtures.register(helper,owner,BuildingType.FARMHOUSE,8,8);
        BlockPos chestPos=helper.absolutePos(new BlockPos(10,1,10));helper.setBlock(new BlockPos(10,1,10),Blocks.CHEST);
        Container chest=(Container)level.getBlockEntity(chestPos);
        helper.assertTrue(chest!=null,"Real Farmhouse chest");
        for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,new ItemStack(Items.STONE,64));
        BlockPos crop=new BlockPos(9,1,8);
        helper.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
        helper.setBlock(crop,((CropBlock)Blocks.WHEAT).getStateForAge(CropBlock.MAX_AGE));
        if (driftAndReload) {
            var zone = com.hearthstead.settlement.workzone.WorkZone.between(owner.id, farm.id,
                com.hearthstead.settlement.workzone.WorkZone.Type.FARM, level.dimension().location(),
                helper.absolutePos(crop.below()), helper.absolutePos(crop.above()), 2);
            helper.assertTrue(farm.commitWorkZone(1, zone), "Exact one-column field for physical drift regression");
        }
        SettlerEntity farmer=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(8,1,8));
        farmer.bindTo(owner.id,owner.center);owner.putRecord(farmer.getUUID(),"Bag farmer",Profession.NONE);
        helper.assertTrue(Employment.hire(level,owner,farm,farmer).ok(),"Actual Farmer employment");
        farmer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_HOE));
        farmer.attributes().pinForTest(Attribute.DEXTERITY,10);
        SettlerEntity[] active={farmer};
        boolean[] flags=new boolean[interruptPastLease?9:7]; // floor, world, hand/reload, bag, full, commit/reload, lift, lease expiry, resumed
        long[] blockedAt={-1}, preemptedAt={-1}; UUID[] preemptedDrop={null}, preemptedAction={null};
        int[] previousBag={0},previousChest={0};
        SettlerEntity foreign=null;
        if(interruptPastLease) {
            foreign=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(13,1,13));
            foreign.bindTo(owner.id,owner.center);owner.putRecord(foreign.getUUID(),"Foreign farmer",Profession.NONE);
            foreign.setNoAi(true);
        }
        SettlerEntity foreignWorker=foreign;
        var bounds=new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(0,0,0))),
            net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16,8,16))));
        helper.onEachTick(()->{
            SettlerEntity actor=active[0];
            int loose=level.getEntitiesOfClass(ItemEntity.class,bounds,e->e.isAlive()&&e.getItem().is(Items.WHEAT))
                .stream().mapToInt(e->e.getItem().getCount()).sum();
            int hand=actor.getOffhandItem().is(Items.WHEAT)?actor.getOffhandItem().getCount():0;
            int bag=count(actor.bag),stored=count(chest);
            if(actor.placedWorkContainerPos()!=null&&!actor.bagTransferPresentation().active())flags[0]=true;
            if(loose==1&&!flags[1]) {
                helper.assertTrue(flags[0]&&bag==0&&hand==0&&stored==0,"Harvest creates one physical drop after actual sack placement, never an instant bag copy");
                flags[1]=true;
                if(interruptPastLease) {
                    ItemEntity output=level.getEntitiesOfClass(ItemEntity.class,bounds,
                        e -> e.isAlive() && e.getItem().is(Items.WHEAT)).getFirst();
                    preemptedDrop[0]=output.getUUID(); preemptedAt[0]=level.getGameTime();
                    preemptedAction[0]=com.hearthstead.settlement.work.WorkerStackProvenance
                        .readTransit(output.getItem()).orElseThrow().actionId();
                    // Simulate a higher-priority interruption without touching
                    // cargo, drops, receipts or the finite public lease.
                    actor.setNoAi(true);
                }
                if (driftAndReload) {
                    ItemEntity output = level.getEntitiesOfClass(ItemEntity.class,bounds,
                        e -> e.isAlive() && e.getItem().is(Items.WHEAT)).getFirst();
                    // Explicit physics interference on the SAME actual item, never copied cargo.
                    // Eight is the bounded recovery edge; the source crop remains in its exact zone.
                    BlockPos outside = helper.absolutePos(crop.west(8));
                    BlockPos beyondRecovery = outside.west();
                    output.setPos(outside.getX()+0.15D, outside.getY()+0.1D, outside.getZ()+0.5D);
                    output.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    var zone = farm.workZone().orElseThrow();
                    helper.assertTrue(!com.hearthstead.settlement.workzone.WorkZoneService
                            .livePositionAllowed(level, zone, output.blockPosition()),
                        "Output must now be outside the exact crop mutation zone");
                    helper.assertTrue(com.hearthstead.settlement.work.WorkerProvenanceService
                            .farmOutputRecoveryPositionAllowed(level,zone,outside)
                        && com.hearthstead.settlement.work.WorkerProvenanceService
                            .collectableFarmOutput(level,owner,farm,actor,zone,output.getItem(),output.blockPosition()),
                        "The exact authenticated crop remains recoverable at the bounded eight-block edge");
                    helper.assertTrue(com.hearthstead.settlement.workzone.WorkZoneService
                            .livePositionAvailable(level,beyondRecovery)
                        && !com.hearthstead.settlement.work.WorkerProvenanceService
                            .farmOutputRecoveryPositionAllowed(level,zone,beyondRecovery)
                        && !com.hearthstead.settlement.work.WorkerProvenanceService
                            .collectableFarmOutput(level,owner,farm,actor,zone,new ItemStack(Items.WHEAT),outside)
                        && !com.hearthstead.settlement.work.WorkerProvenanceService
                            .collectableFarmOutput(level,owner,farm,actor,zone,output.getItem(),beyondRecovery),
                        "Untagged goods and the loaded ninth block remain outside crop recovery authority");
                    UUID sameDrop = output.getUUID();
                    active[0]=reload(helper,actor);
                    helper.assertTrue(level.getEntity(sameDrop)==output,
                        "Worker reload must retain the original ItemEntity outside the field");
                    return;
                }
            }
            if(flags[1])helper.assertTrue(loose+hand+bag+stored==1,"Wheat must remain exactly once across world, hand, bag and chest");
            if(interruptPastLease && preemptedAt[0]>=0 && !flags[8]) {
                if(level.getGameTime()-preemptedAt[0] <= com.hearthstead.entity.ai.GroundCollectionSession.OWNERSHIP_LEASE_TICKS+10) {
                    return;
                }
                Entity entity=level.getEntity(preemptedDrop[0]);
                helper.assertTrue(entity instanceof ItemEntity,
                    "The original exact harvest entity must survive public lease expiry");
                ItemEntity output=(ItemEntity)entity;
                helper.assertTrue(output.isAlive() && output.getItem().is(Items.WHEAT)
                    && loose==1 && hand==0 && bag==0 && stored==0,
                    "Past the public lease, the original physical crop remains once and is never copied into cargo");
                helper.assertTrue(output.getTarget()==null && !output.hasPickUpDelay(),
                    "The public 100-tick lease must really expire before the Farmer-specific recovery seam runs");
                var zone=farm.workZone().orElseThrow();
                helper.assertTrue(!com.hearthstead.settlement.work.WorkerProvenanceService.collectableFarmOutput(
                    level,owner,farm,foreignWorker,zone,output.getItem(),output.blockPosition()),
                    "A different bound settler cannot adopt the expired Farmer harvest transit");
                flags[7]=true;
                actor.setNoAi(false); flags[8]=true;
            }
            if(hand==1&&!flags[2]) {
                helper.assertTrue(bag==0&&actor.placedWorkContainerPos()!=null,"Actual hand owns pickup while sack stays grounded");
                flags[2]=true;active[0]=reload(helper,actor);return;
            }
            if(bag>previousBag[0]) {
                var state=actor.getPersistentData().getCompound("HearthsteadGroundedFieldBag");
                helper.assertTrue(flags[2]&&state.getString("Phase").equals("STOW")&&state.getInt("Clock")==12
                    &&actor.placedWorkContainerPos()!=null,"Actual bag entry occurs only at grounded stow contact12");
                flags[3]=true;
            }
            var view=actor.bagTransferPresentation();
            if(view.active()&&view.clock()==47&&!flags[4]) {
                if(blockedAt[0]<0)blockedAt[0]=level.getGameTime();
                helper.assertTrue(stored==0&&bag==1&&!view.committed(),"Full target cannot consume the presented crop before48");
                if(level.getGameTime()-blockedAt[0]>=10) {
                    // Explicit fixture interference: free the already-inspected full storage.
                    for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,ItemStack.EMPTY);
                    flags[4]=true;
                }
            }
            if(stored>previousChest[0]) {
                helper.assertTrue(flags[4]&&view.active()&&view.clock()==48&&view.committed(),"Actual chest insert shares exact visible contact48");
                if(!flags[5]) {flags[5]=true;previousChest[0]=stored;previousBag[0]=bag;active[0]=reload(helper,actor);return;}
            }
            if(flags[5]&&!view.active()&&actor.placedWorkContainerPos()==null&&count(chest)==1)flags[6]=true;
            previousBag[0]=bag;previousChest[0]=stored;
            if(flags[6]) {
                if(interruptPastLease) {
                    var action=com.hearthstead.settlement.work.WorkerProvenanceSavedData.get(level)
                        .action(preemptedAction[0]);
                    helper.assertTrue(action!=null
                        && action.remaining(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.WHEAT))==0
                        && action.remaining(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.WHEAT_SEEDS))==0,
                        "The interrupted harvest's exact wheat and seed receipt rows must both be resolved by physical delivery");
                }
                for(boolean flag:flags)helper.assertTrue(flag,"Every physical field/chest lifecycle witness is required");
                data.settlements.remove(owner.id);data.setDirty();helper.succeed();
            }
            // The outer framework timeout reports no state. Ten ticks before it,
            // expose the one failed physical phase without altering success,
            // ownership, storage capacity or the lifecycle clock.
            if (!flags[6] && helper.getTick() >= 1390) {
                var transfer = actor.bagTransferPresentation();
                helper.assertTrue(false, "farmer bag lifecycle phase witness; flags="
                    + java.util.Arrays.toString(flags)
                    + ", testTick=" + helper.getTick()
                    + ", worldTick=" + level.getGameTime()
                    + ", driftAndReload=" + driftAndReload + ", interruptPastLease=" + interruptPastLease
                    + ", preemptedAt=" + preemptedAt[0] + ", preemptedDrop=" + preemptedDrop[0]
                    + ", loose=" + loose + ", hand=" + hand + ", bag=" + bag
                    + ", wheatChest=" + stored
                    + ", occupiedChestSlots=" + java.util.stream.IntStream
                        .range(0, chest.getContainerSize())
                        .filter(slot -> !chest.getItem(slot).isEmpty()).count()
                    + ", pos=" + actor.position()
                    + ", activity=" + actor.getActivity()
                    + ", navDone=" + actor.getNavigation().isDone()
                    + ", navTarget=" + actor.getNavigation().getTargetPos()
                    + ", contact=" + com.hearthstead.settlement.work.ContainerApproach
                        .inspect(level, actor, chestPos).state()
                    + ", transfer={active=" + transfer.active()
                    + ", clock=" + transfer.clock()
                    + ", committed=" + transfer.committed()
                    + ", anchor=" + transfer.bagAnchor()
                    + ", target=" + transfer.containerPos()
                    + ", item=" + transfer.item() + "}"
                    + ", placed=" + actor.placedWorkContainerPos()
                    + ":" + actor.placedWorkContainerKind()
                    + ", fieldBag=" + actor.getPersistentData()
                        .getCompound("HearthsteadGroundedFieldBag")
                    + ", route=" + actor.routeFailureNote());
            }
        });
    }
    /**
     * A partially-full Farmer bag must split one real source stack at PICK,
     * then retain that exact source UUID for the remaining unit. Removing the
     * known remaining entity exercises safe expected-row pruning: it must end
     * the field session without depositing a fabricated replacement.
     */
    @GameTest(template="empty16",timeoutTicks=500,batch="farmer_bag_lifecycle")
    public void partialFarmerBagKeepsSourceUuidThenGoneSourceFinishesWithoutMint(GameTestHelper helper) {
        var level=helper.getLevel(); level.setDayTime(2000);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)helper.setBlock(new BlockPos(x,0,z),Blocks.STONE_BRICKS);
        var data=SettlementSavedData.get(level);
        Settlement owner=new Settlement(UUID.randomUUID(),"Partial bag QA",helper.absolutePos(new BlockPos(8,1,8)));
        owner.radius=6; data.settlements.put(owner.id,owner); data.setDirty();
        // Keep the real Farmhouse bounds around the Farmer, source and chest,
        // while placing the required fixture plaque/support away from this
        // manual contact rig.
        Building farm=GameTestFixtures.registerWithBounds(helper,owner,
            BuildingType.FARMHOUSE,new BlockPos(8,1,8),new BlockPos(10,2,11),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(7,1,7)),helper.absolutePos(new BlockPos(12,3,12))));
        SettlerEntity farmer=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(8,1,8));
        farmer.bindTo(owner.id,owner.center); owner.putRecord(farmer.getUUID(),"Partial bag farmer",Profession.NONE);
        helper.assertTrue(Employment.hire(level,owner,farm,farmer).ok(),"Actual Farmer employment for grounded bag route");
        farmer.setNoAi(true); farmer.setCarryCapacity(1);
        // This manual session has no navigation owner: begin from the actual
        // sack anchor and prevent incidental locomotion from changing contact.
        BlockPos anchor=helper.absolutePos(new BlockPos(8,1,8));
        farmer.moveTo(anchor.getX()+0.5D,anchor.getY(),anchor.getZ()+0.5D,
            farmer.getYRot(),farmer.getXRot());
        farmer.getNavigation().stop();
        BlockPos sourceRel=new BlockPos(8,1,9);
        // The same fully loaded local cells are used under every GameTest
        // rotation. Explicit headroom prevents collision ejection before PICK.
        helper.setBlock(new BlockPos(8,1,8),Blocks.AIR);
        helper.setBlock(new BlockPos(8,2,8),Blocks.AIR);
        helper.setBlock(sourceRel,Blocks.AIR);
        helper.setBlock(sourceRel.above(),Blocks.AIR);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(8,1,8))).isAir()
                && helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(8,2,8))).isAir()
                && helper.getLevel().getBlockState(helper.absolutePos(sourceRel)).isAir()
                && helper.getLevel().getBlockState(helper.absolutePos(sourceRel.above())).isAir(),
            "Manual Farmer anchor and exact source have clear physical headroom before source materialization");
        BlockPos source=helper.absolutePos(sourceRel);
        // Keep the chest out of the source pickup ray; it remains one real
        // adjacent Farmhouse container for the later unload contact.
        BlockPos chestPos=helper.absolutePos(new BlockPos(7,1,8));
        helper.setBlock(new BlockPos(7,1,8),Blocks.CHEST);
        Container chest=(Container)level.getBlockEntity(chestPos);
        helper.assertTrue(chest!=null,"Real nearby Farmhouse chest for physical unload");
        helper.assertTrue(com.hearthstead.settlement.work.ContainerApproach
                .inspect(level,farmer,chestPos).canInteract(),
            "Fixture Farmer begins at a clear physical chest contact for the later unload");
        var collection=new com.hearthstead.entity.ai.GroundCollectionSession(farmer,
            stack->stack.is(Items.WHEAT_SEEDS),4);
        helper.assertTrue(collection.spawnPhysical(level,source,new ItemStack(Items.WHEAT_SEEDS,2)),
            "Owned test source is one physical two-seed stack");
        ItemEntity original=collection.nearestLoaded(level,farmer.blockPosition());
        helper.assertTrue(original!=null&&original.getItem().getCount()==2,"Resolve exact physical source before bag session");
        // The subject is the same owned ItemEntity. Suppress constructor
        // velocity only so the manual, NoAI contact fixture cannot drift it
        // before the real PICK clock reaches its contact frame.
        original.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        UUID sourceId=original.getUUID();
        var fieldBag=new com.hearthstead.entity.ai.GroundedBagSession(farmer,collection,Integer.MAX_VALUE);
        fieldBag.begin(source,"minecraft:wheat"); fieldBag.markHarvested();
        fieldBag.recordExpectedDrops(level,java.util.List.of(sourceId),java.util.List.of(new ItemStack(Items.WHEAT_SEEDS,2)));
        fieldBag.collectAfterHarvest();
        var unload=new com.hearthstead.entity.ai.GroundedBagUnload();
        boolean[] unloading={false}, firstUnload={false}, discarded={false}; long[] discardedAt={-1};
        helper.onEachTick(()->{
            if(unloading[0]) {
                var result=unload.tick(level,farmer,chestPos,stack->stack.is(Items.WHEAT_SEEDS),
                    unit->insertOne(chest,unit));
                if(result==com.hearthstead.entity.ai.GroundedBagUnload.Result.BLOCKED)
                    helper.assertTrue(false,"Actual grounded bag-to-chest contact must remain valid");
                if(result==com.hearthstead.entity.ai.GroundedBagUnload.Result.COMPLETE) {
                    helper.assertTrue(!firstUnload[0],"One-unit capacity permits exactly one first unload before source-loss branch");
                    ItemEntity remainder=level.getEntity(sourceId) instanceof ItemEntity item?item:null;
                    helper.assertTrue(remainder!=null&&remainder.isAlive()&&remainder.getItem().is(Items.WHEAT_SEEDS)
                        &&remainder.getItem().getCount()==1&&countSeeds(farmer.bag)==0&&countSeeds(chest)==1,
                        "PICK split keeps the original UUID as one physical seed while exactly one seed crossed sack and chest contacts");
                    firstUnload[0]=true; unloading[0]=false;
                    remainder.discard(); discarded[0]=true; discardedAt[0]=level.getGameTime(); // known deleted source: no replacement is permitted
                    fieldBag.returnFromStorage();
                }
                return;
            }
            var result=fieldBag.tick(level,item->item.getUUID().equals(sourceId),farm);
            if(result==com.hearthstead.entity.ai.GroundedBagSession.Result.NEEDS_UNLOAD) { unloading[0]=true; return; }
            if(result==com.hearthstead.entity.ai.GroundedBagSession.Result.BLOCKED) {
                if(!discarded[0]) helper.assertTrue(false,
                    "Exact partial source remains routable until the deliberate source-loss witness; "
                    + fieldBag.diagnostic(level));
                helper.assertTrue(level.getGameTime()-discardedAt[0]
                    <= com.hearthstead.entity.ai.GroundCollectionSession.OWNERSHIP_REFRESH_TICKS,
                    "A known-gone source may wait only for the existing bounded collection-index refresh");
                return;
            }
            if(result==com.hearthstead.entity.ai.GroundedBagSession.Result.COLLECTED&&discarded[0]) {
                helper.assertTrue(firstUnload[0]&&level.getEntity(sourceId)==null&&countSeeds(farmer.bag)==0&&countSeeds(chest)==1,
                    "Known gone source clears only field intent: no seed, bag cargo, or deposit is fabricated");
                data.settlements.remove(owner.id); data.setDirty(); helper.succeed();
            }
        });
    }

    private static boolean insertOne(Container target,ItemStack unit) {
        for(int slot=0;slot<target.getContainerSize();slot++) {
            ItemStack live=target.getItem(slot);
            if(live.isEmpty()) { target.setItem(slot,unit.copy()); return true; }
            if(ItemStack.isSameItemSameComponents(live,unit)&&live.getCount()<live.getMaxStackSize()) {
                live.grow(1); target.setItem(slot,live); return true;
            }
        }
        return false;
    }
    private static int countSeeds(Container inventory) {
        int result=0;for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(Items.WHEAT_SEEDS))result+=inventory.getItem(i).getCount();return result;
    }

    private static int count(Container inventory) {
        int result=0;for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(Items.WHEAT))result+=inventory.getItem(i).getCount();return result;
    }
    private static SettlerEntity reload(GameTestHelper helper,SettlerEntity original) {
        UUID id=original.getUUID();CompoundTag saved=original.saveWithoutId(new CompoundTag());
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SettlerEntity replacement=ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(replacement!=null,"Real replacement exists");replacement.load(saved);
        helper.assertTrue(id.equals(replacement.getUUID())&&helper.getLevel().addFreshEntity(replacement),"Full NBT restores the same actual worker");return replacement;
    }
}
