package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Packet-level regression for a quiet Hearth after the earned-offer cue. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class HearthBlessingIdleGameTests {

    private static final int OBSERVATION_TICKS = 200;
    private static final String PARTICLE_PROBE =
        "hearthstead_blessing_idle_particle_probe";

    @GameTest(template = "empty5", timeoutTicks = 240,
        batch = "blessing_offer_has_one_cue_and_no_idle_particle_heartbeat")
    public void earnedOfferHasOneCueAndNoIdleParticleHeartbeat(
            GameTestHelper helper) {
        BlockPos hearthRelative = new BlockPos(2, 1, 2);
        BlockPos hearthPos = helper.absolutePos(hearthRelative);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = helper.getBlockEntity(hearthRelative);

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Quietstead",
            hearthPos);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        helper.assertTrue(settlement.blessingState.grantOffer(),
            "fixture must own one selectable Blessing offer");

        ServerPlayer observer = helper.makeMockServerPlayerInLevel();
        observer.setPos(hearthPos.getX() + 0.5D, hearthPos.getY() + 1.0D,
            hearthPos.getZ() + 0.5D);
        EmbeddedChannel channel = (EmbeddedChannel) observer.connection
            .getConnection().channel();
        channel.runPendingTasks();
        drainOutbound(channel);

        AtomicInteger particlePackets = new AtomicInteger();
        channel.pipeline().addLast(PARTICLE_PROBE,
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundLevelParticlesPacket) {
                        particlePackets.incrementAndGet();
                    }
                    super.write(context, message, promise);
                }
            });

        // The earned transition keeps its authored one-shot world cue.
        BlessingPresentation.offerEarned(helper.getLevel(), settlement);
        channel.runPendingTasks();
        drainOutbound(channel);
        helper.assertTrue(particlePackets.get() == 1,
            "offerEarned must send exactly one particle packet to a nearby observer");
        particlePackets.set(0);

        // Let the real placed block entity tick for ten seconds. The old idle
        // path emitted one packet every 20 ticks and therefore fails this exact
        // packet-level assertion ten times over.
        helper.runAfterDelay(OBSERVATION_TICKS, () -> {
            channel.runPendingTasks();
            drainOutbound(channel);
            helper.assertTrue(particlePackets.get() == 0,
                "a pending Blessing offer must send zero idle particle packets "
                    + "across 200 Hearth ticks");
            channel.pipeline().remove(PARTICLE_PROBE);
            helper.succeed();
        });
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(packet);
        }
    }
}
