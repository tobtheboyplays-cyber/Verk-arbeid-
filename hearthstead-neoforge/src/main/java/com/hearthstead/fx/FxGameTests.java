package com.hearthstead.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FxPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.GoodsQuality;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * FX lane, batch {@code fx_}: a server moment sends exactly ONE small
 * {@link FxPayload} per nearby player that negotiated the channel, never a
 * payload to a player without it (mock/vanilla connection), and nothing to a
 * player out of range. The client draws every particle; the server never
 * sends particle packets for these moments.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FxGameTests {

    @GameTest(template = "empty5", batch = "fx_one_payload_per_moment")
    public void oneMomentIsOnePayloadPerCapablePlayer(GameTestHelper helper) {
        FxSend.resetForTests();
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        ServerPlayer capable = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(capable.connection.getConnection());
        capable.setPos(settler.getX() + 2.0D, settler.getY(), settler.getZ());
        ServerPlayer vanilla = helper.makeMockServerPlayerInLevel();
        vanilla.setPos(settler.getX() - 2.0D, settler.getY(), settler.getZ());
        try (Probe good = new Probe(capable, "good"); Probe bare = new Probe(vanilla, "bare")) {
            flush(good, bare);
            good.payloads.clear();
            bare.payloads.clear();

            FxHooks.skillLevelUp(settler);
            flush(good, bare);
            helper.assertTrue(good.fx().size() == 1,
                "skill level-up must reach the capable player as exactly one FxPayload, got " + good.fx().size());
            helper.assertTrue(bare.fx().isEmpty(), "a player without the channel must get no FxPayload");
            FxPayload payload = good.fx().getFirst();
            helper.assertTrue(payload.effectType() == FxEffect.SKILL_LEVEL_UP
                    && payload.entityId() == settler.getId(),
                "the payload names the moment and the settler");
            helper.assertTrue(good.particlePackets == 0, "no server particle packets for an FX moment");

            // same moment, same tick, same block (a second hook path) collapses
            FxHooks.skillLevelUp(settler);
            flush(good, bare);
            helper.assertTrue(good.fx().size() == 1, "same-tick duplicate is deduped");

            // a different moment in the same tick still sends
            FxHooks.crafted(settler, GoodsQuality.LEGENDARY);
            flush(good, bare);
            helper.assertTrue(good.fx().size() == 2
                    && good.fx().get(1).effectType() == FxEffect.CRAFT_LEGENDARY,
                "a Legendary craft is its own single payload");
            // below Superior: no moment at all
            FxHooks.crafted(settler, GoodsQuality.FINE);
            flush(good, bare);
            helper.assertTrue(good.fx().size() == 2, "a Fine craft makes no glint");
            helper.assertTrue(bare.fx().isEmpty(), "still nothing for the channel-less player");
        }
        helper.succeed();
    }

    @GameTest(template = "empty5", batch = "fx_range_and_banner_moments")
    public void bannerMomentsRespectRange(GameTestHelper helper) {
        FxSend.resetForTests();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Fxholm", center);
        ServerPlayer near = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(near.connection.getConnection());
        near.setPos(center.getX() + 3.0D, center.getY(), center.getZ());
        ServerPlayer far = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(far.connection.getConnection());
        far.setPos(center.getX() + 200.0D, center.getY(), center.getZ());
        try (Probe a = new Probe(near, "near"); Probe b = new Probe(far, "far")) {
            flush(a, b);
            a.payloads.clear();
            b.payloads.clear();
            FxHooks.techLearned(helper.getLevel(), settlement);
            FxHooks.raidWon(helper.getLevel(), settlement);
            FxHooks.journeyChapter(helper.getLevel(), settlement);
            flush(a, b);
            List<FxPayload> got = a.fx();
            helper.assertTrue(got.size() == 3, "three distinct Banner moments = three payloads, got " + got.size());
            helper.assertTrue(got.get(0).effectType() == FxEffect.TECH_LEARNED
                    && got.get(1).effectType() == FxEffect.RAID_WON
                    && got.get(2).effectType() == FxEffect.JOURNEY_CHAPTER, "in order, one each");
            helper.assertTrue(b.fx().isEmpty(), "a player 200 blocks away gets nothing");
            // a client-only effect is never sent
            int sent = FxSend.at(helper.getLevel(), FxEffect.FIREFLY, center.getX(), center.getY(), center.getZ());
            flush(a, b);
            helper.assertTrue(sent == 0 && a.fx().size() == 3, "client-derived moments never go on the wire");
        }
        helper.succeed();
    }

    @GameTest(template = "empty5", batch = "fx_captain_one_payload")
    public void captainWindupIsOnePayloadForTheWholeTelegraph(GameTestHelper helper) {
        FxSend.resetForTests();
        SettlerEntity captain = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(viewer.connection.getConnection());
        viewer.setPos(captain.getX() + 3.0D, captain.getY(), captain.getZ());
        try (Probe probe = new Probe(viewer, "captain")) {
            flush(probe);
            probe.payloads.clear();
            var rally = com.hearthstead.entity.combat.captain.CaptainSpecial.RALLY_CRY;
            FxHooks.captainWindup(captain, rally);
            flush(probe);
            helper.assertTrue(probe.fx().size() == 1
                    && probe.fx().getFirst().effectType() == FxEffect.CAPTAIN_WINDUP
                    && (probe.fx().getFirst().arg() >> 8) == rally.windupTicks()
                    && (probe.fx().getFirst().arg() & 255) == FxHooks.CAPTAIN_RALLY,
                "one wind-up payload carries style and length, got " + probe.fx());
            FxHooks.captainWindup(captain, com.hearthstead.entity.combat.captain.CaptainSpecial.DODGE_STEP);
            flush(probe);
            helper.assertTrue(probe.fx().size() == 1, "a special without a wind-up sends nothing");
            FxHooks.captainImpact(captain, rally, captain.position().add(1.0D, 0.0D, 0.0D));
            flush(probe);
            helper.assertTrue(probe.fx().size() == 2 && probe.fx().get(1).effectType() == FxEffect.CAPTAIN_IMPACT
                    && probe.fx().get(1).entityId() == captain.getId(), "one impact payload naming the Captain");
            helper.assertTrue(probe.particlePackets == 0, "no server particle packets");
        }
        helper.succeed();
    }

    private static void flush(Probe... probes) {
        for (Probe probe : probes) {
            probe.channel.runPendingTasks();
            Object outbound;
            while ((outbound = probe.channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        }
    }

    private static final class Probe implements AutoCloseable {
        private final EmbeddedChannel channel;
        private final String name;
        private final List<Object> payloads = new ArrayList<>();
        private int particlePackets;

        private Probe(ServerPlayer player, String suffix) {
            channel = (EmbeddedChannel) player.connection.getConnection().channel();
            name = "hearthstead_fx_probe_" + suffix;
            channel.pipeline().addLast(name, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                        throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet) {
                        payloads.add(packet.payload());
                    } else if (message instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket) {
                        particlePackets++;
                    }
                    super.write(context, message, promise);
                }
            });
        }

        private List<FxPayload> fx() {
            return payloads.stream().filter(FxPayload.class::isInstance).map(FxPayload.class::cast).toList();
        }

        @Override
        public void close() {
            if (channel.pipeline().get(name) != null) {
                channel.pipeline().remove(name);
            }
        }
    }
}
