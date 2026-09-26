package com.hearthstead.network;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server authority for one exact Guard Orders inspection session. */
public final class GuardOrderNetwork {
    private static final double INSPECTION_RANGE_SQR = 8.0D * 8.0D;

    public static void handle(ServerPlayer player,
                              GuardOrderActionPayload action) {
        if (player == null || action == null
            || action.kind() == GuardOrderActionPayload.Kind.UNKNOWN) {
            if (player != null) reject(player.serverLevel(), null,
                action == null ? "reject_null_action" : "reject_unknown_action",
                0, 0);
            return;
        }
        ResolveResult resolution = resolve(player, action);
        if (resolution.resolved() == null) {
            reject(player.serverLevel(), action.guardId(), resolution.reason(),
                0, 0);
            // No resolved guard means no snapshot to carry the refusal, so
            // say it on the action bar instead of letting the screen time out
            // and blame the network (super-QA, battle roles).
            player.displayClientMessage(Component.translatable(
                feedbackFor(resolution.reason() == null ? "" : resolution.reason())), true);
            return;
        }
        Resolved resolved = resolution.resolved();
        GuardOrder current = resolved.validation.order().orElse(null);
        int currentRevision = current == null ? 0 : current.revision();
        int currentPoints = current == null ? 0 : current.patrolPoints().size();

        if (action.kind() == GuardOrderActionPayload.Kind.REFRESH) {
            send(player, snapshot(resolved, action.sessionId(), current,
                GuardOrderSnapshotPayload.Outcome.NEUTRAL, Optional.empty()));
            return;
        }
        String terminalRefusal = validateMutationContext(player, action,
            resolved, currentRevision);
        if (terminalRefusal != null) {
            reject(resolved.level, resolved.guard.getUUID(), terminalRefusal,
                currentRevision, currentPoints);
            send(player, snapshot(resolved, action.sessionId(), current,
                GuardOrderSnapshotPayload.Outcome.REFUSED,
                key(feedbackFor(terminalRefusal))));
            return;
        }

        GuardOrder order = resolved.settlement.guardOrders.orderForMutation(
            resolved.settlement.id, resolved.guard.getUUID(),
            resolved.level.dimension().location()).orElse(null);
        if (order == null) {
            reject(resolved.level, resolved.guard.getUUID(),
                "reject_guard_book_unavailable", currentRevision, currentPoints);
            send(player, snapshot(resolved, action.sessionId(), current,
                GuardOrderSnapshotPayload.Outcome.REFUSED,
                key("hearthstead.guard.command.integrity_blocked")));
            return;
        }

        int beforeRevision = order.revision();
        int beforePoints = order.patrolPoints().size();
        MutateResult mutation = mutate(player, resolved, order, action.kind());
        if (!mutation.changed()) {
            resolved.settlement.guardOrders.discardEmpty(
                resolved.guard.getUUID(), order);
            reject(resolved.level, resolved.guard.getUUID(), mutation.reason(),
                beforeRevision, beforePoints);
            send(player, snapshot(resolved, action.sessionId(),
                resolved.settlement.guardOrders.order(resolved.guard.getUUID())
                    .orElse(null),
                GuardOrderSnapshotPayload.Outcome.REFUSED,
                key(mutation.feedbackKey())));
            return;
        }

        SettlementSavedData.get(resolved.level).setDirty();
        AuthorityTelemetry.emit(resolved.level,
            AuthorityTelemetry.Event.GUARD_ORDER_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(resolved.settlement.id,
                "guard:" + resolved.guard.getUUID(), beforeRevision,
                order.revision(), beforePoints, order.patrolPoints().size(),
                mutation.reason()));
        JourneyServerHooks.noteGuardAssignmentCommitted(player,
            resolved.settlement, resolved.guard, order, order.revision());
        send(player, snapshot(resolved, action.sessionId(), order,
            GuardOrderSnapshotPayload.Outcome.APPLIED,
            key(mutation.feedbackKey())));
    }

    @Nullable
    private static String validateMutationContext(ServerPlayer player,
                                                   GuardOrderActionPayload action,
                                                   Resolved resolved,
                                                   int currentRevision) {
        if (!player.isAlive()) return "reject_player_dead";
        if (player.isSpectator()) return "reject_spectator";
        if (!resolved.settlement.id.equals(action.settlementId())) {
            return "reject_cross_settlement";
        }
        if (action.expectedRevision() != currentRevision) {
            return "reject_stale_revision";
        }
        if (currentRevision == Integer.MAX_VALUE) {
            return "reject_revision_saturated";
        }
        return null;
    }

