package com.hearthstead.client.workzone;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.screen.SettlerScreen;
import com.hearthstead.client.screen.WorkZoneConfirmScreen;
import com.hearthstead.network.WorkZoneActionPayload;
import com.hearthstead.network.WorkZoneSelectionPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import com.hearthstead.registry.ModItems;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only input cancellation and transparent 3D Work Zone preview. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class WorkZoneClient {
    private static WorkZoneSnapshotPayload current;
    /** One physical press may advance at most one server-authoritative stage. */
    private static boolean rightClickArmed = true;

    enum AttackDecision {
        PASS,
        CONSUME,
        SELECT_SETTLER,
        SELECT_WORKPLACE,
        SET_FIRST_CORNER,
        SET_SECOND_CORNER,
        SET_HEIGHT
    }

    enum WorldTarget {
        BLOCK,
        WORKPLACE,
        SETTLER,
        ENTITY,
        MISS
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

    /** Right-click owns the entire Scepter world flow before vanilla use can run. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        boolean mainHandScepter = mc.player != null && mc.player.getMainHandItem()
            .is(ModItems.WORK_SCEPTER.get());
        if (event.isUseItem() && !rightClickArmed) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        BlockHitResult hit = mc.hitResult instanceof BlockHitResult blockHit
            && blockHit.getType() == HitResult.Type.BLOCK ? blockHit : null;
        WorkZoneSnapshotPayload.Stage stage = current == null
            ? WorkZoneSnapshotPayload.Stage.UNKNOWN : current.stage();
        WorldTarget target = target(mc, hit);
        AttackDecision decision = decideUse(event.isUseItem(), stage, mc.screen != null,
                mc.player != null && mc.getConnection() != null,
                mainHandScepter, target);
        if (decision == AttackDecision.PASS) {
            return;
        }
        rightClickArmed = false;
        event.setCanceled(true);
        event.setSwingHand(false);
        switch (decision) {
            case SELECT_SETTLER -> {
                if (mc.hitResult instanceof EntityHitResult entityHit) {
                    PacketDistributor.sendToServer(WorkZoneSelectionPayload.settler(
                        entityHit.getEntity().getUUID()));
                }
            }
            case SELECT_WORKPLACE -> {
                if (hit != null) PacketDistributor.sendToServer(
                    WorkZoneSelectionPayload.workplace(hit.getBlockPos()));
            }
            case SET_FIRST_CORNER, SET_SECOND_CORNER, SET_HEIGHT -> {
                if (hit == null) return;
                WorkZoneActionPayload.Kind kind = decision == AttackDecision.SET_FIRST_CORNER
                    ? WorkZoneActionPayload.Kind.SET_FIRST_CORNER
                    : decision == AttackDecision.SET_SECOND_CORNER
                        ? WorkZoneActionPayload.Kind.SET_SECOND_CORNER
                        : WorkZoneActionPayload.Kind.SET_HEIGHT;
                PacketDistributor.sendToServer(action(kind, hit.getBlockPos()));
            }
            case PASS, CONSUME -> { }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || !mc.options.keyUse.isDown()) {
            rightClickArmed = true;
        }
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

    static AttackDecision decideUse(boolean useItem,
                                       WorkZoneSnapshotPayload.Stage stage,
                                       boolean screenOpen,
                                       boolean connectedPlayer,
                                       boolean mainHandScepter,
                                       WorldTarget target) {
        if (!useItem || screenOpen || !mainHandScepter) return AttackDecision.PASS;
        if (!connectedPlayer) return AttackDecision.CONSUME;
        if (stage == WorkZoneSnapshotPayload.Stage.TARGET_SELECTED
            && (target == WorldTarget.BLOCK || target == WorldTarget.WORKPLACE)) {
            return AttackDecision.SET_FIRST_CORNER;
        }
        if (stage == WorkZoneSnapshotPayload.Stage.CORNER_ONE
            && (target == WorldTarget.BLOCK || target == WorldTarget.WORKPLACE)) {
            return AttackDecision.SET_SECOND_CORNER;
        }
        if (stage == WorkZoneSnapshotPayload.Stage.CORNER_TWO
            && (target == WorldTarget.BLOCK || target == WorldTarget.WORKPLACE)) {
            return AttackDecision.SET_HEIGHT;
        }
        if (stage == WorkZoneSnapshotPayload.Stage.UNKNOWN) {
            if (target == WorldTarget.SETTLER) return AttackDecision.SELECT_SETTLER;
            if (target == WorldTarget.WORKPLACE) return AttackDecision.SELECT_WORKPLACE;
        }
        return AttackDecision.CONSUME;
    }

    private static WorldTarget target(Minecraft mc, BlockHitResult blockHit) {
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            return entityHit.getEntity() instanceof SettlerEntity
                ? WorldTarget.SETTLER : WorldTarget.ENTITY;
        }
        if (blockHit == null) return WorldTarget.MISS;
        return mc.level != null && mc.level.getBlockEntity(blockHit.getBlockPos())
            instanceof PlaqueBlockEntity ? WorldTarget.WORKPLACE : WorldTarget.BLOCK;
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
