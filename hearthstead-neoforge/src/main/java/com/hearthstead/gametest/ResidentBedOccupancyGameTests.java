package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ResidentBedOccupancyGameTests {
    @GameTest(template="empty16",timeoutTicks=80,batch="resident_bed_ownership")
    public void unloadedOwnerReservesBedAcrossBothAllocatorsAndSavedReload(GameTestHelper helper) {
        var level=helper.getLevel();
        var data=SettlementSavedData.get(level);
        BlockPos center=helper.absolutePos(new BlockPos(3,1,3));
        Settlement owner=new Settlement(UUID.randomUUID(),"Bed ownership QA",center);
        data.settlements.put(owner.id,owner);
        SettlerEntity first=null,second=null;
        try {
            for(int x=1;x<=10;x++) for(int z=1;z<=10;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
            BlockPos foot=helper.absolutePos(new BlockPos(6,1,6)),head=foot.south();
            var bed=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH);
            level.setBlockAndUpdate(foot,bed.setValue(BedBlock.PART,BedPart.FOOT));
            level.setBlockAndUpdate(head,bed.setValue(BedBlock.PART,BedPart.HEAD));
            Building home=new Building(UUID.randomUUID(),BuildingType.HOUSE,center,head,
                BoundingBox.fromCorners(center,helper.absolutePos(new BlockPos(10,4,10))));
            GameTestFixtures.placePlaque(helper, new BlockPos(3,1,3));
            home.valid=true; home.beds.add(head); owner.buildings.add(home);
            first=resident(helper,owner,4,"Bed owner A");
            second=resident(helper,owner,8,"Bed candidate B");
            first.claimBed(head); second.claimBed(null);
            helper.assertTrue(head.equals(owner.record(first.getUUID()).bedClaim.head()),"Real claim must persist the exact owner bed");
            CompoundTag entitySave=first.saveWithoutId(new CompoundTag());
            UUID firstId=first.getUUID();
            SettlerEntity waiting=second;
            first.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.runAfterDelay(2,()->{
                SettlerEntity restored=null;
                try {
                    helper.assertTrue(level.getEntity(firstId)==null,"Original owner must actually be absent from loaded entities");
                    assertReserved(helper,owner,home,waiting,head);
                    Settlement loaded=Settlement.readNbt(owner.writeNbt());
                    data.settlements.put(owner.id,loaded);
                    Building loadedHome=loaded.buildings.stream().filter(b->b.id.equals(home.id)).findFirst().orElseThrow();
                    helper.assertTrue(head.equals(loaded.record(firstId).bedClaim.head()),"Settlement serialization must retain unloaded owner claim");
                    assertReserved(helper,loaded,loadedHome,waiting,head);
                    loaded.record(firstId).bedClaim=ResidentBedClaim.unknown();
                    helper.assertTrue(BuildingManager.findFreeBed(level,loaded)==null,"Unknown unloaded legacy ownership must not become free");
                    data.buildingManager.assignFreeBeds(level,loaded,loadedHome);
                    helper.assertTrue(waiting.getClaimedBed()==null,"Push assignment must refuse unknown legacy occupancy");
                    restored=ModEntities.SETTLER.get().create(level);
                    helper.assertTrue(restored!=null,"Owner entity can be restored");
                    restored.load(entitySave);
                    helper.assertTrue(level.addFreshEntity(restored),"Actual same-UUID entity reload must succeed");
                    helper.assertTrue(firstId.equals(restored.getUUID()) && head.equals(restored.getClaimedBed()),"Reload restores the real original owner");
                    helper.assertTrue(loaded.record(firstId).bedClaim.known(),"Normal entity join reconciles unknown legacy projection");
                    assertReserved(helper,loaded,loadedHome,waiting,head);
                    // Stale saved metadata may never override the loaded actual actor's release.
                    restored.releaseBed();
                    helper.assertTrue(head.equals(BuildingManager.findFreeBed(level,loaded)),"Only actual release makes the bed available");
                    // A now leaves the settlement; B is the sole remaining eligible resident.
                    restored.unbind(); loaded.removeRecord(firstId);
                    data.buildingManager.assignFreeBeds(level,loaded,loadedHome);
                    helper.assertTrue(head.equals(waiting.getClaimedBed()) && !head.equals(restored.getClaimedBed()),"Push assignment gives released bed to B without a duplicate owner");
                    helper.assertTrue(ResidentBedOccupancy.read(level,loaded).count(head)==1,"Exactly one canonical owner remains");
                    helper.succeed();
                } finally {
                    if(restored!=null) restored.discard();
                    waiting.discard();
                    data.settlements.remove(owner.id); data.setDirty();
                }
            });
        } catch(RuntimeException | Error failure) {
            if(first!=null) first.discard(); if(second!=null) second.discard();
            data.settlements.remove(owner.id); data.setDirty(); throw failure;
        }
    }
    private static void assertReserved(GameTestHelper helper,Settlement owner,Building home,
                                       SettlerEntity waiting,BlockPos head) {
        var level=helper.getLevel();
        helper.assertTrue(ResidentBedOccupancy.read(level,owner).count(head)==1,"Unloaded original owns exactly this bed");
        helper.assertTrue(BuildingManager.findFreeBed(level,owner)==null,"Pull allocator must not reuse unloaded owner's bed");
        SettlementSavedData.get(level).buildingManager.assignFreeBeds(level,owner,home);
        helper.assertTrue(waiting.getClaimedBed()==null,"Push allocator must not duplicate unloaded owner's bed");
    }
    private static SettlerEntity resident(GameTestHelper helper,Settlement owner,int x,String name) {
        var actor=ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(actor!=null,"Create actual resident");
        var position=helper.absolutePos(new BlockPos(x,1,4));
        actor.moveTo(position.getX()+.5,position.getY(),position.getZ()+.5,0,0);
        actor.setNoAi(true); actor.setSettlerName(name); actor.bindTo(owner.id,owner.center);
        owner.putRecord(actor.getUUID(),name,Profession.NONE);
        helper.assertTrue(helper.getLevel().addFreshEntity(actor),"Publish actual resident");
        return actor;
    }
}
