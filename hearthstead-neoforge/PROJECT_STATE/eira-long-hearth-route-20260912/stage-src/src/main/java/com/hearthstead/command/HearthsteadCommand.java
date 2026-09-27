package com.hearthstead.command;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class HearthsteadCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hearthstead")
            .then(Commands.literal("tavernqa").requires(src -> src.hasPermission(4))
                .then(Commands.argument("action", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(ctx -> com.hearthstead.qa.TavernClientQaFixture.command(ctx.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "action")))))
            .then(Commands.literal("foodrestartqa").requires(src -> src.hasPermission(4))
                .then(Commands.argument("action", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .then(Commands.argument("token", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> com.hearthstead.qa.FoodRestartQaFixtureService.command(
                            ctx.getSource(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "action"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "token"))))))
            .then(Commands.literal("battleqa").requires(src -> src.hasPermission(2))
                .then(Commands.literal("prepare").executes(ctx -> battleQa(ctx.getSource(), "prepare")))
                .then(Commands.literal("prepare_grounded").executes(ctx -> battleQa(ctx.getSource(), "prepare_grounded")))
                .then(Commands.literal("prepare_grounded_village").executes(ctx -> battleQa(ctx.getSource(), "prepare_grounded_village")))
                .then(Commands.literal("start").executes(ctx -> battleQa(ctx.getSource(), "start")))
                .then(Commands.literal("status").executes(ctx -> battleQa(ctx.getSource(), "status"))))
            .then(Commands.literal("earlycoinsqa").requires(src -> src.hasPermission(2))
                .then(Commands.argument("action", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(ctx -> com.hearthstead.qa.EarlyCoinsRecruitQa.execute(ctx.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "action")))))
            .then(Commands.literal("demo").executes(ctx -> demo(ctx.getSource())))
            .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
            .then(Commands.literal("development").requires(src -> src.hasPermission(2))
                .then(Commands.literal("unlock")
                    .then(Commands.argument("node",
                            com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            net.minecraft.commands.SharedSuggestionProvider.suggest(
                                java.util.Arrays.stream(
                                        com.hearthstead.settlement.development.DevelopmentNode
                                            .values())
                                    .filter(com.hearthstead.settlement.development
                                        .DevelopmentNode::implemented)
                                    .map(com.hearthstead.settlement.development
                                        .DevelopmentNode::id),
                                builder))
                        .executes(ctx -> developmentUnlock(ctx.getSource(),
                            com.mojang.brigadier.arguments.StringArgumentType
                                .getString(ctx, "node"))))))
            .then(Commands.literal("recruit").requires(src -> src.hasPermission(2))
                .executes(ctx -> recruit(ctx.getSource())))
            .then(Commands.literal("blessing").requires(src -> src.hasPermission(2))
                .executes(ctx -> blessing(ctx.getSource())))
            .then(Commands.literal("raidqa").requires(src -> src.hasPermission(2))
                .then(Commands.literal("prepare")
                    .executes(ctx -> raidQaPrepare(ctx.getSource())))
                .then(Commands.literal("prepare_grounded")
                    .executes(ctx -> raidQaPrepareGrounded(ctx.getSource())))
                .then(Commands.literal("status")
                    .executes(ctx -> raidQaStatus(ctx.getSource())))
                .then(Commands.literal("recurring-ui")
                    .then(Commands.literal("warning")
                        .executes(ctx -> raidQaRecurringUi(ctx.getSource(),
                            com.hearthstead.qa.RaidQaFixtureService.RecurringUiMode.WARNING)))
                    .then(Commands.literal("recovery")
                        .executes(ctx -> raidQaRecurringUi(ctx.getSource(),
                            com.hearthstead.qa.RaidQaFixtureService.RecurringUiMode.RECOVERY))))
                .then(Commands.literal("start")
                    .executes(ctx -> raidQaStart(ctx.getSource())))
                .then(Commands.literal("advance")
                    .executes(ctx -> raidQaAdvance(ctx.getSource()))))
            .then(Commands.literal("bagqa").requires(src -> src.hasPermission(2))
                .then(Commands.literal("prepare")
                    .executes(ctx -> bagQaPrepare(ctx.getSource())))
                .then(Commands.literal("start")
                    .executes(ctx -> bagQaStart(ctx.getSource())))
                .then(Commands.literal("status")
                    .executes(ctx -> bagQaStatus(ctx.getSource()))))
            .then(Commands.literal("hire").requires(src -> src.hasPermission(2))
                .then(Commands.argument("pos",
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                    .executes(ctx -> hire(ctx.getSource(),
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument
                            .getLoadedBlockPos(ctx, "pos")))))
            .then(Commands.literal("mayor").requires(src -> src.hasPermission(2))
                .executes(ctx -> mayor(ctx.getSource())))
            .then(Commands.literal("why").requires(src -> src.hasPermission(2))
                .executes(ctx -> why(ctx.getSource())))
            .then(Commands.literal("pose").requires(src -> src.hasPermission(2))
                .then(Commands.argument("activity",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        for (Pose pose : POSES) {
                            b.suggest(pose.key());
                        }
                        b.suggest("clear");
                        return b.buildFuture();
                    })
                    .executes(ctx -> pose(ctx.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType
                            .getString(ctx, "activity")))))
            .then(Commands.literal("pulse").requires(src -> src.hasPermission(2))
                .executes(ctx -> pulse(ctx.getSource())))
            .then(Commands.literal("lineup").requires(src -> src.hasPermission(2))
                .executes(ctx -> lineup(ctx.getSource(), 0))
                .then(Commands.argument("page",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8))
                    .executes(ctx -> lineup(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType
                            .getInteger(ctx, "page")))))
            .then(Commands.literal("scan").requires(src -> src.hasPermission(2))
                .then(Commands.argument("pos",
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                    .executes(ctx -> scan(ctx.getSource(),
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument
                            .getLoadedBlockPos(ctx, "pos"))))));
    }

    /** Explicit combat-only admin fixture; never awards Journey progression. */
    private static int battleQa(CommandSourceStack source, String action) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Combat sandbox requires a player."));
            return 0;
        }
        com.hearthstead.qa.BattleQaFixtureService.Result result = switch (action) {
            case "prepare" -> com.hearthstead.qa.BattleQaFixtureService.prepare(
                source.getLevel(), player, player.blockPosition());
            case "prepare_grounded" -> com.hearthstead.qa.BattleQaFixtureService.prepareGrounded(
                source.getLevel(), player, player.blockPosition());
            case "prepare_grounded_village" -> com.hearthstead.qa.BattleQaFixtureService.prepareGroundedVillage(
                source.getLevel(), player, player.blockPosition());
            case "start" -> com.hearthstead.qa.BattleQaFixtureService.start(source.getLevel(), player);
            default -> com.hearthstead.qa.BattleQaFixtureService.status(source.getLevel(), player);
        };
        String message = "Combat sandbox: " + result.stage() + " — " + result.detail();
        if (!result.ok()) {
            source.sendFailure(Component.literal(message));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    /** Everything needed to try the whole loop in five minutes. */
    private static int demo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("hearthstead.command.player_only"));
            return 0;
        }
        give(player, new ItemStack(ModItems.HEARTH.get()));
        give(player, new ItemStack(ModItems.BUILD_PLAN.get()));
        give(player, new ItemStack(ModItems.HANDBOOK.get()));
        give(player, new ItemStack(Items.BREAD, 32));
        give(player, new ItemStack(Items.WHEAT_SEEDS, 32));
        give(player, new ItemStack(Items.OAK_SAPLING, 8));
        give(player, new ItemStack(Items.IRON_HOE));
        give(player, new ItemStack(ModItems.SETTLER_SPAWN_EGG.get(), 4));
        player.displayClientMessage(
            Component.translatable("hearthstead.command.demo_given"), false);
        player.displayClientMessage(
            Component.translatable("hearthstead.command.demo_hint"), false);
        return 1;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * Permission-two scripted equivalent of pressing one Development node.
     * It deliberately uses the same Hearth inventory, live quest assessment,
     * revision check, payment transaction and Journey hook as the real UI;
     * filming and restart QA gain deterministic input without gaining a way
     * around progression.
     */
    private static int developmentUnlock(CommandSourceStack source, String nodeId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("hearthstead.command.player_only"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
            source.getPosition());
        if (!(level.getBlockEntity(pos)
                instanceof com.hearthstead.block.HearthBlockEntity hearth)
            || hearth.getSettlementId() == null) {
            source.sendFailure(Component.translatable(
                "hearthstead.mayor.refused.hearth_unavailable"));
            return 0;
        }
        Settlement settlement = SettlementManager.byId(level, hearth.getSettlementId());
        com.hearthstead.settlement.development.DevelopmentNode node =
            com.hearthstead.settlement.development.DevelopmentNode.byId(nodeId);
        if (settlement == null || node == null) {
            source.sendFailure(Component.translatable(
                "hearthstead.development.blocked.invalid"));
            return 0;
        }
        com.hearthstead.settlement.development.Development.Result result =
            com.hearthstead.settlement.development.Development.purchaseNode(level,
                settlement, hearth, node,
                com.hearthstead.settlement.development.Development.revisionOf(level,
                    settlement), player);
        if (result != com.hearthstead.settlement.development.Development.Result.APPLIED) {
            source.sendFailure(Component.translatable(result.translationKey()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(result.translationKey()), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Settlement nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double dist = s.center.distSqr(net.minecraft.core.BlockPos.containing(
                source.getPosition()));
            if (dist < bestDist) {
                bestDist = dist;
                nearest = s;
            }
        }
        if (nearest == null) {
            source.sendSuccess(() ->
                Component.translatable("hearthstead.command.no_settlement"), true);
            return 0;
        }
        Settlement s = nearest;
        // true (broadcastToAdmins), matching scan()/recruit() just below: info()
        // is the same kind of admin/diagnostic read they are, and there is no
        // reason for it alone to suppress console/log visibility when issued
        // by a player rather than the console -- proven live (20260824T114931Z)
        // that with this at false, a player-issued `hearthstead info` produces
        // no server-log trace at all, silently defeating any log-based check.
        source.sendSuccess(() -> Component.translatable("hearthstead.command.info",
            s.name, s.population(), s.capacity(), s.employed(), s.foodCache,
            s.moraleCache, s.radius), true);
        source.sendSuccess(() -> Component.translatable("hearthstead.command.info_homes",
            s.validHomeCount(), s.validBedCount()), true);
        // Readable on purpose. MineColonies' own wiki concedes its raid
        // curve "is not publicly known", and a threat nobody can read
        // produces annoyance rather than dread (D-A3-3).
        source.sendSuccess(() -> Component.translatable("hearthstead.command.info_threat",
            Component.translatable("hearthstead.raid.stage."
                + s.raidPressure.stage().id()),
            s.raidPressure.pressure(),
            String.format(java.util.Locale.ROOT, "%.0f%%",
                s.raidPressure.chanceTonight() * 100.0),
            s.raidPressure.nightsSinceRaid()), true);
        source.sendSuccess(() -> Component.translatable(
            "hearthstead.command.info_blessings",
            Math.max(0, s.blessingState.earned() - s.blessingState.spent()),
            s.blessingState.issuedCount(BlessingId.WARDEN_OATH),
            s.blessingState.issuedCount(BlessingId.HEARTHWARD),
            s.blessingState.issuedCount(BlessingId.THORNED_ROADS)), true);
        return 1;
    }

    /** Permission-two QA/filming hook; survival rewards still come only from raids. */
    private static int blessing(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Settlement nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Settlement candidate : SettlementSavedData.get(level).settlements.values()) {
            double distance = candidate.center.distSqr(
                net.minecraft.core.BlockPos.containing(source.getPosition()));
            if (distance < bestDist) {
                bestDist = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.translatable("hearthstead.command.no_settlement"));
            return 0;
        }
        if (!nearest.blessingState.grantOffer()) {
            source.sendFailure(Component.translatable(
                "hearthstead.command.blessing_refused", nearest.name));
            return 0;
        }
        Settlement settlement = nearest;
        SettlementSavedData.get(level).setDirty();
        BlessingPresentation.offerEarned(level, settlement);
        source.sendSuccess(() -> Component.translatable(
            "hearthstead.command.blessing_granted",
            settlement.blessingState.offerSerial(), settlement.name), true);
        return 1;
    }

    /**
     * Native-playtest observer for the authored first raid.
     *
     * <p>The output deliberately comes from the same persisted lifecycle,
     * readiness service and live entities used by gameplay.  It does not
     * create settlers, buildings, equipment, Journey receipts or combat
     * results, so a filmed run cannot turn a synthetic fixture into release
     * evidence merely by printing an optimistic marker.
     */
    private static int raidQaStatus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                "HSQA_FIRST_RAID status=PLAYER_ONLY"));
            return 0;
        }
        com.hearthstead.qa.RaidQaFixtureService.Result fixture =
            com.hearthstead.qa.RaidQaFixtureService.status(level, player);
        Settlement settlement = fixture.settlement();
        if (settlement == null) {
            source.sendFailure(Component.literal("HSQA_FIRST_RAID status="
                + fixture.blocker().toUpperCase(java.util.Locale.ROOT)
                + " detail=" + fixture.detail().replace(' ', '_')));
            return 0;
        }
        com.hearthstead.settlement.state.RaidLifecycle lifecycle =
            settlement.raidLifecycle;
        com.hearthstead.settlement.raid.FirstRaidReadinessService.Report report =
            lifecycle.firstState()
                    == com.hearthstead.settlement.state.FirstRaidState.PREPARING
                ? com.hearthstead.settlement.raid.FirstRaidReadinessService
                    .assessDomain(level, settlement)
                : com.hearthstead.settlement.raid.FirstRaidReadinessService
                    .assessExecution(level, settlement);
        int captains = 0;
        int brutes = 0;
        int skirmishers = 0;
        for (com.hearthstead.settlement.state.RaidParticipantRecord participant
                : lifecycle.participantRoster()) {
            if (lifecycle.terminalParticipants().contains(participant.entityId())) {
                continue;
            }
            if (participant.captain()) {
                captains++;
            } else if (participant.build()
                    == com.hearthstead.settlement.state.RaidParticipantRecord.Build.BRUTE) {
                brutes++;
            } else {
                skirmishers++;
            }
        }
        int guards = 0;
        int archers = 0;
        int engaged = 0;
        int guardXp = 0;
        java.util.Set<java.util.UUID> targets = new java.util.HashSet<>();
        for (com.hearthstead.entity.SettlerEntity settler
                : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getProfession() == com.hearthstead.entity.Profession.GUARD) {
                guards++;
                guardXp += settler.combatExperience();
            } else if (settler.getProfession()
                    == com.hearthstead.entity.Profession.ARCHER) {
                archers++;
                guardXp += settler.combatExperience();
            } else {
                continue;
            }
            if (settler.getTarget() instanceof com.hearthstead.entity.RaiderEntity target
                    && settlement.id.equals(target.settlementId())) {
                engaged++;
                targets.add(target.getUUID());
            }
        }
        String captainName = lifecycle.activePlan()
            .or(lifecycle::queuedPlan)
            .flatMap(plan -> com.hearthstead.settlement.raid.RaidDirector
                .leaderNameOf(settlement, plan.captainId()))
            .orElse("none").replace(' ', '_');
        String blockers = report.blockers().isEmpty() ? "none"
            : report.blockers().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.joining("+"));
        int pendingBlessings = Math.max(0,
            settlement.blessingState.earned() - settlement.blessingState.spent());
        String marker = "HSQA_FIRST_RAID status=OK settlement=" + settlement.id
            + " fixtureStage=" + fixture.stage().name()
            + " fixtureReady=" + fixture.ready()
            + " state=" + lifecycle.firstState().name()
            + " ready=" + report.ready()
            + " blockers=" + blockers
            + " warningNight=" + lifecycle.firstWarningNight()
            + " attackNight=" + lifecycle.firstAttackNight()
            + " captain=" + captainName
            + " captains=" + captains
            + " brutes=" + brutes
            + " skirmishers=" + skirmishers
            + " tracked=" + lifecycle.participants().size()
            + " terminal=" + lifecycle.terminalParticipants().size()
            + " guards=" + guards
            + " archers=" + archers
            + " engaged=" + engaged
            + " distinctTargets=" + targets.size()
            + " guardXp=" + guardXp
            + " sleepBlocked="
                + com.hearthstead.settlement.raid.RaidSleepPolicy.blocksSleep(level)
            + " raidLog=" + settlement.raidLog.size()
            + " blessingOffers=" + pendingBlessings;
        source.sendSuccess(() -> Component.literal(marker), true);
        return 1;
    }

    private static int raidQaPrepare(CommandSourceStack source) {
        return raidQaPrepare(source, false);
    }

    private static int raidQaPrepareGrounded(CommandSourceStack source) {
        return raidQaPrepare(source, true);
    }

    private static int raidQaPrepare(CommandSourceStack source, boolean grounded) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("HSQA_FIRST_RAID prepare="
                + (grounded ? "GROUNDED_PLAYER_ONLY" : "PLAYER_ONLY")));
            return 0;
        }
        net.minecraft.core.BlockPos origin = net.minecraft.core.BlockPos.containing(
            source.getPosition()).offset(4, 0, 4);
        com.hearthstead.qa.RaidQaFixtureService.Result result =
            grounded ? com.hearthstead.qa.RaidQaFixtureService.prepareGrounded(
                source.getLevel(), player, origin)
                : com.hearthstead.qa.RaidQaFixtureService.prepare(source.getLevel(),
                    player, origin);
        String marker = "HSQA_FIRST_RAID prepare=" + result.stage().name()
            + (grounded ? " foundation=GROUNDED_ASSISTED" : "")
            + " beds=" + result.physicalBedHeads()
            + " members=" + result.liveMembers()
            + " ready=" + result.ready()
            + " blocker=" + result.blocker().replace(' ', '_')
            + " detail=" + result.detail().replace(' ', '_');
        if (!result.ready()) {
            source.sendFailure(Component.literal(marker));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(marker), true);
        return 1;
    }

    /** Marker-bound assisted snapshot setup for recurring status UI only. */
    private static int raidQaRecurringUi(CommandSourceStack source,
            com.hearthstead.qa.RaidQaFixtureService.RecurringUiMode mode) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("HSQA_RECURRING_UI state="
                + mode.name() + " result=PLAYER_ONLY"));
            return 0;
        }
        com.hearthstead.qa.RaidQaFixtureService.RecurringUiResult result =
            com.hearthstead.qa.RaidQaFixtureService.recurringUi(source.getLevel(),
                player, mode);
        String marker = "HSQA_RECURRING_UI state=" + mode.name()
            + " fixture=VISUAL_ONLY firstState=" + result.firstState()
            + " recurring=" + result.recurring()
            + " plannedNight=" + result.plannedNight()
            + " cooldown=" + result.cooldown()
            + " aftermath=" + result.aftermath()
            + " blocker=" + result.blocker()
            + " detail=" + result.detail().replace(' ', '_');
        if (!result.ready()) {
            source.sendFailure(Component.literal(marker));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(marker), true);
        return 1;
    }
    /** Builds only the physical native bag-review lanes; real AI stays paused. */
    private static int bagQaPrepare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("HSQA_BAG prepare=PLAYER_ONLY"));
            return 0;
        }
        net.minecraft.core.BlockPos playerPos = net.minecraft.core.BlockPos.containing(
            source.getPosition());
        net.minecraft.core.BlockPos origin = playerPos.offset(-23, -4, -19);
        com.hearthstead.qa.BagQaFixtureService.Result result =
            com.hearthstead.qa.BagQaFixtureService.prepare(source.getLevel(),
                player, origin);
        return sendBagQaResult(source, "prepare", result);
    }

    /** Supplies Hearth goods; CourierWorkGoal owns every later state change. */
    private static int bagQaStart(CommandSourceStack source) {
        return sendBagQaResult(source, "start",
            com.hearthstead.qa.BagQaFixtureService.start(source.getLevel()));
    }

    /** Requests one exact, server-owned snapshot in the identity-bound log. */
    private static int bagQaStatus(CommandSourceStack source) {
        return sendBagQaResult(source, "status",
            com.hearthstead.qa.BagQaFixtureService.status(source.getLevel()));
    }

    private static int sendBagQaResult(CommandSourceStack source, String action,
            com.hearthstead.qa.BagQaFixtureService.Result result) {
        String marker = "HSQA_BAG action=" + action
            + " stage=" + result.stage().name()
            + " session=" + (result.sessionId() == null ? "none" : result.sessionId())
            + " origin=" + bagQaPos(result.origin())
            + " camera=" + bagQaPos(result.camera())
            + " detail=" + result.detail().replace(' ', '_');
        if (!result.ok()) {
            source.sendFailure(Component.literal(marker));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(marker), true);
        return 1;
    }

    private static String bagQaPos(net.minecraft.core.BlockPos pos) {
        return pos == null ? "none"
            : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * Advances exactly one calendar boundary through the production raid
     * director.  Readiness remains mandatory; this command cannot supply or
     * rewrite a single qualifying fact.
     */
    private static int raidQaAdvance(CommandSourceStack source) {
        // Compatibility alias: the old multi-step nearest-settlement command
        // is intentionally gone. Both names now use the one marker-bound,
        // readiness-gated production start edge.
        return raidQaStart(source);
    }

    private static int raidQaStart(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                "HSQA_FIRST_RAID start=PLAYER_ONLY"));
            return 0;
        }
        com.hearthstead.qa.RaidQaFixtureService.StartResult result =
            com.hearthstead.qa.RaidQaFixtureService.start(source.getLevel(),
                player);
        String marker = "HSQA_FIRST_RAID start=" + result.outcome().name()
            + " stage=" + result.fixture().stage().name()
            + " spawned=" + result.spawned()
            + " detail=" + result.detail().replace(' ', '_');
        if (!result.started()) {
            source.sendFailure(Component.literal(marker));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(marker), true);
        return 1;
    }

    private static Settlement nearestSettlement(ServerLevel level,
            net.minecraft.world.phys.Vec3 position) {
        Settlement nearest = null;
        double best = Double.MAX_VALUE;
        for (Settlement candidate : SettlementSavedData.get(level).settlements.values()) {
            double distance = position.distanceToSqr(candidate.center.getCenter());
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** Re-surveys the plaque at a position — the admin/testing hook. */
    /**
     * Appoints the nearest settler as mayor.
     *
     * <p>A stopgap until the hearth screen carries the seat: the decision the
     * player actually makes is <i>which person</i>, and this at least lets
     * that decision be made and felt.
     */
    /**
     * Hires the nearest settler into the building whose plaque is at pos.
     *
     * <p>The player's own route is the plaque's Hire tab; this exists so a
     * scripted session can set a village working without driving a UI, which
     * is what filming and QA both need.
     */
    private static int hire(CommandSourceStack source, net.minecraft.core.BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            source.sendFailure(Component.translatable("hearthstead.command.no_plaque",
                pos.getX(), pos.getY(), pos.getZ()));
            return 0;
        }
        com.hearthstead.settlement.Building building = plaque.building(level);
        com.hearthstead.settlement.Settlement s = plaque.settlementFor(level);
        if (building == null || s == null) {
            source.sendFailure(Component.translatable("hearthstead.plaque.not_ready"));
            return 0;
        }
        com.hearthstead.entity.SettlerEntity best = null;
        double nearest = Double.MAX_VALUE;
        for (com.hearthstead.entity.SettlerEntity settler
                : com.hearthstead.settlement.SettlementManager.loadedMembers(level, s)) {
            if (com.hearthstead.settlement.Employment
                    .employerOf(s, settler.getUUID()) != null) {
                continue;
            }
            double d = settler.position().distanceToSqr(pos.getCenter());
            if (d < nearest) {
                nearest = d;
                best = settler;
            }
        }
        if (best == null) {
            source.sendFailure(Component.translatable("hearthstead.employ.no_candidates"));
            return 0;
        }
        com.hearthstead.settlement.Employment.Hired result =
            com.hearthstead.settlement.Employment.hire(level, s, building, best);
        if (!result.ok()) {
            source.sendFailure(result.refusal());
            return 0;
        }
        com.hearthstead.entity.SettlerEntity hired = best;
        source.sendSuccess(() -> Component.translatable("hearthstead.employ.hired",
            hired.getSettlerName(), building.type.displayName()), true);
        return 1;
    }

    private static int mayor(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        com.hearthstead.settlement.Settlement s =
            com.hearthstead.settlement.SettlementManager.at(level,
                net.minecraft.core.BlockPos.containing(source.getPosition()));
        if (s == null) {
            source.sendFailure(Component.translatable("hearthstead.command.no_settlement"));
            return 0;
        }
        com.hearthstead.entity.SettlerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (com.hearthstead.entity.SettlerEntity settler
                : com.hearthstead.settlement.SettlementManager.loadedMembers(level, s)) {
            double d = settler.position().distanceToSqr(source.getPosition());
            if (d < best) {
                best = d;
                nearest = settler;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.translatable("hearthstead.mayor.refused.nobody"));
            return 0;
        }
        Component refusal = com.hearthstead.settlement.Mayor.appoint(level, s, nearest);
        if (refusal != null) {
            source.sendFailure(refusal);
            return 0;
        }
        com.hearthstead.entity.SettlerEntity appointed = nearest;
        source.sendSuccess(() -> Component.translatable("hearthstead.mayor.appointed",
            appointed.getSettlerName(),
            com.hearthstead.settlement.Mayor.boonOf(appointed).displayName()), true);
        return 1;
    }

    private static int scan(CommandSourceStack source, net.minecraft.core.BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            source.sendFailure(Component.translatable("hearthstead.command.no_plaque",
                pos.getX(), pos.getY(), pos.getZ()));
            return 0;
        }
        plaque.survey(level);
        source.sendSuccess(() -> Component.translatable("hearthstead.command.scan_done",
            plaque.type().displayName(),
            Component.translatable("hearthstead.plaque.state." + plaque.state().id())), true);
        return 1;
    }

    /**
     * The admin shortcut consumes the same complete attraction assessment as
     * normal runtime through {@link SettlementManager#primeRecruitment}; it
     * cannot bypass the hearth, tavern, bed, morale, price or post-payment
     * reserve gates. Feedback names every settlement advanced or skipped.
     */
    private static int recruit(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        java.util.Collection<Settlement> all = SettlementSavedData.get(level).settlements.values();
        if (all.isEmpty()) {
            source.sendFailure(Component.translatable("hearthstead.command.no_settlement"));
            return 0;
        }
        int forced = 0;
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (Settlement s : all) {
            if (!SettlementManager.primeRecruitment(level, s)) {
                skipped.add(s.name);
                continue;
            }
            forced++;
        }
        SettlementManager.data(level).setDirty();
        int forcedFinal = forced;
        source.sendSuccess(() ->
            Component.translatable("hearthstead.command.recruit_forced", forcedFinal), true);
        for (String name : skipped) {
            source.sendSuccess(() ->
                Component.translatable("hearthstead.command.recruit_skipped", name), true);
        }
        return forced;
    }


    // ------------------------------------------------ animation showcase ---
    //
    // Every work animation in the mod is driven by one synced value:
    // SettlerEntity's activity. Filming them normally means building the
    // whole job around each one first, which makes an animation review
    // hostage to unrelated bugs -- the lumberjack's tree scan starving on a
    // flat test map is exactly that (KF-018). These three commands drive the
    // activity directly so a clip can be looked at on its own merits.
    //
    // A posed settler has its AI switched off, so nothing overwrites the
    // activity while the camera is on it. This is a viewing aid, never a
    // test oracle: no GameTest may pose a settler and then assert the
    // resulting animation "works" -- that would judge the pose, not the job.
    private record Pose(String key, com.hearthstead.entity.Profession profession,
                        com.hearthstead.entity.SettlerActivity activity, String label) {
    }

    /** Persistent only while the permission-two filming pose is held. */
    private static final String BLESSING_RECEIVE_POSE_TAG =
        "hearthstead_pose_blessing_receive";

    private static final Pose[] POSES = {
        // The trades, each doing the one motion nobody else does (D-016).
        new Pose("chop", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.WORK_CHOP, "Lumberjack - fell"),
        new Pose("gather", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.GATHERING_LOG, "Lumberjack - gather"),
        new Pose("sow", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.WORK_SOW, "Farmer - broadcast"),
        new Pose("harvest", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.WORK_HARVEST, "Farmer - harvest"),
        new Pose("plant", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.WORK_PLANT, "Farmer - plant"),
        new Pose("water", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.WORK_WATER, "Farmer - water"),
        new Pose("till", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.WORK_FARM, "Farmer - till"),
        new Pose("oven", com.hearthstead.entity.Profession.BAKER,
            com.hearthstead.entity.SettlerActivity.WORK_OVEN, "Baker - oven"),
        new Pose("knead", com.hearthstead.entity.Profession.BAKER,
            com.hearthstead.entity.SettlerActivity.WORK_KNEAD, "Baker - knead"),
        new Pose("stoke", com.hearthstead.entity.Profession.SMELTER,
            com.hearthstead.entity.SettlerActivity.WORK_STOKE, "Smelter - stoke"),
        new Pose("hammer", com.hearthstead.entity.Profession.SMITH,
            com.hearthstead.entity.SettlerActivity.WORK_HAMMER, "Smith - anvil"),
        new Pose("saw", com.hearthstead.entity.Profession.SAWYER,
            com.hearthstead.entity.SettlerActivity.WORK_SAW, "Sawyer - saw"),
        new Pose("cleave", com.hearthstead.entity.Profession.BUTCHER,
            com.hearthstead.entity.SettlerActivity.WORK_CLEAVE, "Butcher - cleave"),
        new Pose("weave", com.hearthstead.entity.Profession.WEAVER,
            com.hearthstead.entity.SettlerActivity.WORK_WEAVE, "Weaver - loom"),
        new Pose("mine", com.hearthstead.entity.Profession.MINER,
            com.hearthstead.entity.SettlerActivity.WORK_MINE, "Miner - pick"),
        new Pose("stir", com.hearthstead.entity.Profession.COOK,
            com.hearthstead.entity.SettlerActivity.WORK_STIR, "Cook - stir"),
        new Pose("plane", com.hearthstead.entity.Profession.CARPENTER,
            com.hearthstead.entity.SettlerActivity.WORK_PLANE, "Carpenter - plane"),
        new Pose("chisel", com.hearthstead.entity.Profession.MASON,
            com.hearthstead.entity.SettlerActivity.WORK_CHISEL, "Mason - chisel"),
        new Pose("fletch", com.hearthstead.entity.Profession.FLETCHER,
            com.hearthstead.entity.SettlerActivity.WORK_FLETCH, "Fletcher - fletch"),
        new Pose("scrape", com.hearthstead.entity.Profession.TANNER,
            com.hearthstead.entity.SettlerActivity.WORK_SCRAPE, "Tanner - scrape"),
        new Pose("limb", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.WORK_LIMB, "Lumberjack - limb"),
        // Haulage.
        new Pose("carry", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.CARRYING, "Courier - laden"),
        new Pose("sort", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.SORTING, "Courier - sort"),
        new Pose("haul", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.HAULING_LOG, "Hauling a log"),
        new Pose("travel", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.TRAVELING, "On the road"),
        // The guard.
        new Pose("patrol", com.hearthstead.entity.Profession.GUARD,
            com.hearthstead.entity.SettlerActivity.PATROLLING, "Guard - patrol"),
        new Pose("combat", com.hearthstead.entity.Profession.GUARD,
            com.hearthstead.entity.SettlerActivity.COMBAT, "Guard - combat"),
        // Life.
        new Pose("eat", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.EATING, "Eating"),
        new Pose("rest", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.RESTING, "Resting"),
        new Pose("sleep", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.SLEEPING, "Sleeping"),
        new Pose("cheer", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.CELEBRATING, "Celebrating"),
        new Pose("blessing_receive", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.IDLE, "Blessing - receive"),
        new Pose("flee", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.FLEEING, "Fleeing"),
        new Pose("idle", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.IDLE, "Idle"),
        // Trade idles (14 new clips; owner: "vil ogsa ha idle animations som
        // matcher jobben"). These are activity IDLE with profession != NONE,
        // not a WORK_* activity -- SettlerEntity.setupAnimationStates() gates
        // exactly one of the fourteen AnimationDefinitions per profession.
        // Several clips are shared by more than one profession there
        // (GUARD/ARCHER -> IDLE_SENTRY, SMITH/SMELTER -> IDLE_FORGE,
        // BAKER/MILLER -> IDLE_BAKER, COOK/BREWER -> IDLE_COOK,
        // MASON/CARPENTER/SAWYER -> IDLE_SIGHT_EDGE, BUTCHER/TANNER ->
        // IDLE_BLADE_BENCH); each row below poses the first profession
        // listed in that gate, so the pose key matches the
        // AnimationDefinition constant it exercises. Two lineup pages
        // (SHOWCASE_PLAN.md scenes 21-22): frontier/field trades, then
        // hearth/bench trades -- 7 and 7, not an arbitrary cut.
        new Pose("idle_farmer", com.hearthstead.entity.Profession.FARMER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Farmer - idle"),
        new Pose("idle_lumberer", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Lumberjack - idle"),
        new Pose("idle_miner", com.hearthstead.entity.Profession.MINER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Miner - idle"),
        new Pose("idle_courier", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Courier - idle"),
        new Pose("idle_sentry", com.hearthstead.entity.Profession.GUARD,
            com.hearthstead.entity.SettlerActivity.IDLE, "Guard - idle (sentry)"),
        new Pose("idle_fletcher", com.hearthstead.entity.Profession.FLETCHER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Fletcher - idle"),
        new Pose("idle_sight_edge", com.hearthstead.entity.Profession.MASON,
            com.hearthstead.entity.SettlerActivity.IDLE, "Mason - idle (sight edge)"),
        new Pose("idle_forge", com.hearthstead.entity.Profession.SMITH,
            com.hearthstead.entity.SettlerActivity.IDLE, "Smith - idle (forge)"),
        new Pose("idle_baker", com.hearthstead.entity.Profession.BAKER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Baker - idle"),
        new Pose("idle_cook", com.hearthstead.entity.Profession.COOK,
            com.hearthstead.entity.SettlerActivity.IDLE, "Cook - idle"),
        new Pose("idle_weaver", com.hearthstead.entity.Profession.WEAVER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Weaver - idle"),
        new Pose("idle_blade_bench", com.hearthstead.entity.Profession.BUTCHER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Butcher - idle (bench)"),
        new Pose("idle_scholar", com.hearthstead.entity.Profession.SCHOLAR,
            com.hearthstead.entity.SettlerActivity.IDLE, "Scholar - idle"),
        new Pose("idle_innkeeper", com.hearthstead.entity.Profession.INNKEEPER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Innkeeper - idle"),
        new Pose("idle_trader", com.hearthstead.entity.Profession.TRADER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Trade Steward - idle"),
    };

    private static final int LINEUP_PER_PAGE = 7;
    private static final double LINEUP_SPACING = 2.5;

    /** Poses the nearest settler, or "clear" to hand everyone back their AI. */
    private static int pose(CommandSourceStack source, String key) {
        ServerLevel level = source.getLevel();
        if ("clear".equals(key)) {
            int freed = 0;
            for (com.hearthstead.entity.SettlerEntity settler : posedNear(level,
                    source.getPosition(), 64.0)) {
                settler.setNoAi(false);
                settler.setCustomNameVisible(false);
                settler.removeTag(BLESSING_RECEIVE_POSE_TAG);
                freed++;
            }
            int n = freed;
            source.sendSuccess(() ->
                Component.literal("Released " + n + " posed settler(s)."), true);
            return n;
        }
        Pose pose = null;
        for (Pose candidate : POSES) {
            if (candidate.key().equals(key)) {
                pose = candidate;
                break;
            }
        }
        if (pose == null) {
            source.sendFailure(Component.literal("Unknown pose: " + key));
            return 0;
        }
        com.hearthstead.entity.SettlerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (com.hearthstead.entity.SettlerEntity settler : level.getEntitiesOfClass(
                com.hearthstead.entity.SettlerEntity.class,
                new net.minecraft.world.phys.AABB(source.getPosition(),
                    source.getPosition()).inflate(32.0))) {
            double d = settler.position().distanceToSqr(source.getPosition());
            if (d < best) {
                best = d;
                nearest = settler;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No settler within 32 blocks."));
            return 0;
        }
        applyPose(nearest, pose);
        com.hearthstead.entity.SettlerEntity posed = nearest;
        Pose applied = pose;
        source.sendSuccess(() -> Component.literal(
            posed.getSettlerName() + " posed: " + applied.label()), true);
        return 1;
    }

    private static void applyPose(com.hearthstead.entity.SettlerEntity settler, Pose pose) {
        settler.getNavigation().stop();
        settler.setNoAi(true);
        if (pose.profession() != com.hearthstead.entity.Profession.NONE) {
            settler.setProfessionProjection(pose.profession());
        }
        settler.setActivity(pose.activity());
        settler.removeTag(BLESSING_RECEIVE_POSE_TAG);
        if ("blessing_receive".equals(pose.key())) {
            settler.addTag(BLESSING_RECEIVE_POSE_TAG);
            settler.triggerBlessingReceive();
        }
        if (pose.activity() == com.hearthstead.entity.SettlerActivity.GATHERING_LOG) {
            settler.triggerGatherLog();
        }
    }

    /**
     * Re-fires the one-shot clips on every posed settler.
     *
     * <p>A gather stoop, a sergeant's leap, and Blessing acceptance all end
     * on their own clock, by design -- they are punctuation, not loops.
     * Holding one open would mean lying about the clip. So the camera pulses
     * them instead.
     *
     * <p>Deliberately keyed on activity alone (GATHERING_LOG / COMBAT), not
     * on activity-or-IDLE: IDLE_LUMBERER (the "idle_lumberer" pose) is a
     * profession LUMBERER settler legitimately sitting at activity IDLE, and
     * it is a LOOPING clip -- pulsing it would yank it into the one-shot
     * gather stoop every 2s and the trade-idle lineup pages would never show
     * their own clip on camera. Fixed here rather than left for the new
     * pages to inherit (found while wiring lineup pages 5-6, SHOWCASE_PLAN.md
     * scenes 21-22): the old bound matched nothing in the pre-existing POSES
     * array (no prior pose was LUMBERER+IDLE), so this was latent until now.
     */
    private static int pulse(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int fired = 0;
        for (com.hearthstead.entity.SettlerEntity settler : posedNear(level,
                source.getPosition(), 64.0)) {
            com.hearthstead.entity.SettlerActivity activity = settler.getActivity();
            if (settler.getTags().contains(BLESSING_RECEIVE_POSE_TAG)) {
                settler.triggerBlessingReceive();
                fired++;
            } else if (settler.getProfession() == com.hearthstead.entity.Profession.LUMBERER
                && activity == com.hearthstead.entity.SettlerActivity.GATHERING_LOG) {
                settler.triggerGatherLog();
                fired++;
            } else if (settler.getProfession() == com.hearthstead.entity.Profession.GUARD
                && activity == com.hearthstead.entity.SettlerActivity.COMBAT) {
                settler.triggerLeapStrike();
                fired++;
            }
        }
        int n = fired;
        source.sendSuccess(() -> Component.literal("Pulsed " + n + " one-shot(s)."), true);
        return n;
    }

    private static java.util.List<com.hearthstead.entity.SettlerEntity> posedNear(
        ServerLevel level, net.minecraft.world.phys.Vec3 around, double radius) {
        java.util.List<com.hearthstead.entity.SettlerEntity> found = new java.util.ArrayList<>();
        for (com.hearthstead.entity.SettlerEntity settler : level.getEntitiesOfClass(
                com.hearthstead.entity.SettlerEntity.class,
                new net.minecraft.world.phys.AABB(around, around).inflate(radius))) {
            if (settler.isNoAi()) {
                found.add(settler);
            }
        }
        return found;
    }

    /**
     * Spawns one settler per animation, in a labelled row facing the camera.
     *
     * <p>Seven to a page: wide enough to read a whole row of nameplates at
     * 1280x720 without them overlapping, which is the resolution the harness
     * films at.
     */
    private static int lineup(CommandSourceStack source, int page) {
        ServerLevel level = source.getLevel();
        net.minecraft.world.phys.Vec3 origin = source.getPosition();
        int first = page * LINEUP_PER_PAGE;
        if (first >= POSES.length) {
            source.sendFailure(Component.literal("No page " + page + "; "
                + ((POSES.length + LINEUP_PER_PAGE - 1) / LINEUP_PER_PAGE) + " pages exist."));
            return 0;
        }
        int last = Math.min(POSES.length, first + LINEUP_PER_PAGE);
        int count = last - first;
        // Centred on the caller, laid out along X, four blocks north of them,
        // all facing due south -- at the camera.
        double startX = origin.x - (count - 1) * LINEUP_SPACING / 2.0;
        double z = origin.z - 4.0;
        int spawned = 0;
        for (int i = first; i < last; i++) {
            Pose pose = POSES[i];
            com.hearthstead.entity.SettlerEntity settler =
                com.hearthstead.registry.ModEntities.SETTLER.get().create(level);
            if (settler == null) {
                continue;
            }
            double x = startX + (i - first) * LINEUP_SPACING;
            net.minecraft.core.BlockPos ground = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.core.BlockPos.containing(x, origin.y, z));
            double y = Math.abs(ground.getY() - origin.y) <= 6 ? ground.getY() : origin.y;
            // Yaw 0 is +Z (south) -- toward the caller, who stands 4 south.
            settler.moveTo(x, y, z, 0.0F, 0.0F);
            settler.setYHeadRot(0.0F);
            settler.setYBodyRot(0.0F);
            settler.setSettlerName(pose.label());
            settler.setCustomNameVisible(true);
            settler.setPersistenceRequired();
            applyPose(settler, pose);
            level.addFreshEntity(settler);
            spawned++;
        }
        int n = spawned;
        int shown = page;
        source.sendSuccess(() -> Component.literal("Lineup page " + shown + ": "
            + n + " settlers posed."), true);
        return n;
    }


    /**
     * Answers the hardest question in the mod: why is this settler doing
     * nothing?
     *
     * <p>An idle settler looks identical whether the AI chose rest or
     * silently failed (the KF-014 lesson), and every live diagnosis so far
     * has cost a chain of guessing commands. This dumps the actual decision
     * inputs — phase, needs, employment, posting, running goals, and for a
     * courier the exact predicates CourierWorkGoal reads — for the nearest
     * settler, so one command replaces the guessing.
     */
    private static int why(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        com.hearthstead.entity.SettlerEntity settler = null;
        double best = Double.MAX_VALUE;
        for (com.hearthstead.entity.SettlerEntity candidate : level.getEntitiesOfClass(
                com.hearthstead.entity.SettlerEntity.class,
                new net.minecraft.world.phys.AABB(source.getPosition(),
                    source.getPosition()).inflate(16.0))) {
            double d = candidate.position().distanceToSqr(source.getPosition());
            if (d < best) {
                best = d;
                settler = candidate;
            }
        }
        if (settler == null) {
            source.sendFailure(Component.literal("No settler within 16 blocks."));
            return 0;
        }
        com.hearthstead.entity.SettlerEntity s0 = settler;
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(String.format(java.util.Locale.ROOT,
            "%s -- %s, activity %s, noAi=%s", s0.getSettlerName(),
            s0.getProfession().key(), s0.getActivity().key(), s0.isNoAi()));
        lines.add(String.format(java.util.Locale.ROOT,
            "needs: energy %.0f hunger %.0f morale %.0f, bag %d",
            s0.getEnergy(), s0.getHunger(), s0.getMorale(), bagCount(s0)));
        lines.add("effort: " + s0.effortDescribe());
        com.hearthstead.settlement.DayPhase phase = s0.dayPhase();
        lines.add(String.format(java.util.Locale.ROOT,
            "clock: daytime %d -> %s (work=%s rest=%s meal=%s)",
            level.getDayTime() % 24000L, phase, phase.work(), phase.rest(), phase.meal()));
        com.hearthstead.settlement.Settlement s = s0.settlement();
        if (s == null) {
            lines.add("settlement: NONE (bound=" + s0.isBound() + ") <- every goal is off");
        } else {
            com.hearthstead.settlement.Building employer =
                com.hearthstead.settlement.Employment.employerOf(s, s0.getUUID());
            if (employer == null) {
                lines.add("employment: none");
            } else {
                lines.add(String.format(java.util.Locale.ROOT,
                    "employment: %s valid=%s anchor=%s bounds=%s",
                    employer.type.id(), employer.valid, employer.anchor,
                    employer.bounds == null ? "null" : employer.bounds.toString()));
            }
            com.hearthstead.settlement.Schedule.Posting post =
                com.hearthstead.settlement.Schedule.postFor(s, s0, phase);
            lines.add("posting: " + (post == null ? "none"
                : post.where() + " (" + post.reason() + ")"));
            if (s0.getProfession() == com.hearthstead.entity.Profession.COURIER) {
                com.hearthstead.block.HearthBlockEntity hearth = s0.hearth();
                int haulable = 0;
                if (hearth != null) {
                    var inv = hearth.getInventory();
                    for (int i = 0; i < inv.getSlots(); i++) {
                        var stack = inv.getStackInSlot(i);
                        if (!stack.isEmpty() && !stack.has(
                                net.minecraft.core.component.DataComponents.FOOD)) {
                            haulable += stack.getCount();
                        }
                    }
                }
                com.hearthstead.settlement.Building warehouse = null;
                for (com.hearthstead.settlement.Building b : s.buildings) {
                    if (b.type == com.hearthstead.building.BuildingType.WAREHOUSE
                        && b.valid) {
                        warehouse = b;
                        break;
                    }
                }
                int containers = warehouse == null ? -1
                    : com.hearthstead.settlement.warehouse.WarehouseStorage
                        .of(level, warehouse).containers().size();
                lines.add(String.format(java.util.Locale.ROOT,
                    "courier: hearth=%s haulable=%d warehouse=%s containers=%d",
                    hearth != null, haulable,
                    warehouse == null ? "none" : warehouse.anchor, containers));
            }
        }
        StringBuilder running = new StringBuilder();
        s0.goalSelector.getAvailableGoals().forEach(wrapped -> {
            if (wrapped.isRunning()) {
                if (running.length() > 0) {
                    running.append(", ");
                }
                running.append(wrapped.getGoal().getClass().getSimpleName());
            }
        });
        lines.add("running goals: " + (running.length() == 0 ? "none" : running));
        lines.add("navigation: " + navigationWhy(s0));
        lines.add("last route failure: " + s0.routeFailureNote());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), true);
        }
        return 1;
    }

    /** Read-only current-path snapshot for live stuck-route diagnosis. */
    private static String navigationWhy(com.hearthstead.entity.SettlerEntity settler) {
        var navigation = settler.getNavigation();
        var path = navigation.getPath();
        if (path == null) {
            return "path=none target=" + navigation.getTargetPos()
                + " done=" + navigation.isDone();
        }
        int nodes = path.getNodeCount();
        net.minecraft.core.BlockPos end = nodes <= 0 ? null
            : path.getNodePos(nodes - 1);
        return "target=" + path.getTarget() + " navTarget="
            + navigation.getTargetPos() + " done=" + path.isDone()
            + " reachable=" + path.canReach() + " next="
            + path.getNextNodeIndex() + " nodes=" + nodes + " end=" + end;
    }

    private static int bagCount(com.hearthstead.entity.SettlerEntity settler) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private HearthsteadCommand() {
    }
}
