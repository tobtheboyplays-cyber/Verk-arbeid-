package com.hearthstead.client.workzone;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.screen.SettlerScreen;
import com.hearthstead.client.screen.WorkZoneConfirmScreen;
import com.hearthstead.network.WorkZoneActionPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import com.hearthstead.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only input cancellation and transparent 3D Work Zone preview. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class WorkZoneClient {
    private static WorkZoneSnapshotPayload current;

    enum AttackDecision {
        PASS,
        CAPTURE_AND_CANCEL_BLOCK_DAMAGE
    }

    public static void accept(WorkZoneSnapshotPayload snapshot) {
        if (snapshot == null || snapshot.stage()
                == WorkZoneSnapshotPayload.Stage.UNKNOWN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String clientDimension = mc.level == null ? null
            : mc.level.dimension().location().toString();
        if (!dimensionsMatch(clientDimension, snapshot.dimension())) {
            // A late packet for another dimension cannot paint this world.
            // It also must not erase a newer session already opened here.
            if (shouldClearCurrentForForeignSnapshot(clientDimension, current,
                    snapshot)) {
                current = null;
            }
            if (mc.screen instanceof WorkZoneConfirmScreen screen
                && !screen.matchesDimension(clientDimension)) {
                mc.setScreen(null);
            }
            return;
        }

        WorkZoneConfirmScreen matching = mc.screen
            instanceof WorkZoneConfirmScreen screen && screen.accepts(snapshot)
                ? screen : null;
        boolean currentMatches = sameIdentity(current, snapshot);
        if (snapshot.stage() == WorkZoneSnapshotPayload.Stage.RESET
            && matching == null && !currentMatches) {
            // A late replay rejection must not erase a newer valid session.
            return;
        }
        if (matching != null) {
            matching.update(snapshot);
        }

        switch (snapshot.stage()) {
            case TARGET_SELECTED -> {
                current = snapshot;
                QaClientObserver.markUiTransition("work_scepter_target_selected");
                // The Overview waits for this exact authoritative acceptance
                // before returning to the world. A refusal never reaches this
                // branch, so it remains visible on the original sheet.
                if (mc.screen instanceof SettlerScreen settlerScreen
                    && settlerScreen.consumeAcceptedWorkZoneSelection()) {
                    mc.setScreen(null);
                }
            }
            case CORNER_ONE -> {
                current = snapshot;
                QaClientObserver.markUiTransition("work_scepter_corner_one");
            }
            case CORNER_TWO -> {
                current = snapshot;
                QaClientObserver.markUiTransition("work_scepter_corner_two");
            }
            case PREVIEW_READY -> {
                current = snapshot;
                QaClientObserver.markUiTransition("work_scepter_zone_preview");
                if (matching == null) {
                    mc.setScreen(new WorkZoneConfirmScreen(snapshot));
                }
            }
            case COMMITTED -> {
                QaClientObserver.markUiTransition("work_scepter_zone_confirm");
                current = null;
            }
            case CANCELLED -> {
                QaClientObserver.markUiTransition("work_scepter_zone_cancel");
                current = null;
            }
            case REJECTED -> {
                QaClientObserver.markUiTransition("work_scepter_zone_reject");
                // A modal rejection retains identity for its explicit Cancel;
                // a pre-screen rejection is actionable only when the server
                // returned TARGET_SELECTED/CORNER_ONE above.
                current = matching == null ? null : snapshot;
            }
            case RESET -> {
                QaClientObserver.markUiTransition("work_scepter_zone_reset");
                current = null;
                if (matching != null && mc.screen == matching) {
                    mc.setScreen(null);
                }
            }
            case UNKNOWN -> current = null;
        }
    }

    /**
     * Left-click chooses corner two and consumes the attack before vanilla can
     * damage the pointed block. The server independently ray-checks the same
     * physical block before accepting it.
     */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = mc.hitResult instanceof BlockHitResult blockHit
            && blockHit.getType() == HitResult.Type.BLOCK ? blockHit : null;
        WorkZoneSnapshotPayload.Stage stage = current == null
            ? WorkZoneSnapshotPayload.Stage.UNKNOWN : current.stage();
        if (decideAttack(event.isAttack(), stage, mc.screen != null,
                mc.player != null && mc.getConnection() != null,
                mc.player != null && mc.player.getMainHandItem()
                    .is(ModItems.WORK_SCEPTER.get()), hit != null)
            != AttackDecision.CAPTURE_AND_CANCEL_BLOCK_DAMAGE) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        WorkZoneActionPayload.Kind kind = stage
            == WorkZoneSnapshotPayload.Stage.CORNER_ONE
                ? WorkZoneActionPayload.Kind.SET_SECOND_CORNER
                : WorkZoneActionPayload.Kind.SET_HEIGHT;
        PacketDistributor.sendToServer(action(kind, hit.getBlockPos()));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
            || current == null || current.cornerOne().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !dimensionsMatch(
                mc.level.dimension().location().toString(), current.dimension())) {
            current = null;
            if (mc.screen instanceof WorkZoneConfirmScreen) {
                mc.setScreen(null);
            }
            return;
        }
        BlockPos first = current.cornerOne().orElseThrow();
        BlockPos second = current.cornerTwo().orElse(first);
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()),
            Math.min(first.getY(), second.getY()),
            Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()),
            Math.max(first.getY(), second.getY()),
            Math.max(first.getZ(), second.getZ()));

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float red = current.typeWireId() == 0 ? 0.72F : 0.35F;
        float green = current.typeWireId() == 0 ? 0.57F : 0.68F;
        float blue = current.typeWireId() == 0 ? 0.18F : 0.32F;
        DebugRenderer.renderFilledBox(pose, buffers, min, max,
            red, green, blue, 0.13F);
        buffers.endBatch(RenderType.debugFilledBox());

        Vec3 camera = event.getCamera().getPosition();
        AABB outline = AABB.encapsulatingFullBlocks(min, max)
            .move(-camera.x, -camera.y, -camera.z)
            .inflate(0.002D);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
            outline, 0.96F, 0.88F, 0.50F, 1.0F);
        buffers.endBatch(RenderType.lines());
    }

    public static WorkZoneActionPayload action(WorkZoneActionPayload.Kind kind,
                                                BlockPos corner) {
        WorkZoneSnapshotPayload snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("No active Work Zone session");
        }
        return new WorkZoneActionPayload(snapshot.sessionId(),
            snapshot.settlementId(), snapshot.buildingId(),
            snapshot.typeWireId(), kind, snapshot.expectedRevision(),
            java.util.Optional.ofNullable(corner));
    }

    static AttackDecision decideAttack(boolean attack,
                                       WorkZoneSnapshotPayload.Stage stage,
                                       boolean screenOpen,
                                       boolean connectedPlayer,
                                       boolean mainHandScepter,
                                       boolean physicalBlockHit) {
        return attack && (stage == WorkZoneSnapshotPayload.Stage.CORNER_ONE
            || stage == WorkZoneSnapshotPayload.Stage.CORNER_TWO)
            && !screenOpen && connectedPlayer && mainHandScepter
            && physicalBlockHit
            ? AttackDecision.CAPTURE_AND_CANCEL_BLOCK_DAMAGE
            : AttackDecision.PASS;
    }

    static boolean dimensionsMatch(String clientDimension,
                                   String snapshotDimension) {
        return clientDimension != null && snapshotDimension != null
            && clientDimension.equals(snapshotDimension);
    }

    static boolean sameIdentity(WorkZoneSnapshotPayload first,
                                WorkZoneSnapshotPayload second) {
        return first != null && second != null
            && first.sessionId().equals(second.sessionId())
            && first.settlementId().equals(second.settlementId())
            && first.buildingId().equals(second.buildingId());
    }

    static boolean shouldClearCurrentForForeignSnapshot(
            String clientDimension, WorkZoneSnapshotPayload active,
            WorkZoneSnapshotPayload incoming) {
        return active != null
            && (!dimensionsMatch(clientDimension, active.dimension())
                || sameIdentity(active, incoming));
    }

    private WorkZoneClient() { }
}
