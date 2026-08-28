package com.hearthstead.network;

import com.hearthstead.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C: open the settler inspection card for the given entity. */
public class OpenSettlerScreenPacket {
    private final int entityId;

    public OpenSettlerScreenPacket(int entityId) {
        this.entityId = entityId;
    }

    public OpenSettlerScreenPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHooks.openSettlerScreen(entityId)));
        ctx.get().setPacketHandled(true);
    }
}
