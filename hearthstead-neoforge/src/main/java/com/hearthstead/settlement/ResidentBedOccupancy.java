package com.hearthstead.settlement;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Profession;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Shared allocator/display view: an unloaded resident does not vacate their bed. */
public record ResidentBedOccupancy(Map<BlockPos,Integer> claims, boolean unresolved) {
    public ResidentBedOccupancy { claims=Map.copyOf(claims); }
    public int count(BlockPos head) { return claims.getOrDefault(head,0); }
    public boolean available(BlockPos head) { return !unresolved && count(head)==0; }
    public static ResidentBedOccupancy read(ServerLevel level, Settlement owner) {
        var claims=new HashMap<BlockPos,Integer>();
        boolean unknown=false;
        for(Settlement.SettlerRecord record:owner.settlers) {
            ResidentBedClaim claim=record.bedClaim;
            // Loaded physical state overrides any saved projection, including unknown legacy data.
            if(level.getEntity(record.entityId) instanceof SettlerEntity actor) {
                if(!actor.isAlive() || !owner.id.equals(actor.getSettlementId())) continue;
                claim=ResidentBedClaim.known(actor.getClaimedBed());
            }
            if(!claim.known()) unknown=true;
            else if(claim.head()!=null) claims.merge(claim.head(),1,Integer::sum);
        }
        return new ResidentBedOccupancy(claims,unknown);
    }
}
