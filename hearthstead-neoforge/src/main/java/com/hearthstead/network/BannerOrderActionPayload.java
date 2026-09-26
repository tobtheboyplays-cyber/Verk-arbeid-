package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Actions carry no client-authored world coordinates. */
public record BannerOrderActionPayload(int action, UUID token, UUID settler) implements CustomPacketPayload {
    public static final int OPEN=0, ASSIGN=1, MOVE=2, HOLD=3, ATTACK=4, FOLLOW=5, TAKEOVER=6;
    public static final UUID NONE=new UUID(0,0);
    public static final Type<BannerOrderActionPayload> TYPE=new Type<>(Hearthstead.id("banner_order_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BannerOrderActionPayload> CODEC=StreamCodec.of(
        (buf,p)->{buf.writeVarInt(p.action);buf.writeUUID(p.token);buf.writeUUID(p.settler);},
        buf->new BannerOrderActionPayload(buf.readVarInt(),buf.readUUID(),buf.readUUID()));
    public static BannerOrderActionPayload open(UUID request) { return new BannerOrderActionPayload(OPEN,request,NONE); }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
