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
        com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root =
        dispatcher.register(Commands.literal("hearthstead")
            .then(Commands.literal("merchantwestqa")
                .requires(src -> src.hasPermission(4) && com.hearthstead.util.QaTrace.ENABLED)
                .then(Commands.literal("prepare").executes(ctx -> com.hearthstead.qa.MerchantWestPublicationQa.command(ctx.getSource(), "prepare")))
                .then(Commands.literal("capture").executes(ctx -> com.hearthstead.qa.MerchantWestPublicationQa.command(ctx.getSource(), "capture")))
                .then(Commands.literal("assert").executes(ctx -> com.hearthstead.qa.MerchantWestPublicationQa.command(ctx.getSource(), "assert"))))
            .then(Commands.literal("socialqa")
                .requires(src -> src.hasPermission(4) && com.hearthstead.util.QaTrace.ENABLED)
                .then(Commands.literal("arm").executes(ctx ->
                    com.hearthstead.qa.SocialPairObservationQa.arm(ctx.getSource()))))
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
            .then(Commands.literal("demo").requires(src -> src.hasPermission(2))
                .executes(ctx -> demo(ctx.getSource())))
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
            .then(com.hearthstead.qa.watchdog.WorkerWatchdog.command())
            .then(com.hearthstead.qa.soak.SoakQa.command())
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
            .then(Commands.literal("packlineup").requires(src -> src.hasPermission(2))
                .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 20))
                    .executes(ctx -> packLineup(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page")))))
            .then(Commands.literal("packfill").requires(src -> src.hasPermission(2))
                .then(Commands.argument("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> packFill(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "percent")))))
            .then(Commands.literal("raiderfilm").requires(src -> src.hasPermission(2))
                .then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        b.suggest("brute");
                        b.suggest("skirmisher");
                        b.suggest("clear");
                        return b.buildFuture();
                    })
                    .then(Commands.argument("pos",
                            net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                        .executes(ctx -> raiderFilm(ctx.getSource(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "kind"),
                            net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(ctx, "pos"))))))
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
        // Bannerhold is the player-facing name; /hearthstead stays for saves,
        // scripts and muscle memory. The alias shares the exact same tree.
        dispatcher.register(Commands.literal("bannerhold").redirect(root));
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
        // produces annoyance rather than dread (D-A3-3). Read from the real
        // schedule: before Declare Ready no raid can come at all.
        source.sendSuccess(() -> com.hearthstead.settlement.raid.RaidThreatInfo.line(
            s, source.getLevel().getDayTime()), true);
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
        // Scripted filming poses. Each key below is listed in SCRIPTED_KEYS:
        // applyPose tags it and onPoseTick keeps re-asserting the render cue
        // (social cue, held bow draw, bag clock, circle walk) every tick.
        new Pose("bard", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.PLAYING_MUSIC, "Bard - playing"),
        new Pose("chat", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.SOCIALIZING, "Village - chat"),
        new Pose("listen", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.SOCIALIZING, "Village - listen"),
        new Pose("village_welcome", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.SOCIALIZING, "Village - welcome"),
        new Pose("bow_draw", com.hearthstead.entity.Profession.ARCHER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Archer - bow drawn"),
        new Pose("inn_welcome", com.hearthstead.entity.Profession.INNKEEPER,
            com.hearthstead.entity.SettlerActivity.IDLE, "Innkeeper - welcome"),
        new Pose("bag_unload", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.SORTING, "Courier - bag to chest"),
        new Pose("walk", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.IDLE, "Walk"),
        new Pose("walk_guard", com.hearthstead.entity.Profession.GUARD,
            com.hearthstead.entity.SettlerActivity.PATROLLING, "Guard - walk"),
        new Pose("walk_laden", com.hearthstead.entity.Profession.COURIER,
            com.hearthstead.entity.SettlerActivity.CARRYING, "Courier - laden walk"),
        // Motion-engine filming poses (presentation only; no damage, catch or wool).
        new Pose("shear", com.hearthstead.entity.Profession.HERDER,
            com.hearthstead.entity.SettlerActivity.WORK_SHEAR, "Herder - shear"),
        new Pose("fish", com.hearthstead.entity.Profession.FISHER,
            com.hearthstead.entity.SettlerActivity.WORK_FISH, "Fisher - cast and land"),
        new Pose("melee", com.hearthstead.entity.Profession.GUARD,
            com.hearthstead.entity.SettlerActivity.PATROLLING, "Guard - sword strike"),
        new Pose("carcass", com.hearthstead.entity.Profession.HUNTER,
            com.hearthstead.entity.SettlerActivity.HAULING_CARCASS, "Hunter - carcass carry"),
        new Pose("butcher", com.hearthstead.entity.Profession.HUNTER,
            com.hearthstead.entity.SettlerActivity.WORK_BUTCHER, "Hunter - butcher"),
        new Pose("skin", com.hearthstead.entity.Profession.HUNTER,
            com.hearthstead.entity.SettlerActivity.WORK_SKIN, "Hunter - skin"),
        new Pose("container_cycle", com.hearthstead.entity.Profession.LUMBERER,
            com.hearthstead.entity.SettlerActivity.GATHERING_LOG, "Lumberjack - bag down, pickup, stow, lift"),
    };

    /** Tag carried only by settlers the pose command is scripting. */
    private static final String SCRIPTED_POSE_TAG = "hearthstead_pose_scripted";
    private static final java.util.Set<String> SCRIPTED_KEYS = java.util.Set.of(
        "chat", "listen", "village_welcome", "bow_draw", "inn_welcome",
        "bag_unload", "walk", "walk_guard", "walk_laden", "fish", "melee", "container_cycle",
        "carcass", "butcher", "skin");
    /** Circle radius fits inside one lineup slot (LINEUP_SPACING 2.5). */
    private static final double POSE_WALK_RADIUS = 1.0;
    /** Blocks per tick; an ordinary settler stroll. */
    private static final double POSE_WALK_SPEED = 0.11;
    private static final int POSE_CUE_PERIOD = 60;

    /** Per-settler script state; weak so an unloaded/removed settler is dropped. */
    private static final class ScriptedPose {
        final String key;
        final net.minecraft.world.phys.Vec3 center;
        final java.util.UUID transferId = java.util.UUID.randomUUID();
        net.minecraft.core.BlockPos bagAnchor;
        net.minecraft.core.BlockPos chestPos;
        float bagYaw;
        int age;

        ScriptedPose(String key, net.minecraft.world.phys.Vec3 center) {
            this.key = key;
            this.center = center;
        }
    }

    private static final java.util.Map<com.hearthstead.entity.SettlerEntity, ScriptedPose>
        SCRIPTED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
            HearthsteadCommand::onPoseTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
            HearthsteadCommand::onRaiderFilmTick);
    }

    // ---- Review aid: every job's back container, empty / half / full / full at top sack tier. ----
    private static final String PACK_LINEUP_TAG = "hearthstead_pack_lineup";
    private static final com.hearthstead.entity.Profession[] PACK_LINEUP = {
        com.hearthstead.entity.Profession.COURIER, com.hearthstead.entity.Profession.MINER,
        com.hearthstead.entity.Profession.BUILDER, com.hearthstead.entity.Profession.BAKER,
        com.hearthstead.entity.Profession.FARMER, com.hearthstead.entity.Profession.FISHER,
        com.hearthstead.entity.Profession.HUNTER, com.hearthstead.entity.Profession.HERDER,
        com.hearthstead.entity.Profession.SMITH, com.hearthstead.entity.Profession.TANNER,
        com.hearthstead.entity.Profession.WEAVER, com.hearthstead.entity.Profession.BREWER,
        com.hearthstead.entity.Profession.COOK, com.hearthstead.entity.Profession.MASON,
        com.hearthstead.entity.Profession.SCHOLAR, com.hearthstead.entity.Profession.TRADER,
        com.hearthstead.entity.Profession.HEALER, com.hearthstead.entity.Profession.LUMBERER};

    /** Page N: 4 professions in rows (north to south), columns empty, half, full, full at sack tier 3. */
    private static int packLineup(CommandSourceStack source, int page) {
        ServerLevel level = source.getLevel();
        net.minecraft.world.phys.Vec3 origin = source.getPosition();
        for (com.hearthstead.entity.SettlerEntity old : level.getEntitiesOfClass(
                com.hearthstead.entity.SettlerEntity.class, new net.minecraft.world.phys.AABB(origin, origin).inflate(48.0),
                e -> e.getTags().contains(PACK_LINEUP_TAG))) {
            old.discard();
        }
        int first = page * 4;
        if (first >= PACK_LINEUP.length) {
            source.sendFailure(Component.literal("No page " + page));
            return 0;
        }
        float[] fills = {0.0F, 0.5F, 1.0F, 1.0F};
        int spawned = 0;
        for (int row = 0; row < 4 && first + row < PACK_LINEUP.length; row++) {
            com.hearthstead.entity.Profession profession = PACK_LINEUP[first + row];
            for (int col = 0; col < fills.length; col++) {
                com.hearthstead.entity.SettlerEntity settler =
                    com.hearthstead.registry.ModEntities.SETTLER.get().create(level);
                if (settler == null) {
                    continue;
                }
                double x = origin.x + (col - 1.5) * 1.6;
                double z = origin.z - 5.0 - row * 2.2;
                // Yaw 180 faces north: the camera south of the grid sees the backs.
                settler.moveTo(x, origin.y, z, 180.0F, 0.0F);
                settler.setYHeadRot(180.0F);
                settler.setYBodyRot(180.0F);
                settler.setNoAi(true);
                settler.setPersistenceRequired();
                settler.addTag(PACK_LINEUP_TAG);
                settler.setProfessionProjection(profession);
                settler.setSettlerName(profession.key() + " " + (col == 3 ? "full T3" : (int) (fills[col] * 100) + "%"));
                settler.setCustomNameVisible(true);
                if (col == 3) {
                    settler.setHaulGear(com.hearthstead.settlement.development.HaulGear.pack(3, false));
                }
                int want = Math.round(settler.getCarryCapacity() * fills[col]);
                for (int slot = 0; slot < settler.bag.getContainerSize() && want > 0; slot++) {
                    int n = Math.min(64, want);
                    settler.bag.setItem(slot, new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.COBBLESTONE, n));
                    want -= n;
                }
                level.addFreshEntity(settler);
                spawned++;
            }
        }
        int n = spawned;
        source.sendSuccess(() -> Component.literal("Pack lineup page " + page + ": " + n + " settlers"), true);
        return n;
    }

    /** Sets every pack-lineup settler's real bag to PERCENT of capacity (cobblestone), for fill clips. */
    private static int packFill(CommandSourceStack source, int percent) {
        int n = 0;
        for (com.hearthstead.entity.SettlerEntity settler : source.getLevel().getEntitiesOfClass(
                com.hearthstead.entity.SettlerEntity.class,
                new net.minecraft.world.phys.AABB(source.getPosition(), source.getPosition()).inflate(48.0),
                e -> e.getTags().contains(PACK_LINEUP_TAG))) {
            settler.bag.clearContent();
            int want = Math.round(settler.getCarryCapacity() * percent / 100.0F);
            for (int slot = 0; slot < settler.bag.getContainerSize() && want > 0; slot++) {
                int k = Math.min(64, want);
                settler.bag.setItem(slot, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.COBBLESTONE, k));
                want -= k;
            }
            n++;
        }
        return n;
    }

    // ---- Filming aid: a NoAI raider that cycles its presentation events. ----
    // Presentation only (entity events): no target, no damage, no raid state.
    private static final String FILM_RAIDER_TAG = "hearthstead_film_raider";
    private static final java.util.Map<com.hearthstead.entity.RaiderEntity, int[]> FILM_RAIDERS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static int raiderFilm(CommandSourceStack source, String kind, net.minecraft.world.phys.Vec3 pos) {
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        for (com.hearthstead.entity.RaiderEntity old : level.getEntitiesOfClass(
                com.hearthstead.entity.RaiderEntity.class, new net.minecraft.world.phys.AABB(pos, pos).inflate(48.0),
                r -> r.getTags().contains(FILM_RAIDER_TAG))) {
            FILM_RAIDERS.remove(old);
            old.discard();
        }
        if ("clear".equals(kind)) {
            return 1;
        }
        boolean brute = "brute".equals(kind);
        com.hearthstead.entity.RaiderEntity raider =
            com.hearthstead.registry.ModEntities.RAIDER.get().create(level);
        if (raider == null) {
            return 0;
        }
        raider.setVariant(brute ? com.hearthstead.entity.RaiderEntity.Variant.BRUTE
            : com.hearthstead.entity.RaiderEntity.Variant.SKIRMISHER);
        raider.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        raider.setYHeadRot(0.0F);
        raider.setYBodyRot(0.0F);
        raider.setNoAi(true);
        raider.setPersistenceRequired();
        raider.setInvulnerable(true);
        raider.addTag(FILM_RAIDER_TAG);
        level.addFreshEntity(raider);
        FILM_RAIDERS.put(raider, new int[] {brute ? 1 : 0, 0});
        source.sendSuccess(() -> Component.literal("film raider: " + kind), false);
        return 1;
    }

    private static void onRaiderFilmTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (FILM_RAIDERS.isEmpty()) {
            return;
        }
        byte[] bruteMoves = {com.hearthstead.entity.RaiderEntity.EV_BRUTE_CLUB_STRIKE,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_TAUNT,
            com.hearthstead.entity.RaiderEntity.EV_BREACH_SLAM};
        byte[] skirmisherMoves = {com.hearthstead.entity.RaiderEntity.EV_RAIDER_LIGHT,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_HEAVY,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_HOP_BACK,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_DODGE_LEFT,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_DODGE_RIGHT,
            com.hearthstead.entity.RaiderEntity.EV_STRIKE,
            com.hearthstead.entity.RaiderEntity.EV_RAIDER_TAUNT};
        java.util.List<java.util.Map.Entry<com.hearthstead.entity.RaiderEntity, int[]>> live;
        synchronized (FILM_RAIDERS) {
            live = new java.util.ArrayList<>(FILM_RAIDERS.entrySet());
        }
        for (var entry : live) {
            com.hearthstead.entity.RaiderEntity raider = entry.getKey();
            if (raider == null || raider.isRemoved()) {
                FILM_RAIDERS.remove(raider);
                continue;
            }
            int[] state = entry.getValue();
            if (state[1]++ % 45 == 0) {
                byte[] moves = state[0] == 1 ? bruteMoves : skirmisherMoves;
                raider.level().broadcastEntityEvent(raider, moves[(state[1] / 45) % moves.length]);
            }
        }
    }

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
                releaseScripted(settler);
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
        releaseScripted(settler);
        if (SCRIPTED_KEYS.contains(pose.key())) {
            ScriptedPose script = new ScriptedPose(pose.key(),
                settler.position().add(-POSE_WALK_RADIUS, 0.0, 0.0));
            if ("bow_draw".equals(pose.key())) {
                settler.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new ItemStack(Items.BOW));
            } else if ("inn_welcome".equals(pose.key())) {
                // INN_WELCOME expects both hands free (the server reserves them).
                settler.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
            } else if ("bag_unload".equals(pose.key())) {
                // Visual projection only: a placed SACK record at the feet's
                // front block and a transfer toward the block beyond it. No
                // block is placed and no bag/ledger inventory is touched.
                settler.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                net.minecraft.core.Direction facing =
                    net.minecraft.core.Direction.fromYRot(settler.getYRot());
                // Same layout as the real GroundedBagUnload: the sack anchors
                // on the settler's own block and the chest stands one ahead.
                script.bagAnchor = settler.blockPosition();
                script.chestPos = script.bagAnchor.relative(facing);
                script.bagYaw = facing.toYRot();
            }
            settler.addTag(SCRIPTED_POSE_TAG);
            SCRIPTED.put(settler, script);
            tickScripted(settler, script);
        }
    }

    /** Display carcass for the hunter filming poses (released with the pose). */
    private static ItemStack filmCarcass() {
        return com.hearthstead.item.CarcassItem.create(com.hearthstead.item.CarcassData.of(
            net.minecraft.world.entity.EntityType.PIG, java.util.List.of()));
    }

    /** Undoes every render cue a scripted pose published. Safe on any settler. */
    private static void releaseScripted(com.hearthstead.entity.SettlerEntity settler) {
        ScriptedPose script = SCRIPTED.remove(settler);
        if (!settler.getTags().contains(SCRIPTED_POSE_TAG) && script == null) {
            return;
        }
        settler.removeTag(SCRIPTED_POSE_TAG);
        if (settler.isUsingItem()) {
            settler.stopUsingItem();
        }
        settler.setVillageSocial(com.hearthstead.entity.VillageSocial.NONE, 0L);
        settler.setInnkeeperSocial(0, 0L);
        if (script != null && "fish".equals(script.key)) {
            settler.setFisherCycleTick(-1);
        }
        if (script != null && ("carcass".equals(script.key) || "butcher".equals(script.key)
                || "skin".equals(script.key))) {
            settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        if (script != null && "container_cycle".equals(script.key)) {
            settler.clearWorkContainer();
            settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        if (script != null && script.bagAnchor != null) {
            settler.clearBagTransferPresentation(script.transferId);
            if (script.bagAnchor.equals(settler.placedWorkContainerPos())) {
                settler.clearWorkContainer();
            }
        }
    }

    /** Keeps every scripted filming pose alive; touches only tagged, AI-off settlers. */
    private static void onPoseTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (SCRIPTED.isEmpty()) {
            return;
        }
        java.util.List<java.util.Map.Entry<com.hearthstead.entity.SettlerEntity, ScriptedPose>> live;
        synchronized (SCRIPTED) {
            live = new java.util.ArrayList<>(SCRIPTED.entrySet());
        }
        for (java.util.Map.Entry<com.hearthstead.entity.SettlerEntity, ScriptedPose> entry : live) {
            com.hearthstead.entity.SettlerEntity settler = entry.getKey();
            if (settler == null) {
                continue;
            }
            if (settler.isRemoved() || !settler.isNoAi()
                || !settler.getTags().contains(SCRIPTED_POSE_TAG)) {
                SCRIPTED.remove(settler);
                continue;
            }
            ScriptedPose script = entry.getValue();
            script.age++;
            tickScripted(settler, script);
        }
    }

    private static void tickScripted(com.hearthstead.entity.SettlerEntity settler, ScriptedPose script) {
        long now = settler.level().getGameTime();
        switch (script.key) {
            case "chat", "listen" -> {
                int mode = "chat".equals(script.key)
                    ? com.hearthstead.entity.VillageSocial.CHAT
                    : com.hearthstead.entity.VillageSocial.LISTEN;
                if (settler.villageSocialMode() == com.hearthstead.entity.VillageSocial.NONE) {
                    settler.setVillageSocial(mode, now);
                }
            }
            case "village_welcome" -> {
                if (script.age % POSE_CUE_PERIOD == 0) {
                    settler.setVillageSocial(com.hearthstead.entity.VillageSocial.WELCOME, now);
                }
            }
            case "inn_welcome" -> {
                if (script.age % POSE_CUE_PERIOD == 0) {
                    settler.setInnkeeperSocial(com.hearthstead.entity.InnkeeperAtmosphere.WELCOME, now);
                }
            }
            case "bow_draw" -> {
                if (!settler.getMainHandItem().is(Items.BOW)) {
                    settler.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(Items.BOW));
                }
                if (!settler.isUsingItem()) {
                    settler.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            }
            case "bag_unload" -> {
                // 80-tick authored cycle, then a 10-tick settled hold, looped.
                int cycle = script.age % 90;
                if (cycle == 0) {
                    settler.placeWorkContainer(
                        com.hearthstead.entity.WorkContainerKind.SACK, script.bagAnchor);
                    settler.triggerBagToChestUnload();
                }
                settler.publishBagTransferPresentation(new com.hearthstead.entity.BagTransferPresentation(
                    script.transferId, script.bagAnchor, script.bagYaw, script.chestPos,
                    Math.min(80, cycle), false, new ItemStack(Items.BREAD, 8)));
            }
            case "container_cycle" -> {
                // The lumberer's portable-container beats on a 150-tick loop,
                // presentation only: a projected SACK anchor at the front-left
                // cell and a display log in the free hand; no inventory moves.
                int cycle = script.age % 150;
                net.minecraft.core.Direction facing =
                    net.minecraft.core.Direction.fromYRot(settler.getYRot());
                net.minecraft.core.BlockPos spot = settler.blockPosition().relative(facing)
                    .relative(facing.getCounterClockWise());
                switch (cycle) {
                    case 0 -> {
                        settler.placeWorkContainer(com.hearthstead.entity.WorkContainerKind.SACK, spot);
                        settler.triggerWorkContainerDown();
                    }
                    case 40 -> settler.triggerGroundItemPickup();
                    case 52 -> settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                        new ItemStack(Items.OAK_LOG));
                    case 70 -> settler.triggerWorkContainerStow();
                    case 82 -> settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                        ItemStack.EMPTY);
                    case 100 -> settler.triggerWorkContainerUp();
                    case 130 -> settler.clearWorkContainer();
                    default -> {
                    }
                }
            }
            case "fish" -> {
                // Drive the synced 300-tick catch cycle the fisher pose reads.
                settler.setFisherCycleTick(script.age % 300);
            }
            case "melee" -> {
                // Presentation events only: no target, no ticket, no damage.
                // Cycles light A, light B, finisher, heavy, shield bash.
                byte[] moves = {com.hearthstead.entity.SettlerEntity.EV_MELEE,
                    com.hearthstead.entity.SettlerEntity.EV_GUARD_LIGHT_B,
                    com.hearthstead.entity.SettlerEntity.EV_GUARD_FINISHER,
                    com.hearthstead.entity.SettlerEntity.EV_GUARD_HEAVY,
                    com.hearthstead.entity.SettlerEntity.EV_GUARD_SHIELD_BASH};
                if (script.age % 30 == 0) {
                    settler.level().broadcastEntityEvent(settler, moves[(script.age / 30) % moves.length]);
                }
            }
            case "butcher", "skin" -> {
                if (!com.hearthstead.item.CarcassItem.isCarcass(settler.getOffhandItem())) {
                    settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, filmCarcass());
                }
            }
            case "walk", "walk_guard", "walk_laden", "carcass" -> {
                if ("carcass".equals(script.key)
                    && !com.hearthstead.item.CarcassItem.isCarcass(settler.getOffhandItem())) {
                    settler.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, filmCarcass());
                }
                double angle = script.age * POSE_WALK_SPEED / POSE_WALK_RADIUS;
                double x = script.center.x + POSE_WALK_RADIUS * Math.cos(angle);
                double z = script.center.z + POSE_WALK_RADIUS * Math.sin(angle);
                // Heading is the circle's tangent; MC yaw: dx=-sin, dz=cos.
                float yaw = (float) Math.toDegrees(Math.atan2(Math.sin(angle), Math.cos(angle)));
                settler.setPos(x, script.center.y, z);
                settler.setYRot(yaw);
                settler.setYBodyRot(yaw);
                settler.setYHeadRot(yaw);
            }
            default -> {
            }
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
            } else if (settler.getTags().contains(SCRIPTED_POSE_TAG)) {
                // Restart the scripted one-shot cues on the camera's beat.
                ScriptedPose script = SCRIPTED.get(settler);
                if (script != null) {
                    long now = level.getGameTime();
                    switch (script.key) {
                        case "inn_welcome" -> settler.setInnkeeperSocial(
                            com.hearthstead.entity.InnkeeperAtmosphere.WELCOME, now);
                        case "village_welcome" -> settler.setVillageSocial(
                            com.hearthstead.entity.VillageSocial.WELCOME, now);
                        case "chat" -> settler.setVillageSocial(
                            com.hearthstead.entity.VillageSocial.CHAT, now);
                        case "listen" -> settler.setVillageSocial(
                            com.hearthstead.entity.VillageSocial.LISTEN, now);
                        case "bag_unload" -> script.age = -1;
                        default -> {
                        }
                    }
                    fired++;
                }
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
                var requestData = com.hearthstead.settlement.request.RequestLedgerSavedData.existing(level);
                var requestLedger = requestData == null ? null : requestData.existing(s.id);
                lines.add("courier ledger: " + (requestData == null ? "missing"
                    : requestData.rootQuarantined() ? "root quarantined"
                    : requestLedger == null ? "settlement ledger missing"
                    : requestLedger.quarantined() ? "quarantined: " + requestLedger.quarantineReason()
                    : "ready, active=" + requestLedger.active().size()));
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
