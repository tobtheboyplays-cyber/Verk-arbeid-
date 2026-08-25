package com.hearthstead.command;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
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
            .then(Commands.literal("demo").executes(ctx -> demo(ctx.getSource())))
            .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
            .then(Commands.literal("recruit").requires(src -> src.hasPermission(2))
                .executes(ctx -> recruit(ctx.getSource())))
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
        return 1;
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

    private static int recruit(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            s.recruitProgress = Math.max(0, s.recruitTarget - 1);
        }
        SettlementManager.data(level).setDirty();
        source.sendSuccess(() ->
            Component.translatable("hearthstead.command.recruit_forced"), true);
        return 1;
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
        new Pose("flee", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.FLEEING, "Fleeing"),
        new Pose("idle", com.hearthstead.entity.Profession.NONE,
            com.hearthstead.entity.SettlerActivity.IDLE, "Idle"),
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
        if (pose.activity() == com.hearthstead.entity.SettlerActivity.GATHERING_LOG) {
            settler.triggerGatherLog();
        }
    }

    /**
     * Re-fires the one-shot clips on every posed settler.
     *
     * <p>A gather stoop and a sergeant's leap both end on their own clock, by
     * design -- they are punctuation, not loops. Holding one open would mean
     * lying about the clip. So the camera pulses them instead.
     */
    private static int pulse(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int fired = 0;
        for (com.hearthstead.entity.SettlerEntity settler : posedNear(level,
                source.getPosition(), 64.0)) {
            com.hearthstead.entity.SettlerActivity activity = settler.getActivity();
            if (settler.getProfession() == com.hearthstead.entity.Profession.LUMBERER
                && (activity == com.hearthstead.entity.SettlerActivity.IDLE
                    || activity == com.hearthstead.entity.SettlerActivity.GATHERING_LOG)) {
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
        lines.add("last route failure: " + s0.routeFailureNote());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), true);
        }
        return 1;
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
