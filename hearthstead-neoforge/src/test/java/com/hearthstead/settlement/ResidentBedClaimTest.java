package com.hearthstead.settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResidentBedClaimTest {
    @Test void savedAssignedAndReleasedClaimsRemainDistinctFromLegacyUnknown() {
        var bed=new BlockPos(11,64,-7);
        var assigned=ResidentBedClaim.known(bed);
        assertEquals(assigned,ResidentBedClaim.readNbt(assigned.writeNbt()));
        var released=ResidentBedClaim.known(null);
        assertEquals(released,ResidentBedClaim.readNbt(released.writeNbt()));
        assertFalse(ResidentBedClaim.readNbt(null).known());
        assertFalse(ResidentBedClaim.readNbt(new CompoundTag()).known());
        assertNotEquals(released,ResidentBedClaim.unknown());
    }
    @Test void corruptOrFutureProjectionNeverInventsVacancy() {
        CompoundTag tag=ResidentBedClaim.known(new BlockPos(1,2,3)).writeNbt();
        tag.putString("Head","not a packed position");
        assertFalse(ResidentBedClaim.readNbt(tag).known());
        tag=ResidentBedClaim.known(null).writeNbt(); tag.putByte("Known",(byte)2);
        assertFalse(ResidentBedClaim.readNbt(tag).known());
        tag=ResidentBedClaim.known(null).writeNbt(); tag.putByte("Version",(byte)2);
        assertFalse(ResidentBedClaim.readNbt(tag).known());
    }
}