    private static MutateResult mutate(ServerPlayer player, Resolved resolved,
                                       GuardOrder order,
                                       GuardOrderActionPayload.Kind kind) {
        Building employer = resolved.validation.employer().orElseThrow();
        UUID issuer = player.getUUID();
        long now = resolved.level.getGameTime();
        return switch (kind) {
            case HOLD_HERE -> {
                BlockPos here = player.blockPosition().immutable();
                if (!validPlayerPoint(resolved, here)) {
                    yield refused("reject_invalid_stand_post",
                        "hearthstead.guard.command.outside_settlement");
                }
                boolean changed = order.issueStand(here, player.getDirection(),
                    GuardOrder.DEFAULT_LEASH_RADIUS, issuer, employer.id, now);
                yield result(changed, "set_stand_post",
                    "hearthstead.guard.command.hold_applied");
            }
            case DEFEND_HEARTH -> {
                BlockPos defend = defendPosition(resolved.level,
                    resolved.settlement, resolved.guard);
                if (defend == null) {
                    yield refused("reject_hearth_post_unreachable",
                        "hearthstead.guard.command.hearth_unreachable");
                }
                boolean changed = order.issueStand(defend,
                    faceToward(defend, resolved.settlement.center),
                    GuardOrder.DEFAULT_LEASH_RADIUS, issuer, employer.id, now);
                yield result(changed, "set_hearth_post",
                    "hearthstead.guard.command.defend_applied");
            }
            case ADD_PATROL_POINT -> {
                BlockPos point = player.blockPosition().immutable();
                if (!validPlayerPoint(resolved, point)) {
                    yield refused("reject_invalid_patrol_point",
                        "hearthstead.guard.command.outside_settlement");
                }
                if (order.patrolPoints().size() >= GuardOrder.MAX_PATROL_POINTS) {
                    yield refused("reject_patrol_full",
                        "hearthstead.guard.command.route_full");
                }
                if (order.patrolPoints().contains(point)) {
                    yield refused("reject_duplicate_patrol_point",
                        "hearthstead.guard.command.duplicate_point");
                }
                boolean changed = order.appendPatrolPoint(point, issuer,
                    employer.id, now);
                yield result(changed, "set_patrol_point_add",
                    "hearthstead.guard.command.point_added");
            }
            case REMOVE_PATROL_POINT -> {
                if (order.patrolPoints().isEmpty()) {
                    yield refused("reject_no_patrol_point",
                        "hearthstead.guard.command.no_point");
                }
                boolean changed = order.removeLastPatrolPoint(issuer,
                    employer.id, now);
                yield result(changed, "set_patrol_point_remove",
                    "hearthstead.guard.command.point_removed");
            }
            case START_PATROL -> {
                if (order.patrolPoints().size() < GuardOrder.MIN_PATROL_POINTS) {
                    yield refused("reject_patrol_too_short",
                        "hearthstead.guard.command.need_two_points");
                }
                boolean changed = order.issuePatrol(order.traversal(), issuer,
                    employer.id, now);
                yield result(changed, "set_patrol_route",
                    "hearthstead.guard.command.patrol_applied");
            }
            case CLEAR_ORDER -> {
                if (order.mode() == GuardOrder.Mode.NONE) {
                    yield refused("reject_no_active_order",
                        "hearthstead.guard.command.no_active_order");
                }
                boolean changed = order.clear(issuer, employer.id, now);
                yield result(changed, "set_clear_order",
                    "hearthstead.guard.command.cleared");
            }
            case TOWER_POST -> {
                if (!GuardAssignmentService.towerPostAvailable(resolved.level,
                        resolved.settlement, employer)) {
                    yield refused("reject_tower_locked",
                        "hearthstead.guard.command.tower_locked_tip");
                }
                BlockPos post = towerPostPosition(resolved.level,
                    resolved.settlement, employer, resolved.guard);
                if (post == null) {
                    yield refused("reject_tower_post_unreachable",
                        "hearthstead.guard.command.tower_unreachable");
                }
                boolean changed = order.issueTower(post,
                    faceToward(post, employer.plaquePos),
                    GuardOrder.DEFAULT_FACING_ARC, issuer, employer.id, now);
                yield result(changed, "set_tower_post",
                    "hearthstead.guard.command.tower_applied");
            }
            case TOGGLE_TRAVERSAL -> {
                if (order.patrolPoints().size()
                        < GuardOrder.MIN_PATROL_POINTS) {
                    yield refused("reject_patrol_too_short",
                        "hearthstead.guard.command.need_two_points");
                }
                boolean changed = order.toggleTraversal(issuer, employer.id, now);
                yield result(changed, "set_patrol_traversal",
                    "hearthstead.guard.command.traversal_changed");
            }
            case REFRESH, UNKNOWN -> refused("reject_unknown_action",
                "hearthstead.guard.command.refused");
        };
    }

