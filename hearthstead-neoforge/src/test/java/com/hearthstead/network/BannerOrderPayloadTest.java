package com.hearthstead.network;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BannerOrderPayloadTest {
    private RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);}
    @Test void skyMenuAndEnemyMenuPreserveTargetAndCommanderIdentity(){
        for(var input:new BannerOrderMenuPayload[]{
            new BannerOrderMenuPayload(UUID.randomUUID(),11,5,true,"",false,BlockPos.ZERO,""),
            new BannerOrderMenuPayload(UUID.randomUUID(),14,3,false,"Tobias",true,new BlockPos(-500,71,9),"Raider")}){
            var buf=buffer();
            try{BannerOrderMenuPayload.CODEC.encode(buf,input);assertEquals(input,BannerOrderMenuPayload.CODEC.decode(buf));assertEquals(0,buf.readableBytes());}
            finally{buf.release();}
        }
    }
    @Test void commandPreservesSessionAndAssignmentUuidWithoutCoordinates(){
        var input=new BannerOrderActionPayload(BannerOrderActionPayload.ASSIGN,UUID.randomUUID(),UUID.randomUUID());
        var buf=buffer();
        try{BannerOrderActionPayload.CODEC.encode(buf,input);assertEquals(input,BannerOrderActionPayload.CODEC.decode(buf));assertEquals(0,buf.readableBytes());}
        finally{buf.release();}
    }
    @Test void malformedMenuColorAndOversizedRosterAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new BannerOrderMenuPayload(UUID.randomUUID(),16,2,true,"",false,BlockPos.ZERO,""));
        assertThrows(IllegalArgumentException.class,()->new BannerOrderMenuPayload(UUID.randomUUID(),1,4097,true,"",false,BlockPos.ZERO,""));
        assertThrows(IllegalArgumentException.class,()->new BannerOrderMenuPayload(UUID.randomUUID(),1,2,true,"x".repeat(81),false,BlockPos.ZERO,""));
    }
}
