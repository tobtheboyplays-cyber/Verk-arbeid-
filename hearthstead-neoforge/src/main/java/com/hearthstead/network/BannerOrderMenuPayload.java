package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Bounded, server-selected target and command ownership for one menu. */
public record BannerOrderMenuPayload(UUID token,int color,int members,boolean canCommand,
        String leader,boolean ground,BlockPos point,String enemy) implements CustomPacketPayload {
    public BannerOrderMenuPayload {
        if(token==null||color<0||color>15||members<0||members>4096||leader==null||leader.length()>80
            ||point==null||enemy==null||enemy.length()>80)throw new IllegalArgumentException("Invalid banner menu");
    }
    public static final Type<BannerOrderMenuPayload> TYPE=new Type<>(Hearthstead.id("banner_order_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BannerOrderMenuPayload> CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.token);b.writeVarInt(p.color);b.writeVarInt(p.members);b.writeBoolean(p.canCommand);
            b.writeUtf(p.leader,80);b.writeBoolean(p.ground);b.writeBlockPos(p.point);b.writeUtf(p.enemy,80);},
        b->new BannerOrderMenuPayload(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readBoolean(),
            b.readUtf(80),b.readBoolean(),b.readBlockPos(),b.readUtf(80)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
