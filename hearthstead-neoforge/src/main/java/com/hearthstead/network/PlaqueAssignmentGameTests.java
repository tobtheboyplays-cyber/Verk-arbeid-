package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Regression boundary for the plaque's one remaining assignment role: homes. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PlaqueAssignmentGameTests {

    private static final String WORK_DIRECT_KEY =
        "hearthstead.plaque.assign.work_direct";

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "plaque_work_assign_packet_is_rejected_and_read_only")
    public void workAssignPacketIsRejectedAndReadOnly(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Staffstead");
        PlaqueBlockEntity plaque = plaque(helper, settlement,
            BuildingType.FARMHOUSE, 8, 8);
        Building building = plaque.building(helper.getLevel());
        SettlerEntity candidate = settler(helper, settlement, "Eira",
            new BlockPos(5, 1, 5));
        ServerPlayer player = viewer(helper, plaque.getBlockPos());
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get()));
        MessageProbe packets = new MessageProbe(player, "work_assign_refusal");

        helper.assertTrue(building != null && building.workers.isEmpty()
                && candidate.getProfession() == Profession.NONE,
            "fixture must begin with one unassigned settler and an empty workplace");
        int revision = plaque.revision();
        UUID session = PlaqueNetwork.openFor(player, plaque);
        flush(packets);
        PlaqueSnapshot opening = packets.onlyPlaque().getLast();
        helper.assertTrue(opening.candidates().isEmpty(),
            "a work-plaque snapshot must author no candidate hiring path");
        packets.clear();

        PlaqueNetwork.handle(player, new PlaqueAction(plaque.getBlockPos(),
            plaque.buildingId(), session, PlaqueAction.Kind.ASSIGN,
            candidate.getUUID(), opening.revision()));
        flush(packets);

        helper.assertTrue(building.workers.isEmpty()
                && candidate.getProfession() == Profession.NONE
                && player.getMainHandItem().is(ModItems.FARMER_EMBLEM.get())
                && player.getMainHandItem().getCount() == 1
                && plaque.revision() == revision,
            "even an authorized legacy ASSIGN must preserve worker, profession, "
                + "emblem and plaque state");
        helper.assertTrue(packets.hasOverlayTranslation(WORK_DIRECT_KEY),
            "the rejected packet must explain the direct-to-settler Job Emblem path");
        helper.assertTrue(packets.onlyPlaque().size() == 1
                && packets.onlyPlaque().getFirst().delivery()
                    == PlaqueSnapshot.Delivery.UPDATE
                && packets.onlyPlaque().getFirst().candidates().isEmpty(),
            "the stale client must receive a candidate-free authoritative update");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "plaque_housing_assign_still_claims_a_bed")
    public void housingAssignStillClaimsABed(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Homestead");
        PlaqueBlockEntity plaque = plaque(helper, settlement,
            BuildingType.HOUSE, 8, 8);
        Building building = plaque.building(helper.getLevel());
        SettlerEntity resident = settler(helper, settlement, "Liv",
            new BlockPos(5, 1, 5));
        BlockPos bed = helper.absolutePos(new BlockPos(9, 1, 9));
        helper.assertTrue(building != null, "fixture must resolve its home");
        building.beds.add(bed);
        ServerPlayer player = viewer(helper, plaque.getBlockPos());
        MessageProbe packets = new MessageProbe(player, "housing_assign");

        UUID session = PlaqueNetwork.openFor(player, plaque);
        flush(packets);
        PlaqueSnapshot opening = packets.onlyPlaque().getLast();
        helper.assertTrue(opening.candidates().stream()
                .anyMatch(candidate -> candidate.id().equals(resident.getUUID())
                    && candidate.blockedReason().isEmpty()),
            "a valid home with a free bed must retain its eligible move-in candidate");
        packets.clear();

        PlaqueNetwork.handle(player, new PlaqueAction(plaque.getBlockPos(),
            plaque.buildingId(), session, PlaqueAction.Kind.ASSIGN,
            resident.getUUID(), opening.revision()));
        flush(packets);

        helper.assertTrue(bed.equals(resident.getClaimedBed()),
            "housing ASSIGN must still claim this home's free bed");
        helper.assertTrue(packets.onlyPlaque().size() == 1
                && packets.onlyPlaque().getFirst().occupants().stream()
                    .anyMatch(occupant -> occupant.id().equals(resident.getUUID())),
            "the housing update must move the resident into the occupant overview");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper,
                                         Settlement settlement, String name,
                                         BlockPos relativePos) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relativePos);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static PlaqueBlockEntity plaque(GameTestHelper helper,
                                            Settlement settlement,
                                            BuildingType type, int x, int z) {
        Building building = GameTestFixtures.register(helper, settlement, type, x, z);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel()
            .getBlockEntity(building.plaquePos);
        if (plaque == null) {
            throw new IllegalStateException("fixture did not create a plaque");
        }
        try {
            set(plaque, "buildingId", building.id);
            set(plaque, "type", type);
            set(plaque, "state", PlaqueState.LINKED_VALID);
            set(plaque, "insertedPlan", PlaqueItemData.stamped(
                new ItemStack(ModItems.BUILD_PLAN.get()), type));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("fixture could not link plaque", exception);
        }
        return plaque;
    }

    private static void set(PlaqueBlockEntity plaque, String name, Object value)
            throws ReflectiveOperationException {
        Field field = PlaqueBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plaque, value);
    }

    private static ServerPlayer viewer(GameTestHelper helper, BlockPos plaquePos) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(plaquePos.getX() + 0.5D, plaquePos.getY() + 0.5D,
            plaquePos.getZ() + 0.5D);
        return player;
    }

    private static void flush(MessageProbe probe) {
        probe.channel.runPendingTasks();
        Object outbound;
        while ((outbound = probe.channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    private static final class MessageProbe implements AutoCloseable {
        private final EmbeddedChannel channel;
        private final String handlerName;
        private final List<PlaqueSnapshot> snapshots = new ArrayList<>();
        private final List<Component> overlayMessages = new ArrayList<>();

        private MessageProbe(ServerPlayer player, String suffix) {
            channel = (EmbeddedChannel) player.connection.getConnection().channel();
            handlerName = "hearthstead_plaque_assignment_" + suffix;
            channel.pipeline().addLast(handlerName, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet
                            && packet.payload() instanceof PlaqueSnapshot snapshot) {
                        snapshots.add(snapshot);
                    } else if (message instanceof ClientboundSystemChatPacket packet
                            && packet.overlay()) {
                        overlayMessages.add(packet.content());
                    }
                    super.write(context, message, promise);
                }
            });
        }

        private List<PlaqueSnapshot> onlyPlaque() {
            return snapshots;
        }

        private boolean hasOverlayTranslation(String key) {
            return overlayMessages.stream().anyMatch(message ->
                message.getContents() instanceof TranslatableContents translated
                    && translated.getKey().equals(key));
        }

        private void clear() {
            snapshots.clear();
            overlayMessages.clear();
        }

        @Override
        public void close() {
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        }
    }
}