    private static MutateResult result(boolean changed, String reason,
                                       String feedback) {
        return changed ? new MutateResult(true, reason, feedback)
            : refused("reject_revision_saturated", feedback);
    }

    private static MutateResult refused(String reason, String feedback) {
        return new MutateResult(false, reason, feedback);
    }

    private static GuardOrderSnapshotPayload snapshot(Resolved resolved,
            UUID sessionId, @Nullable GuardOrder order,
            GuardOrderSnapshotPayload.Outcome outcome,
            Optional<Component> feedback) {
        int revision = order == null ? 0 : order.revision();
        int mode = order == null ? GuardOrder.Mode.NONE.wireId()
            : order.mode().wireId();
        Optional<BlockPos> destination = order == null ? Optional.empty()
            : order.pos();
        List<BlockPos> points = order == null ? List.of()
            : order.patrolPoints();
        GuardOrder.Traversal traversal = order == null
            ? GuardOrder.Traversal.LOOP : order.traversal();
        int facing = order == null ? Direction.NORTH.get3DDataValue()
            : order.facing().get3DDataValue();
        int leash = order == null ? GuardOrder.DEFAULT_LEASH_RADIUS
            : order.leashRadius();
        int arc = order == null ? GuardOrder.DEFAULT_FACING_ARC
            : order.facingArc();
        Optional<UUID> building = order == null ? Optional.empty()
            : order.linkedBuildingId();
        Building employer = resolved.validation.employer().orElse(null);
        return new GuardOrderSnapshotPayload(resolved.guard.getId(),
            resolved.guard.getUUID(), sessionId, resolved.settlement.id,
            revision, mode, destination, points, traversal.wireId(), facing,
            leash, arc, building, !resolved.player.isSpectator(),
            GuardAssignmentService.hasServiceableEquipment(resolved.guard),
            GuardAssignmentService.towerPostAvailable(resolved.level,
                resolved.settlement, employer), outcome, feedback);
    }

