package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.BedMarkersPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Bounded, read-only visual projection; never loads chunks or assigns a bed. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BedMarkerService {
    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime()%20 != 0) return;
        for (ServerPlayer player : level.players()) send(level,player);
    }
    private static void send(ServerLevel level, ServerPlayer player) {
        // The projection is optional until this connection has negotiated its
        // client channel. Never send into login or a server-only connection.
        if (player.connection == null
            || !net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
                player.connection, BedMarkersPayload.TYPE.id())) return;
        var positions = new HashSet<BlockPos>();
        int cx=player.blockPosition().getX()>>4, cz=player.blockPosition().getZ()>>4;
        for (int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            var chunk=level.getChunkSource().getChunkNow(cx+dx,cz+dz);
            if(chunk==null) continue;
            for(var blockEntity:chunk.getBlockEntities().values()) {
                if(!(blockEntity instanceof BedBlockEntity)) continue;
                BlockPos pos=blockEntity.getBlockPos();
                if(pos.distSqr(player.blockPosition())>32*32) continue;
                var state=level.getBlockState(pos);
                if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==BedPart.HEAD)
                    positions.add(pos.immutable());
            }
        }
        List<BlockPos> nearest=positions.stream().sorted(Comparator.comparingDouble(p->p.distSqr(player.blockPosition())))
            .limit(BedMarkersPayload.MAX_BEDS).toList();
        var result=new ArrayList<BedMarkersPayload.Entry>();
        for(BlockPos head:nearest) {
            Byte status=status(level,head);
            if(status!=null) result.add(new BedMarkersPayload.Entry(head,status));
        }
        com.hearthstead.network.PayloadSend.toPlayer(player,new BedMarkersPayload(level.dimension().location(),result));
    }
    /** null means unresolved legacy ownership, never a false free-bed claim. */
    public static Byte status(ServerLevel level,BlockPos head) {
        var state=level.getBlockState(head);
        if(!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART)!=BedPart.HEAD)
            return BedMarkersPayload.INVALID;
        BlockPos foot=head.relative(state.getValue(BedBlock.FACING).getOpposite());
        if(!level.isLoaded(foot)) return null;
        var other=level.getBlockState(foot);
        if(other.getBlock()!=state.getBlock() || other.getValue(BedBlock.PART)!=BedPart.FOOT
            || other.getValue(BedBlock.FACING)!=state.getValue(BedBlock.FACING)) return BedMarkersPayload.INVALID;
        Settlement owner=null; boolean unobservedBuilding=false;
        for(Settlement settlement:SettlementSavedData.get(level).settlements.values()) {
            for(Building building:settlement.buildings) {
                if(!building.beds.contains(head)) continue;
                if(!level.isLoaded(building.plaquePos)) { unobservedBuilding=true; continue; }
                if(!building.valid) continue;
                if(!(level.getBlockEntity(building.plaquePos) instanceof PlaqueBlockEntity plaque)
                    || plaque.state()!=PlaqueState.LINKED_VALID || !building.id.equals(plaque.buildingId())) continue;
                if(owner!=null && owner!=settlement) return null;
                owner=settlement;
            }
        }
        if(owner==null) return unobservedBuilding ? null : BedMarkersPayload.INVALID;
        ResidentBedOccupancy occupancy=ResidentBedOccupancy.read(level,owner);
        int claims=occupancy.count(head);
        if(claims>1) return BedMarkersPayload.INVALID;
        if(claims==1) return BedMarkersPayload.ASSIGNED;
        return occupancy.available(head) ? BedMarkersPayload.FREE : null;
    }
}
