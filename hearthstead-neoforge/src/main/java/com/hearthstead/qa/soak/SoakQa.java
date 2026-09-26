package com.hearthstead.qa.soak;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.workzone.WorkZone;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator-only helpers for building a disposable soak village from a script
 * (RCON). The script places real blocks with vanilla /fill and /setblock; these
 * commands only do what vanilla commands cannot: register a surveyed-shape
 * building behind an already placed plaque (the same seam GameTestFixtures
 * uses), commit a Work Zone, and hire settlers through {@link Employment#hire}.
 *
 * <p>Never used by ordinary play. Every command requires permission level 2.
 */
public final class SoakQa {
    private SoakQa() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("soakqa").requires(src -> src.hasPermission(2))
            .then(Commands.literal("anchor")
                .then(Commands.argument("mode", StringArgumentType.word())
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                            .then(Commands.argument("x2", IntegerArgumentType.integer())
                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                    .executes(SoakQa::anchor)))))))
            .then(Commands.literal("register")
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .then(Commands.argument("plaque", BlockPosArgument.blockPos())
                                .then(Commands.argument("anchor", BlockPosArgument.blockPos())
                                    .executes(SoakQa::register)))))))
            .then(Commands.literal("zone")
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(SoakQa::zone)))))
            .then(Commands.literal("hire")
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
                        .executes(SoakQa::hire))))
            .then(Commands.literal("research").executes(SoakQa::research))
            .then(Commands.literal("merchant")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(SoakQa::merchant)))
            .then(Commands.literal("raid").executes(ctx -> raid(ctx, true))
                .then(Commands.literal("silent").executes(ctx -> raid(ctx, false))))
            .then(Commands.literal("raidprobe").executes(SoakQa::raidProbe))
            .then(Commands.literal("feed")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .executes(SoakQa::feed)))
            .then(Commands.literal("status").executes(SoakQa::status));
    }

    private static Settlement settlementAt(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos pos = BlockPos.containing(src.getPosition());
        Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double d = s.center.distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static int fail(CommandSourceStack src, String message) {
        src.sendFailure(Component.literal(message));
        return 0;
    }

    private static int ok(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int anchor(CommandContext<CommandSourceStack> ctx) {
        boolean add = "add".equals(StringArgumentType.getString(ctx, "mode"));
        int n = SoakChunkAnchor.apply(ctx.getSource().getLevel(),
            IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "z1"),
            IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "z2"), add);
        return ok(ctx.getSource(), (add ? "anchored " : "released ") + n + " chunks (ticking)");
    }

    private static BuildingType type(String id) {
        for (BuildingType t : BuildingType.values()) {
            if (t.id().equals(id) || t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    private static int register(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BuildingType type = type(StringArgumentType.getString(ctx, "type"));
        Settlement s = settlementAt(src);
        if (type == null || s == null) {
            return fail(src, "unknown type or no settlement");
        }
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        BlockPos plaque = BlockPosArgument.getBlockPos(ctx, "plaque");
        BlockPos anchor = BlockPosArgument.getBlockPos(ctx, "anchor");
        if (!(level.getBlockState(plaque).getBlock() instanceof com.hearthstead.block.PlaqueBlock)) {
            return fail(src, "no plaque block at " + plaque.toShortString());
        }
        for (Building existing : s.buildings) {
            if (plaque.equals(existing.plaquePos)) {
                return ok(src, "already registered " + existing.type.id());
            }
        }
        Building b = new Building(UUID.randomUUID(), type, plaque, anchor, BoundingBox.fromCorners(from, to));
        b.valid = true;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                b.beds.add(p.immutable());
            }
        }
        s.buildings.add(b);
        SettlementSavedData.get(level).setDirty();
        SettlementSavedData.get(level).buildingManager.assignFreeBeds(level, s, b);
        return ok(src, "registered " + type.id() + " beds=" + b.beds.size() + " in " + s.name);
    }

    private static Building first(Settlement s, BuildingType type) {
        for (Building b : s.buildings) {
            if (b.type == type && b.valid) {
                return b;
            }
        }
        return null;
    }

    private static int zone(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BuildingType type = type(StringArgumentType.getString(ctx, "type"));
        Settlement s = settlementAt(src);
        Building b = s == null || type == null ? null : first(s, type);
        WorkZone.Type zoneType = type == null ? null : WorkZone.Type.fromBuilding(type);
        if (b == null || zoneType == null) {
            return fail(src, "no building/zone type");
        }
        int revision = b.workZoneRevision();
        WorkZone zone = WorkZone.between(s.id, b.id, zoneType, level.dimension().location(),
            BlockPosArgument.getBlockPos(ctx, "from"), BlockPosArgument.getBlockPos(ctx, "to"), revision + 1);
        boolean committed = b.commitWorkZone(revision, zone);
        SettlementSavedData.get(level).setDirty();
        return committed ? ok(src, "zone committed for " + type.id()) : fail(src, "zone refused");
    }

    private static int hire(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BuildingType type = type(StringArgumentType.getString(ctx, "type"));
        Settlement s = settlementAt(src);
        if (type == null || s == null) {
            return fail(src, "unknown type or no settlement");
        }
        int want = IntegerArgumentType.getInteger(ctx, "count");
        int hired = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < want; i++) {
            Building b = null;
            for (Building candidate : s.buildings) {
                if (candidate.type == type && candidate.valid
                    && candidate.workers.size() < type.workerCapacity()) {
                    b = candidate;
                    break;
                }
            }
            if (b == null) {
                break;
            }
            SettlerEntity worker = null;
            for (SettlerEntity member : SettlementManager.loadedMembers(level, s)) {
                if (member.getProfession() == Profession.NONE && !member.getUUID().equals(s.mayorId)
                    && !member.isTraveler()) {
                    worker = member;
                    break;
                }
            }
            if (worker == null) {
                worker = SettlementManager.spawnSettler(level, s, false);
            }
            if (worker == null) {
                return fail(src, "could not spawn a settler");
            }
            Employment.Hired result = Employment.hire(level, s, b, worker);
            if (!result.ok()) {
                return fail(src, "hire refused: " + (result.refusal() == null ? "?" : result.refusal().getString()));
            }
            hired++;
            names.append(worker.getSettlerName()).append(' ');
        }
        SettlementSavedData.get(level).setDirty();
        return ok(src, "hired " + hired + " " + type.id() + ": " + names.toString().trim());
    }

    private static int research(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Settlement s = settlementAt(src);
        Building study = s == null ? null : first(s, BuildingType.ARCHITECTS_STUDY);
        if (study == null) {
            return fail(src, "no study");
        }
        var refusal = com.hearthstead.settlement.research.Research.start(src.getLevel(), s, study,
            com.hearthstead.settlement.research.ResearchProject.AAKERSKIFTE);
        return ok(src, "research start: " + (refusal == null ? "ok" : refusal.toString()));
    }

    private static int merchant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Settlement s = settlementAt(src);
        if (s == null) {
            return fail(src, "no settlement");
        }
        WanderingTrader merchant = EntityType.WANDERING_TRADER.spawn(level,
            BlockPosArgument.getBlockPos(ctx, "pos"), MobSpawnType.COMMAND);
        if (merchant == null) {
            return fail(src, "spawn failed");
        }
        merchant.setNoAi(true);
        merchant.setPersistenceRequired();
        merchant.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", s.id);
        merchant.getPersistentData().putLong("HearthsteadMerchantExpires", level.getGameTime() + 2_000_000L);
        boolean published = com.hearthstead.event.GoldCoinTrades.clearOwnedMerchantForPublication(merchant, s.id);
        return ok(src, "merchant spawned, published=" + published);
    }

    /**
     * {@code soakqa raid}: a REAL raid, as play sees it (coordinator decision
     * 26 Sep): the band is sealed as a recurring raid run with its PendingRaid
     * mirror, the dawn retreat armed, the arrival horn and leader line played
     * and the settlement ALARM raised. {@code soakqa raid silent} keeps the old
     * band-only spawn (no raid flag, no warning, no alarm) for comparisons with
     * earlier soaks. The output keeps the "raid band spawned: N" prefix that
     * soak.py parses.
     */
    private static int raid(CommandContext<CommandSourceStack> ctx, boolean live) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Settlement s = settlementAt(src);
        if (s == null) {
            return fail(src, "no settlement");
        }
        var planned = com.hearthstead.settlement.raid.RaidDirector.planRaid(level, s, level.getDayTime() / 24000L);
        var plan = new com.hearthstead.settlement.raid.RaidPlan(planned.captainId(),
            com.hearthstead.settlement.raid.RaidObjective.BLOD, 0, planned.night());
        String liveRefusal = live ? liveRaidRefusal(s) : null;
        List<com.hearthstead.entity.RaiderEntity> band = com.hearthstead.settlement.raid.RaidDirector.spawnBand(
            level, s, plan);
        if (!live) {
            return ok(src, "raid band spawned: " + band.size() + " live=false");
        }
        if (liveRefusal == null) {
            java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>();
            for (var raider : band) ids.add(raider.getUUID());
            // Queue only once the band stands, so a failed placement never
            // leaves a queued serial behind for the director to retry later.
            if (band.size() < com.hearthstead.settlement.raid.RaidDirector.MIN_BAND
                    || ids.size() > com.hearthstead.settlement.state.RecurringRaidRun.MAX_PARTICIPANTS) {
                liveRefusal = "band_size_" + band.size();
            } else if (!s.recurringRaidRun.queue(plan)) {
                liveRefusal = "recurring_run_refused_queue";
            } else if (!s.recurringRaidRun.sealAndActivate(plan, ids)) {
                s.recurringRaidRun.block(); // contradictory evidence: fail closed as the director does
                liveRefusal = "band_not_sealed";
            } else {
                s.pendingRaid = plan;
                s.raidLootEscaped = false;
                s.raidItemsStolenTonight = 0;
                s.raidSettlersHurtTonight = 0;
                s.raidCaptainSlainId = null;
                s.raidLifecycle.armRecurringRetreat(level.getDayTime());
                SettlementSavedData.get(level).setDirty();
                com.hearthstead.settlement.raid.RaidPresentation.arrival(level, s);
                com.hearthstead.settlement.raid.RaidDirector.leaderNameOf(s, plan.captainId())
                    .ifPresent(name -> com.hearthstead.settlement.raid.RaidBroadcast.send(level, s,
                        Component.translatable("hearthstead.message.raid_captain_leads", name, s.name)));
            }
        }
        if (!band.isEmpty()) {
            // Play: the arrival is seen. Even an unsealed live band raises the ALARM.
            SettlementManager.raiseAlert(level, s, band.get(0).blockPosition());
        }
        return ok(src, "raid band spawned: " + band.size() + " live=" + (liveRefusal == null)
            + (liveRefusal == null ? "" : " refused=" + liveRefusal));
    }

    /** Why a live raid cannot be sealed here (null = it can); the band still spawns with the ALARM. */
    private static String liveRaidRefusal(Settlement s) {
        if (s.raidLifecycle.firstState() != com.hearthstead.settlement.state.FirstRaidState.COMPLETED) {
            return "first_raid_not_completed";
        }
        if (!s.recurringRaidRun.isEmpty() || s.pendingRaid != null) {
            return "raid_already_running";
        }
        if (s.raidLifecycle.recurringScheduleBlocked()) {
            return "recurring_schedule_blocked";
        }
        return null;
    }

    /**
     * {@code soakqa raidprobe}: on the nearest settlement's REAL terrain, for
     * every approach bearing 0-355 (step 5), spawns and immediately discards
     * (a) a recurring band through {@code RaidDirector#spawnBand} and (b) the
     * authored first raid's five-slot band through its exact placement code,
     * and checks every raider stands dry, outside the claim, on open ground.
     * It also replays the pre-26-Sep footing rule (one bearing per follower,
     * +-12 blocks around the Banner's height) against the same loaded blocks
     * so the before/after is measured on one world. Lines tagged RAIDPROBE.
     */
    private static int raidProbe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Settlement s = settlementAt(src);
        if (s == null) {
            return fail(src, "no settlement");
        }
        long night = level.getDayTime() / 24000L;
        var captain = com.hearthstead.settlement.raid.RaidDirector.pickCaptain(s, level.getRandom());
        int recurringOk = 0;
        int firstOk = 0;
        int legacyFirstOk = 0;
        int legacyRecurringOk = 0;
        int badPlacements = 0;
        int probes = 0;
        StringBuilder failed = new StringBuilder();
        for (int approach = 0; approach < 360; approach += 5) {
            probes++;
            var plan = new com.hearthstead.settlement.raid.RaidPlan(captain.id(),
                com.hearthstead.settlement.raid.RaidObjective.BLOD, approach, night);
            List<com.hearthstead.entity.RaiderEntity> band =
                com.hearthstead.settlement.raid.RaidDirector.spawnBand(level, s, plan);
            int bad = badPlacements(level, s, band);
            badPlacements += bad;
            if (band.size() >= com.hearthstead.settlement.raid.RaidDirector.MIN_BAND) {
                recurringOk++;
            }
            com.hearthstead.Hearthstead.LOGGER.info("RAIDPROBE recurring approach={} size={} bad={} at={}",
                approach, band.size(), bad, positions(band));
            band.forEach(net.minecraft.world.entity.Entity::discard);
            List<com.hearthstead.entity.RaiderEntity> first =
                com.hearthstead.settlement.raid.RaidDirector.spawnFirstBandForQa(level, s, plan);
            bad = badPlacements(level, s, first);
            badPlacements += bad;
            if (first.size() == com.hearthstead.settlement.raid.RaidDirector.FIRST_RAID_BAND_SIZE) {
                firstOk++;
            } else {
                failed.append(approach).append(' ');
            }
            com.hearthstead.Hearthstead.LOGGER.info("RAIDPROBE first approach={} size={} bad={} at={}",
                approach, first.size(), bad, positions(first));
            first.forEach(net.minecraft.world.entity.Entity::discard);
            if (legacyPlaceable(level, s, approach, 5, 5)) {
                legacyFirstOk++;
            }
            if (legacyPlaceable(level, s, approach, 3, 2)) {
                legacyRecurringOk++;
            }
        }
        String summary = String.format(Locale.ROOT,
            "RAIDPROBE %s centre=%s radius=%d: recurring %d/%d, first-raid %d/%d, "
                + "bad placements %d | legacy rule: first-raid %d/%d, 3-band %d/%d | first failed at [%s]",
            s.name, s.center.toShortString(), s.radius, recurringOk, probes, firstOk, probes,
            badPlacements, legacyFirstOk, probes, legacyRecurringOk, probes, failed.toString().trim());
        com.hearthstead.Hearthstead.LOGGER.info(summary);
        return ok(src, summary);
    }

    private static String positions(List<com.hearthstead.entity.RaiderEntity> band) {
        StringBuilder out = new StringBuilder();
        for (var raider : band) {
            BlockPos at = raider.blockPosition();
            out.append(at.toShortString()).append('/')
                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(raider.level().getBlockState(at.below()).getBlock()).getPath())
                .append("; ");
        }
        return out.toString();
    }

    /** Raiders that stand in or over fluid, inside the claim, or under cover. */
    private static int badPlacements(ServerLevel level, Settlement s,
                                     List<com.hearthstead.entity.RaiderEntity> band) {
        int bad = 0;
        for (var raider : band) {
            BlockPos at = raider.blockPosition();
            boolean wet = !level.getFluidState(at).isEmpty()
                || !level.getFluidState(at.below()).isEmpty();
            boolean inside = !com.hearthstead.settlement.raid.RaidDirector.outsideClaim(s, at);
            boolean covered = at.getY() < level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at.getX(), at.getZ());
            if (wet || inside || covered) {
                bad++;
                com.hearthstead.Hearthstead.LOGGER.warn("RAIDPROBE bad placement {} wet={} inside={} covered={}",
                    at.toShortString(), wet, inside, covered);
            }
        }
        return bad;
    }

    /** The pre-fix footing rule, replayed read-only on the same blocks. */
    private static boolean legacyPlaceable(ServerLevel level, Settlement s, int approach,
                                           int band, int required) {
        int min = com.hearthstead.settlement.raid.RaidDirector.spawnMinDistance(s.radius);
        int max = com.hearthstead.settlement.raid.RaidDirector.spawnMaxDistance(s.radius);
        int reach = max + com.hearthstead.settlement.raid.RaidDirector.CAPTAIN_EXTRA_REACH;
        net.minecraft.util.RandomSource random =
            net.minecraft.util.RandomSource.create(approach * 31L + band);
        int placed = 0;
        for (int i = 0; i < band; i++) {
            float bearing = approach + (i / (float) (band - 1) - 0.5F) * 2.0F
                * com.hearthstead.settlement.raid.RaidDirector.SPAWN_ARC;
            int distance = min + random.nextInt(max - min + 1);
            boolean found = legacyAt(level, s, bearing, distance);
            if (!found && i > 0) {
                for (int d = min; d <= max && !found; d += 2) {
                    found = legacyAt(level, s, bearing, d);
                }
            } else if (!found) {
                for (int step = 0; step <= 8 && !found; step++) {
                    for (int sign = 1; sign >= -1 && !found; sign -= 2) {
                        if (step == 0 && sign < 0) {
                            continue;
                        }
                        for (int d = min; d <= reach && !found; d += 4) {
                            found = legacyAt(level, s, bearing + sign * step * 24.0F, d);
                        }
                    }
                }
                if (!found) {
                    return false;
                }
            }
            if (found) {
                placed++;
            }
        }
        return placed >= required;
    }

    private static boolean legacyAt(ServerLevel level, Settlement s, float bearing, int distance) {
        BlockPos column = com.hearthstead.settlement.raid.RaidDirector.formUpAt(s.center, bearing, distance);
        return com.hearthstead.settlement.raid.RaidDirector.outsideClaim(s, column)
            && com.hearthstead.settlement.raid.RaidDirector.standableNear(level, column) != null;
    }

    /** {@code soakqa feed minecraft:bread 64}: inserts real food into the Hearth larder. */
    private static int feed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Settlement s = settlementAt(src);
        String[] parts = StringArgumentType.getString(ctx, "item").trim().split(" +");
        if (s == null || !(src.getLevel().getBlockEntity(s.center)
                instanceof com.hearthstead.block.HearthBlockEntity hearth)) {
            return fail(src, "no hearth");
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse(parts[0]));
        int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 64;
        var inv = hearth.getInventory();
        int left = count;
        for (int slot = 0; slot < inv.getSlots() && left > 0; slot++) {
            int batch = Math.min(left, item.getDefaultMaxStackSize());
            var rest = inv.insertItem(slot, new net.minecraft.world.item.ItemStack(item, batch), false);
            left -= batch - rest.getCount();
        }
        return ok(src, "fed " + (count - left) + " " + parts[0]);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Settlement s = settlementAt(src);
        if (s == null) {
            return fail(src, "no settlement");
        }
        StringBuilder b = new StringBuilder(s.name).append(" center=").append(s.center.toShortString())
            .append(" radius=").append(s.radius).append(" buildings=").append(s.buildings.size());
        for (Building building : s.buildings) {
            b.append("\n ").append(building.type.id()).append(building.valid ? "" : "(invalid)")
                .append(" workers=").append(building.workers.size())
                .append(" zone=").append(building.workZone().isPresent());
        }
        String text = b.toString();
        src.sendSuccess(() -> Component.literal(text.toLowerCase(Locale.ROOT)), false);
        return 1;
    }
}