    private static ResolveResult resolve(ServerPlayer player,
                                         GuardOrderActionPayload action) {
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(action.guardEntityId())
                instanceof SettlerEntity guard)) {
            return failed("reject_guard_entity");
        }
        if (!guard.getUUID().equals(action.guardId())) {
            return failed("reject_guard_uuid");
        }
        if (!guard.isAlive() || guard.level() != level) {
            return failed("reject_guard_dead");
        }
        if (player.distanceToSqr(guard) > INSPECTION_RANGE_SQR) {
            return failed("reject_out_of_range");
        }
        if (!InspectionViewers.authorizeSettler(player, guard,
                action.guardId(), action.sessionId())) {
            return failed("reject_identity_session_dimension");
        }
        SettlementSavedData root = SettlementSavedData.existing(level);
        Settlement settlement = root == null ? null
            : root.settlements.get(guard.getSettlementId());
        if (settlement == null) return failed("reject_settlement_missing");
        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(level, settlement, guard, true);
        if (validation.reason() != GuardAssignmentService.InvalidReason.NONE) {
            return failed("reject_" + validation.reason().id());
        }
        return new ResolveResult(new Resolved(player, level, settlement, guard,
            validation), "none");
    }

    private static boolean validPlayerPoint(Resolved resolved, BlockPos point) {
        // The player's live server position is the authored order. Validate
        // that it is a loaded, standable settlement square here; do not make
        // a single synchronous pathfinder result part of command authority.
        // Doors, temporary mobs and changing blocks can make that result
        // transient. GuardOrderGoal owns bounded retries and truthful no_path
        // feedback while the persisted order remains intact.
        return resolved.settlement.inside(point)
            && resolved.level.isLoaded(point)
            && standable(resolved.level, point);
    }

    /** Chooses a real standable, path-reachable square around the Hearth. */
    @Nullable
    static BlockPos defendPosition(ServerLevel level, Settlement settlement,
                                   SettlerEntity guard) {
        List<BlockPos> candidates = nearby(settlement.center);
        candidates.sort(Comparator.comparingDouble(
            candidate -> guard.blockPosition().distSqr(candidate)));
        for (BlockPos candidate : candidates) {
            if (!settlement.inside(candidate) || !level.isLoaded(candidate)
                || !standable(level, candidate)) continue;
            Path path = guard.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) return candidate.immutable();
        }
        return null;
    }

    /** Bounded post search over marker cells and one registered floor; never loads a chunk. */
    @Nullable
    static BlockPos towerPostPosition(ServerLevel level, Settlement settlement,
                                      Building tower, SettlerEntity guard) {
        // The caller has already required towerPostAvailable; repeat the
        // exact settlement/building identity here because this bounded helper
        // is package-visible for server-side tests as well.
        if (!exactTowerCandidate(settlement, tower)) {
            return null;
        }
        // A surveyed Watchtower marker may be raised one block above its
        // walkable floor. Include only that adjacent lower band; bounds,
        // support, headroom and ordinary navigation still decide every post.
        Set<BlockPos> candidates = new LinkedHashSet<>(nearbyTowerRing2(tower.anchor.below()));
        candidates.addAll(nearbyTowerRing2(tower.anchor));
        candidates.addAll(nearbyTowerRing2(tower.plaquePos.below()));
        candidates.addAll(nearbyTowerRing2(tower.plaquePos));
        return candidates.stream()
            .filter(tower::contains)
            .filter(level::isLoaded)
            .filter(pos -> standable(level, pos))
            .sorted(Comparator.comparingDouble(
                pos -> guard.blockPosition().distSqr(pos)))
            .filter(pos -> {
                Path path = guard.getNavigation().createPath(pos, 0);
                return path != null && path.canReach();
            })
            .findFirst().map(BlockPos::immutable).orElse(null);
    }

    /** A bounded post must be inside the exact registered Watchtower. */
    private static boolean exactTowerCandidate(Settlement settlement,
                                               Building tower) {
        return settlement != null && tower != null && tower.valid
            && tower.type == BuildingType.WATCHTOWER
            && settlement.buildings.contains(tower);
    }

    private static List<BlockPos> nearby(BlockPos center) {
        List<BlockPos> candidates = new ArrayList<>(18);
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0 || dy != 0) {
                        candidates.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        return candidates;
    }

    /** Bounded Watchtower-only radius-two horizontal search, retaining dy=0..1. */
    private static List<BlockPos> nearbyTowerRing2(BlockPos center) {
        List<BlockPos> candidates = new ArrayList<>(49);
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx != 0 || dz != 0 || dy != 0) {
                        candidates.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        return candidates;
    }

    private static Direction faceToward(BlockPos from, BlockPos target) {
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        return Math.abs(dx) > Math.abs(dz)
            ? (dx >= 0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above())
                .getCollisionShape(level, feet.above()).isEmpty()
            && level.getBlockState(feet.below())
                .isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private static void reject(ServerLevel level, @Nullable UUID guardId,
                               String reason, int revision, int points) {
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED,
            AuthorityTelemetry.Fields.state(null,
                guardId == null ? "guard_order" : "guard:" + guardId,
                revision, revision, points, points,
                reason == null ? "reject_unknown" : reason));
    }

    private static String feedbackFor(String reason) {
        return switch (reason) {
            case "reject_stale_revision" -> "hearthstead.guard.command.stale";
            case "reject_spectator" -> "hearthstead.guard.command.read_only";
            default -> "hearthstead.guard.command.refused";
        };
    }

    private static Optional<Component> key(String key) {
        return Optional.of(Component.translatable(key));
    }

    private static void send(ServerPlayer player,
                             GuardOrderSnapshotPayload snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
    }

    private static ResolveResult failed(String reason) {
        return new ResolveResult(null, reason.toLowerCase(Locale.ROOT));
    }

    private record Resolved(ServerPlayer player, ServerLevel level,
                            Settlement settlement, SettlerEntity guard,
                            GuardAssignmentService.Validation validation) {
    }

    private record ResolveResult(@Nullable Resolved resolved, String reason) {
    }

    private record MutateResult(boolean changed, String reason,
                                String feedbackKey) {
    }

    private GuardOrderNetwork() {
    }
}
