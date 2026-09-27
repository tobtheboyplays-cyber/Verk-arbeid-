package com.hearthstead.settlement.guard;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrderRules.Refusal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server authority for live field orders: R commands the melee troops
 * (Knights, Spearmen, Longswordsmen, Healers), G the ranged ones (Archers,
 * Rune Mages); the wire still carries single roles for tools and tests.
 *
 * <p>Focus and keep attacking (owner, 27 Sep): when the marked enemy of an
 * ATTACK order falls while a raid is on, the order does not revert; it turns
 * into "keep attacking" and every soldier who heard it engages the next
 * raider (the defence coordinator's pick, else the nearest) until the player
 * gives another order. When the raid is over the order lapses after the
 * usual grace and the soldiers go back to their posts.
 *
 * <p>Orders are battle state, not saved state: they live in memory per
 * server, lapse after the raid (or a peacetime drill) and are cleared by
 * "Return to posts". While a soldier holds an assignment the existing
 * {@link BannerTeams} hooks route through here, so the persisted post order
 * ({@code GuardOrderGoal}), alarm/escort goals and tower posts yield, and melee,
 * archery and target selection accept only the targets this order allows.
 * Without an assignment every soldier runs the smart default defence.
 *
 * <p>Every request is re-validated here (player state, rate, range, aimed
 * enemy, settlement), soldiers hear it only within {@link
 * FieldOrderRules#EARSHOT}, and the reply always names how many heard and how
 * many cannot reach their slot, so an order never fails silently.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FieldOrders {
    private static final int TICK_INTERVAL = 10;
    private static final int HEARTBEAT_TICKS = 60;
    private static final int ACK_DELAY_TICKS = 8;
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    /** One order as given: shared by every soldier who heard it. */
    public static final class Order {
        public final int id;
        public final Kind kind;
        public final Group group;
        public final UUID issuer;
        public final String issuerName;
        public final BlockPos center;
        public final int octant;
        public final int width;
        /** The marked enemy; null once it fell and the order turned into "keep attacking". */
        @Nullable public UUID enemy;
        public String enemyName;
        /** True after the marked enemy fell during a raid: engage the next raider. */
        boolean engaging;
        final FieldOrderRules.Lifetime lifetime;
        int heard;
        int total;
        int unreachable;
        boolean holdFire;

        Order(int id, Kind kind, Group group, UUID issuer, String issuerName, BlockPos center,
              int octant, int width, @Nullable UUID enemy, String enemyName, long now, boolean raid) {
            this.id = id;
            this.kind = kind;
            this.group = group;
            this.issuer = issuer;
            this.issuerName = issuerName;
            this.center = center.immutable();
            this.octant = octant;
            this.width = width;
            this.enemy = enemy;
            this.enemyName = enemyName == null ? "" : enemyName;
            this.lifetime = new FieldOrderRules.Lifetime(now, raid);
        }

        public int heard() { return heard; }
        public int total() { return total; }
        public int unreachable() { return unreachable; }
        public boolean engaging() { return engaging; }
    }

    /** One soldier's part in an order. */
    public static final class Assignment {
        public final Order order;
        BlockPos slot;
        boolean reachable;
        boolean holdFire;
        final int slotIndex;
        @Nullable final Assignment previous;
        final BannerTeamBook.Command view;
        /** "Keep attacking": this soldier's current pick and when it was made. */
        @Nullable UUID engageTarget;
        long engagePickedAt = Long.MIN_VALUE;

        Assignment(Order order, BlockPos slot, boolean reachable, int slotIndex,
                   boolean holdFire, @Nullable Assignment previous) {
            this.order = order;
            this.slot = slot.immutable();
            this.reachable = reachable;
            this.slotIndex = slotIndex;
            this.holdFire = holdFire;
            this.previous = previous;
            BannerTeamBook.Order mapped = switch (order.kind) {
                case ATTACK -> BannerTeamBook.Order.ATTACK;
                case FOLLOW -> BannerTeamBook.Order.FOLLOW;
                default -> BannerTeamBook.Order.HOLD;
            };
            this.view = new BannerTeamBook.Command(mapped,
                mapped == BannerTeamBook.Order.FOLLOW ? null : this.slot, order.enemy, order.issuer,
                new UUID(0x46494c44L, order.id));
        }

        public BlockPos slot() { return slot; }
        public boolean reachable() { return reachable; }
        public boolean holdFire() { return holdFire; }
    }

    static final class Book {
        final UUID settlementId;
        final ResourceKey<Level> dimension;
        final Map<UUID, Assignment> assignments = new LinkedHashMap<>();
        final EnumMap<Group, Order> latest = new EnumMap<>(Group.class);
        int revision;

        Book(UUID settlementId, ResourceKey<Level> dimension) {
            this.settlementId = settlementId;
            this.dimension = dimension;
        }
    }

    static final class State {
        final Map<UUID, Book> books = new HashMap<>();
        final Map<UUID, Long> lastOrder = new HashMap<>();
        final Map<UUID, Integer> sentHash = new HashMap<>();
        final Map<UUID, Long> sentAt = new HashMap<>();
        final List<PendingSound> sounds = new ArrayList<>();
        int nextOrderId = 1;
        long ticks;
    }

    private record PendingSound(ResourceKey<Level> dimension, Vec3 at, long playAt, Group group) {
    }

    /** The answering squad's own voice (sound pass): spear butts, quivers, a rune hum, or the general "Hoo!". */
    private static net.minecraft.sounds.SoundEvent ackSound(Group group) {
        return switch (group) {
            case SPEARMEN -> ModSounds.COMMAND_ACK_SPEAR.get();
            case ARCHERS -> ModSounds.COMMAND_ACK_ARCHER.get();
            case MAGES -> ModSounds.COMMAND_ACK_MAGE.get();
            default -> ModSounds.COMMAND_ACK.get();
        };
    }

    /** Outcome of one request, for feedback and tests. */
    public record Result(Refusal refusal, int heard, int total, int unreachable, Component line) {
        public boolean accepted() { return refusal == Refusal.NONE; }
    }

    // ------------------------------------------------------------------ hooks

    /** The live assignment of this soldier, or null (smart default defence). */
    /** Test seam: force the [features] guardCommands switch in GameTests. */
    @Nullable private static Boolean enabledOverride;

    /** Server kill-switch: [features] guardCommands in the server config. */
    public static boolean enabled() {
        return enabledOverride != null ? enabledOverride
            : com.hearthstead.HearthsteadServerConfig.guardCommandsEnabled();
    }

    public static void setEnabledForTests(@Nullable Boolean enabled) {
        enabledOverride = enabled;
    }

    @Nullable
    public static Assignment assignment(SettlerEntity settler) {
        if (settler == null || !(settler.level() instanceof ServerLevel level) || !enabled()) return null;
        State state = STATES.get(level.getServer());
        if (state == null || state.books.isEmpty()) return null;
        UUID settlementId = settler.getSettlementId();
        if (settlementId == null) return null;
        Book book = state.books.get(settlementId);
        if (book == null || !book.dimension.equals(level.dimension())) return null;
        Assignment assignment = book.assignments.get(settler.getUUID());
        if (assignment == null || !settler.isAlive()
            || !memberOf(assignment.order.group, settler.getProfession())) return null;
        return assignment;
    }

    public static boolean controls(SettlerEntity settler) {
        return assignment(settler) != null;
    }

    /** Banner-command view used by the shared yield hooks in combat and post goals. */
    @Nullable
    public static BannerTeamBook.Command bannerView(SettlerEntity settler) {
        Assignment assignment = assignment(settler);
        return assignment == null ? null : assignment.view;
    }

    /** Where this soldier's movement is anchored right now. */
    @Nullable
    public static BlockPos anchor(SettlerEntity settler) {
        Assignment assignment = assignment(settler);
        if (assignment == null) return null;
        if (assignment.order.kind == Kind.ATTACK) {
            LivingEntity enemy = enemy(settler, assignment);
            // Keep attacking with nobody left in sight: hold where they stand.
            if (enemy == null) return assignment.order.engaging ? settler.blockPosition() : assignment.slot;
            if (styleOf(settler) == FieldOrderRules.Style.RANGED) {
                // Focus: close to a stand-off ring, never walk into melee.
                double distance = settler.distanceTo(enemy);
                if (distance <= 18.0D) return settler.blockPosition();
                Vec3 away = settler.position().subtract(enemy.position()).multiply(1, 0, 1);
                Vec3 standoff = enemy.position().add(away.lengthSqr() < 1.0E-4
                    ? Vec3.ZERO : away.normalize().scale(14.0D));
                return BlockPos.containing(standoff);
            }
            return enemy.blockPosition();
        }
        return assignment.slot;
    }

    /** True while a knight stands in a shield line (the brace look, blocking). */
    public static boolean bracing(SettlerEntity settler) {
        Assignment assignment = assignment(settler);
        return assignment != null && styleOf(settler) == FieldOrderRules.Style.MELEE
            && assignment.order.kind == Kind.LINE
            && settler.blockPosition().distSqr(assignment.slot) <= 2.25D;
    }

    /** Target filter applied at acquisition, pursuit and melee contact. */
    public static boolean allowsTarget(SettlerEntity settler, @Nullable LivingEntity target) {
        Assignment assignment = assignment(settler);
        if (assignment == null) return true;
        Settlement settlement = settler.settlement();
        if (target == null || settlement == null || !BannerTeams.hostile(settlement, target)
            || !settler.canAttack(target)) {
            return false;
        }
        double selfSqr = settler.distanceToSqr(target);
        boolean selfDefence = selfSqr <= 9.0D;
        if (assignment.holdFire) return selfDefence;
        FieldOrderRules.Style style = styleOf(settler);
        if (style == FieldOrderRules.Style.SUPPORT) return selfDefence; // healers never engage
        boolean archer = style == FieldOrderRules.Style.RANGED;
        return switch (assignment.order.kind) {
            case ATTACK -> assignment.order.engaging
                ? selfDefence || engageAllows(settler, settlement, target)
                : target.getUUID().equals(assignment.order.enemy) || selfDefence;
            case FOLLOW -> {
                ServerPlayer leader = leader(settler, assignment);
                if (leader == null) yield selfDefence;
                double reach = archer ? 16.0D : 8.0D;
                yield selfDefence || target.distanceToSqr(leader) <= reach * reach;
            }
            case LINE, HIGH_GROUND -> {
                double fromSlot = settler.blockPosition().distSqr(assignment.slot);
                if (archer) {
                    double reach = assignment.order.kind == Kind.HIGH_GROUND ? 28.0D : 24.0D;
                    yield selfDefence || fromSlot <= 25.0D && selfSqr <= reach * reach;
                }
                yield selfDefence || fromSlot <= 36.0D
                    && target.blockPosition().distSqr(assignment.slot) <= 25.0D;
            }
            default -> true;
        };
    }

    /** Keep attacking: raiders of this settlement's raid, and hostiles inside the defended area. */
    public static final double ENGAGE_AREA_BEYOND_CLAIM = 16.0D;
    /** How far a soldier looks for the next raider when the coordinator has none for it. */
    public static final double ENGAGE_SEARCH = 40.0D;
    private static final int ENGAGE_REPICK_TICKS = 20;

    static boolean engageAllows(SettlerEntity settler, Settlement settlement, LivingEntity target) {
        if (target instanceof com.hearthstead.entity.RaiderEntity raider
            && settlement.id.equals(raider.settlementId())) {
            return true;
        }
        double area = settlement.radius + ENGAGE_AREA_BEYOND_CLAIM;
        return target.blockPosition().distSqr(settlement.center) <= area * area
            || settler.distanceToSqr(target) <= 16.0D * 16.0D;
    }

    /** True while this soldier's focus order has turned into "keep attacking". */
    public static boolean engaging(SettlerEntity settler) {
        Assignment assignment = assignment(settler);
        return assignment != null && assignment.order.kind == Kind.ATTACK && assignment.order.engaging;
    }

    /**
     * The enemy an ATTACK order points this soldier at: the marked one, or in
     * "keep attacking" the next raider (re-picked about once a second).
     */
    @Nullable
    public static LivingEntity enemy(SettlerEntity settler, Assignment assignment) {
        if (!(settler.level() instanceof ServerLevel level)) return null;
        if (assignment.order.engaging) return nextEnemy(level, settler, assignment);
        if (assignment.order.enemy == null) return null;
        Entity entity = level.getEntity(assignment.order.enemy);
        return entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
    }

    @Nullable
    private static LivingEntity nextEnemy(ServerLevel level, SettlerEntity settler, Assignment assignment) {
        Settlement settlement = settler.settlement();
        if (settlement == null) return null;
        long now = level.getGameTime();
        LivingEntity current = assignment.engageTarget == null ? null
            : level.getEntity(assignment.engageTarget) instanceof LivingEntity living ? living : null;
        boolean currentOk = current != null && current.isAlive() && !current.isRemoved()
            && BannerTeams.hostile(settlement, current) && engageAllows(settler, settlement, current);
        if (currentOk && now - assignment.engagePickedAt < ENGAGE_REPICK_TICKS) return current;
        LivingEntity pick = null;
        // 1. What the soldier is already fighting.
        if (settler.getTarget() instanceof net.minecraft.world.entity.monster.Monster fighting
            && fighting.isAlive() && BannerTeams.hostile(settlement, fighting)
            && engageAllows(settler, settlement, fighting)) {
            pick = fighting;
        }
        // 2. The defence coordinator's assignment (protect-civilians-first, load balanced).
        if (pick == null) {
            net.minecraft.world.entity.monster.Monster assigned =
                com.hearthstead.settlement.raid.RaidThreatBoard.assignedTarget(level, settlement, settler);
            if (assigned != null && assigned.isAlive() && BannerTeams.hostile(settlement, assigned)
                && engageAllows(settler, settlement, assigned)) {
                pick = assigned;
            }
        }
        // 3. The nearest raider (then any hostile) in the raid area.
        if (pick == null) {
            double best = Double.MAX_VALUE;
            boolean bestRaider = false;
            for (net.minecraft.world.entity.monster.Monster candidate : level.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                settler.getBoundingBox().inflate(ENGAGE_SEARCH),
                m -> m.isAlive() && BannerTeams.hostile(settlement, m) && settler.canAttack(m)
                    && engageAllows(settler, settlement, m))) {
                boolean raider = candidate instanceof com.hearthstead.entity.RaiderEntity;
                double d = settler.distanceToSqr(candidate);
                if (raider && !bestRaider || raider == bestRaider && d < best) {
                    pick = candidate;
                    best = d;
                    bestRaider = raider;
                }
            }
        }
        if (pick == null && currentOk) pick = current;
        assignment.engageTarget = pick == null ? null : pick.getUUID();
        assignment.engagePickedAt = now;
        return pick;
    }

    @Nullable
    static ServerPlayer leader(SettlerEntity settler, Assignment assignment) {
        if (!(settler.level() instanceof ServerLevel level)) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(assignment.order.issuer);
        return player != null && player.isAlive() && !player.isSpectator() && player.level() == level
            ? player : null;
    }

    // ------------------------------------------------------------------ issue

    public static Result issue(ServerPlayer player, FieldOrderRequestPayload request) {
        if (player == null || request == null) {
            return refused(null, Refusal.NOT_ALLOWED, 0, 0);
        }
        if (!enabled()) {
            return refused(player, Refusal.DISABLED, 0, 0);
        }
        if (!player.isAlive() || player.isSpectator()) {
            return refused(player, Refusal.NOT_ALLOWED, 0, 0);
        }
        ServerLevel level = player.serverLevel();
        State state = STATES.computeIfAbsent(level.getServer(), server -> new State());
        long now = level.getGameTime();
        if (FieldOrderRules.rateLimited(state.lastOrder.getOrDefault(player.getUUID(), Long.MIN_VALUE), now)) {
            return new Result(Refusal.TOO_FAST, 0, 0, 0, Component.empty());
        }
        double distanceToPos = request.hasPos()
            ? Math.sqrt(player.blockPosition().distSqr(request.pos())) : -1.0D;
        Refusal shape = FieldOrderRules.validateShape(request.group(), request.kind(),
            request.octant(), request.width(), distanceToPos, request.enemyEntityId() >= 0);
        if (shape != Refusal.NONE) return refused(player, shape, 0, 0);
        Group group = Group.fromWire(request.group()).orElseThrow();
        Kind kind = Kind.fromWire(request.kind()).orElseThrow();

        Settlement settlement = commandedSettlement(player);
        if (settlement == null) return refused(player, Refusal.NO_SETTLEMENT, 0, 0);
        if (kind.needsPos() && !level.hasChunkAt(request.pos())) {
            return refused(player, Refusal.OUT_OF_RANGE, 0, 0);
        }
        LivingEntity enemy = null;
        if (kind.needsEnemy()) {
            Entity entity = level.getEntity(request.enemyEntityId());
            if (!(entity instanceof LivingEntity living) || !BannerTeams.hostile(settlement, living)
                || player.distanceToSqr(living) > FieldOrderRules.SERVER_TARGET_RANGE
                    * FieldOrderRules.SERVER_TARGET_RANGE
                || !player.hasLineOfSight(living) && player.distanceToSqr(living) > 100.0D) {
                return refused(player, Refusal.INVALID_ENEMY, 0, 0);
            }
            enemy = living;
        }
        state.lastOrder.put(player.getUUID(), now);
        Book book = state.books.computeIfAbsent(settlement.id, id -> new Book(id, level.dimension()));
        if (!book.dimension.equals(level.dimension())) {
            return refused(player, Refusal.NO_SETTLEMENT, 0, 0);
        }
        boolean raid = raidActive(settlement, now);

        int heard = 0;
        int total = 0;
        int unreachable = 0;
        List<SettlerEntity> rallied = new ArrayList<>(); // tech tree: Commander's Horn
        List<Component> phrases = new ArrayList<>();
        Refusal outcome = Refusal.NOBODY_HEARD;
        Map<Group, Roster> rosters = new EnumMap<>(Group.class);
        Map<Group, Integer> counts = new EnumMap<>(Group.class);
        for (Group arm : Group.ROLES) {
            if (!group.includes(arm) || FieldOrderRules.kindFor(arm, kind).isEmpty()) continue;
            Roster roster = roster(level, settlement, arm, player);
            rosters.put(arm, roster);
            counts.put(arm, roster.heard.size());
        }
        Map<Group, Integer> depths = FormationMath.layerDepths(counts);
        for (Group arm : Group.ROLES) {
            if (!group.includes(arm) || !rosters.containsKey(arm)) continue;
            Kind armKind = FieldOrderRules.kindFor(arm, kind).orElse(null);
            if (armKind == null) continue; // e.g. healers ignore "attack", melee ignore "hold fire"
            BlockPos center = request.pos();
            if (kind == Kind.HIGH_GROUND && armKind == Kind.LINE) {
                // A wall or tower means "hold the line at its base" for melee and support.
                center = FieldTerrain.baseToward(level, request.pos(), player.blockPosition());
            }
            if (group.multi() && armKind == Kind.LINE && center != null && request.hasPos()) {
                // One order for everyone: melee front, ranged behind, healers at the back.
                center = FormationMath.behind(center, request.octant(), depths.getOrDefault(arm, 0));
            }
            Roster roster = rosters.get(arm);
            total += roster.total;
            if (armKind == Kind.RETURN) {
                int cleared = clearGroup(book, arm);
                heard += cleared;
                book.latest.remove(arm);
                phrases.add(phrase(arm, Kind.RETURN, ""));
                outcome = Refusal.NONE;
                continue;
            }
            if (armKind == Kind.RESUPPLY) {
                // The archer quiver lane owns the resupply trip; it never replaces the standing order.
                heard += roster.heard.size();
                if (!roster.heard.isEmpty()) outcome = Refusal.NONE;
                phrases.add(phrase(arm, Kind.RESUPPLY, ""));
                continue;
            }
            if (roster.heard.isEmpty()) continue;
            outcome = Refusal.NONE;
            heard += roster.heard.size();
            Order order = new Order(state.nextOrderId++, armKind, arm, player.getUUID(),
                player.getGameProfile().getName(),
                center == null || !request.hasPos() ? player.blockPosition() : center,
                request.octant(), group.multi()
                    ? FormationMath.defaultWidth(arm, roster.heard.size()) : request.width(),
                enemy == null ? null : enemy.getUUID(),
                enemy == null ? "" : enemy.getDisplayName().getString(), now, raid);
            order.lifetime.raidGrace(com.hearthstead.settlement.techtree.effects.WatchEffects
                .orderRaidGraceTicks(level, settlement, FieldOrderRules.AFTER_RAID_GRACE_TICKS));
            rallied.addAll(roster.heard);
            order.total = roster.total;
            order.heard = roster.heard.size();
            if (armKind.stance()) {
                applyStance(book, order, roster.heard, armKind == Kind.HOLD_FIRE);
            } else {
                order.unreachable = assign(level, book, order, roster.heard, player, enemy);
            }
            unreachable += order.unreachable;
            Order shown = book.latest.get(arm);
            if (armKind.stance() && shown != null && !shown.kind.stance()) {
                shown.holdFire = armKind == Kind.HOLD_FIRE; // keep showing the position order
            } else if (armKind == Kind.FIRE_AT_WILL) {
                book.latest.remove(arm);
            } else {
                book.latest.put(arm, order);
            }
            phrases.add(phrase(arm, armKind, order.enemyName.isEmpty() ? order.issuerName : order.enemyName));
        }
        if (outcome != Refusal.NONE) {
            return refused(player, Refusal.NOBODY_HEARD, 0, total);
        }
        book.revision++;
        String argument = enemy != null ? enemy.getDisplayName().getString() : player.getGameProfile().getName();
        Component what = group.multi() || phrases.isEmpty()
            ? phrase(group.multi() ? group : Group.ALL, kind, argument) : phrases.getFirst();
        Component line = feedback(group, what, heard, total, unreachable);
        player.displayClientMessage(line, true);
        broadcast(level, settlement, player, group, what);
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
            ModSounds.COMMAND_SHOUT.get(), SoundSource.PLAYERS, 1.0F,
            0.95F + level.random.nextFloat() * 0.1F);
        state.sounds.add(new PendingSound(level.dimension(), ackPosition(level, book, player),
            now + ACK_DELAY_TICKS, group));
        state.sentHash.remove(player.getUUID());
        com.hearthstead.settlement.techtree.effects.WatchEffects.hornRally(level, settlement, player, rallied);
        return new Result(Refusal.NONE, heard, total, unreachable, line);
    }

    private static Vec3 ackPosition(ServerLevel level, Book book, ServerPlayer player) {
        for (Map.Entry<UUID, Assignment> entry : book.assignments.entrySet()) {
            if (entry.getValue().order.issuer.equals(player.getUUID())
                && level.getEntity(entry.getKey()) instanceof SettlerEntity soldier) {
                return soldier.position();
            }
        }
        return player.position();
    }

    private record Roster(List<SettlerEntity> heard, int total) {
    }

    /** Loaded, ready soldiers of one arm; {@code heard} are those within earshot. */
    private static Roster roster(ServerLevel level, Settlement settlement, Group arm,
                                 @Nullable ServerPlayer commander) {
        List<SettlerEntity> heard = new ArrayList<>();
        int total = 0;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            SettlerEntity soldier = level.getEntity(record.entityId) instanceof SettlerEntity loaded
                && loaded.isAlive() && settlement.id.equals(loaded.getSettlementId()) ? loaded : null;
            // Live profession when loaded; the employer's trade otherwise.
            Profession profession = soldier != null ? soldier.getProfession()
                : com.hearthstead.settlement.Employment.professionOf(settlement, record.entityId);
            if (!memberOf(arm, profession)) continue;
            total++;
            if (soldier == null
                || !EquipmentRequests.readyForProfession(level, soldier, soldier.getProfession())) continue;
            if (commander == null
                || FieldOrderRules.withinEarshot(soldier.distanceToSqr(commander),
                    com.hearthstead.settlement.techtree.effects.WatchEffects.orderEarshot(
                        level, settlement, FieldOrderRules.EARSHOT))) {
                heard.add(soldier);
            }
        }
        heard.sort((a, b) -> a.getUUID().compareTo(b.getUUID()));
        return new Roster(heard, total);
    }

    static boolean memberOf(Group arm, Profession profession) {
        if (profession == null) return false;
        if (arm.multi()) return Group.forProfessionKey(profession.key()).map(arm::includes).orElse(false);
        return arm.professionKey().equals(profession.key());
    }

    /** The role of this soldier, if it is a commandable one. */
    public static java.util.Optional<Group> roleOf(SettlerEntity settler) {
        return Group.forProfessionKey(settler.getProfession().key());
    }

    static FieldOrderRules.Style styleOf(SettlerEntity settler) {
        return roleOf(settler).map(Group::style).orElse(FieldOrderRules.Style.MELEE);
    }

    /** Places slots, pairs soldiers, records reachability. Returns unreachable count. */
    private static int assign(ServerLevel level, Book book, Order order, List<SettlerEntity> soldiers,
                              ServerPlayer commander, @Nullable LivingEntity enemy) {
        boolean archers = order.group.ranged(); // loose spacing and an arc behind the commander
        List<FieldTerrain.PlacedSlot> placed = switch (order.kind) {
            case LINE -> FieldTerrain.placeLine(level, order.center, order.octant, soldiers.size(),
                order.width, order.group);
            case HIGH_GROUND -> FieldTerrain.placeHigh(level, order.center, soldiers.size());
            case FOLLOW -> FieldTerrain.placeEscort(level, commander.blockPosition(),
                FormationMath.octant(commander.getYRot()), soldiers.size(), archers);
            default -> List.of();
        };
        int unreachable = 0;
        // A field order is the newest order: it ends any "come to me" summon.
        for (SettlerEntity soldier : soldiers) com.hearthstead.settlement.summon.PlayerSummons.end(soldier);
        if (order.kind == Kind.ATTACK) {
            for (SettlerEntity soldier : soldiers) {
                Assignment previous = book.assignments.get(soldier.getUUID());
                while (previous != null && previous.order.kind == Kind.ATTACK) previous = previous.previous;
                // A focus/charge order always fires; "hold fire" returns with the previous order.
                book.assignments.put(soldier.getUUID(), new Assignment(order, enemy.blockPosition(), true, 0,
                    false, previous));
                soldier.getNavigation().stop();
                // Charge/focus: take the aimed enemy now when it is in reach.
                if (enemy != null && soldier.distanceToSqr(enemy) <= 32.0D * 32.0D) {
                    soldier.setTarget(enemy);
                } else {
                    soldier.setTarget(null);
                }
            }
            return 0;
        }
        List<FormationMath.Soldier> keyed = new ArrayList<>(soldiers.size());
        for (SettlerEntity soldier : soldiers) {
            keyed.add(new FormationMath.Soldier(soldier.getUUID(), soldier.blockPosition()));
        }
        List<BlockPos> slotPositions = new ArrayList<>(placed.size());
        List<FieldTerrain.PlacedSlot> usable = new ArrayList<>();
        for (FieldTerrain.PlacedSlot slot : placed) {
            if (order.kind == Kind.HIGH_GROUND && !slot.valid()) continue; // tower full: no slot
            usable.add(slot);
            slotPositions.add(slot.pos());
        }
        BlockPos pairCenter = order.kind == Kind.FOLLOW ? commander.blockPosition() : order.center;
        int[] pairing = FormationMath.assign(keyed, slotPositions, pairCenter, order.octant);
        for (int i = 0; i < soldiers.size(); i++) {
            SettlerEntity soldier = soldiers.get(i);
            Assignment previous = book.assignments.get(soldier.getUUID());
            boolean holdFire = previous != null && previous.holdFire && archers;
            order.holdFire |= holdFire; // a new position keeps "hold fire" until Fire at will
            if (pairing[i] < 0) {
                unreachable++; // heard, but no room (tower or wall full)
                continue;
            }
            FieldTerrain.PlacedSlot slot = usable.get(pairing[i]);
            boolean reachable = slot.valid() && pathExists(soldier, slot.pos());
            if (!reachable) unreachable++;
            book.assignments.put(soldier.getUUID(),
                new Assignment(order, slot.pos(), reachable, pairing[i], holdFire, null));
            soldier.setTarget(null);
            soldier.getNavigation().stop();
            if (soldier.isUsingItem()) soldier.stopUsingItem();
        }
        return unreachable;
    }

    private static boolean pathExists(SettlerEntity soldier, BlockPos slot) {
        if (soldier.blockPosition().distSqr(slot) <= 2.25D) return true;
        // A body still settling (just spawned, falling, jumping) cannot be
        // pathed yet; FieldOrderGoal reports the real result a moment later.
        if (!soldier.onGround()) return true;
        Path path = soldier.getNavigation().createPath(slot, 0);
        if (path == null || path.getNodeCount() == 0) return false;
        if (path.canReach()) return true;
        // A long route may exceed one bounded search; a prefix that makes
        // real progress still counts as reachable (the goal re-plans).
        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        return end.distSqr(slot) + 4.0D < soldier.blockPosition().distSqr(slot);
    }

    private static void applyStance(Book book, Order order, List<SettlerEntity> archers, boolean holdFire) {
        for (SettlerEntity archer : archers) {
            Assignment current = book.assignments.get(archer.getUUID());
            if (current != null && holdFire && current.order.kind == Kind.ATTACK && current.order.engaging) {
                // "Hold fire" is a new order: it ends "keep attacking"; hold where they stand.
                book.assignments.put(archer.getUUID(),
                    new Assignment(order, archer.blockPosition(), true, 0, true, null));
            } else if (current != null) {
                current.holdFire = holdFire;
                if (!holdFire && current.order.kind == Kind.HOLD_FIRE) {
                    book.assignments.remove(archer.getUUID());
                }
            } else if (holdFire) {
                // No position order: hold fire where they stand.
                book.assignments.put(archer.getUUID(),
                    new Assignment(order, archer.blockPosition(), true, 0, true, null));
            }
            if (holdFire) archer.setTarget(null);
        }
        order.holdFire = holdFire;
    }

    private static int clearGroup(Book book, Group arm) {
        int cleared = 0;
        for (Iterator<Assignment> it = book.assignments.values().iterator(); it.hasNext(); ) {
            if (it.next().order.group == arm) {
                it.remove();
                cleared++;
            }
        }
        return cleared;
    }

    /** The settlement a player commands: the nearest one whose reach covers them. */
    @Nullable
    public static Settlement commandedSettlement(ServerPlayer player) {
        SettlementSavedData data = SettlementSavedData.existing(player.serverLevel());
        if (data == null) return null;
        Settlement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Settlement settlement : data.settlements.values()) {
            if (settlement == null || settlement.center == null) continue;
            double reach = settlement.radius + FieldOrderRules.COMMAND_REACH_BEYOND_CLAIM;
            double distance = player.blockPosition().distSqr(settlement.center);
            if (distance <= reach * reach && distance < bestDistance) {
                best = settlement;
                bestDistance = distance;
            }
        }
        return best;
    }

    static boolean raidActive(Settlement settlement, long now) {
        return BlessingEffects.raidActive(settlement) || settlement.alertActive(now);
    }

    // --------------------------------------------------------------- feedback

    private static Component groupName(Group group) {
        return Component.translatable("hearthstead.command.group." + group.id());
    }

    /** "hold the line", "focus Brute", ... keyed by style so every role reads naturally. */
    static Component phrase(Group arm, Kind kind, String argument) {
        return Component.translatable("hearthstead.command.kind." + styleKey(arm) + "." + kind.id(), argument);
    }

    public static String styleKey(Group group) {
        return group == Group.ALL ? "all" : group.style().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Component feedback(Group group, Component what, int heard, int total,
                                      int unreachable) {
        return unreachable > 0
            ? Component.translatable("hearthstead.command.feedback.unreachable", groupName(group), what,
                heard, total, unreachable)
            : Component.translatable("hearthstead.command.feedback", groupName(group), what, heard, total);
    }

    /** Co-op: everyone else near this settlement reads "Tobias: Knights charge Brute". */
    private static void broadcast(ServerLevel level, Settlement settlement, ServerPlayer issuer, Group group,
                                  Component what) {
        Component line = Component.translatable("hearthstead.command.broadcast",
            issuer.getGameProfile().getName(), groupName(group), what).withStyle(ChatFormatting.GOLD);
        double reach = settlement.radius + FieldOrderRules.COMMAND_REACH_BEYOND_CLAIM + 32;
        for (ServerPlayer other : level.players()) {
            if (other == issuer || other.blockPosition().distSqr(settlement.center) > reach * reach) continue;
            other.displayClientMessage(line, false);
        }
    }

    private static Result refused(@Nullable ServerPlayer player, Refusal refusal, int heard, int total) {
        Component line = refusal == Refusal.NOBODY_HEARD
            ? Component.translatable(refusal.translationKey(), total)
            : Component.translatable(refusal.translationKey());
        if (player != null) player.displayClientMessage(line.copy().withStyle(ChatFormatting.RED), true);
        return new Result(refusal, heard, total, 0, line);
    }

    // ------------------------------------------------------------ server tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        State state = STATES.get(server);
        if (state == null) {
            // Nothing issued yet: still sync the roster for preview counts.
            if (server.getTickCount() % (TICK_INTERVAL * 2) != 0) return;
            state = STATES.computeIfAbsent(server, s -> new State());
        }
        state.ticks++;
        playPendingSounds(server, state);
        if (server.getTickCount() % TICK_INTERVAL != 0) return;
        if (!enabled()) {
            // Switched off: drop every live order; soldiers run the default defence.
            // Clients still get a (now empty) snapshot so HUD chips and dots vanish.
            state.books.clear();
        }
        for (Iterator<Book> it = state.books.values().iterator(); it.hasNext(); ) {
            Book book = it.next();
            ServerLevel level = server.getLevel(book.dimension);
            SettlementSavedData data = level == null ? null : SettlementSavedData.existing(level);
            Settlement settlement = data == null ? null : data.settlements.get(book.settlementId);
            if (settlement == null) {
                it.remove();
                continue;
            }
            if (update(level, settlement, book)) book.revision++;
        }
        sync(server, state);
    }

    /** Expiry, target loss and escort slot refresh. Returns true if anything changed. */
    static boolean update(ServerLevel level, Settlement settlement, Book book) {
        long now = level.getGameTime();
        boolean raid = raidActive(settlement, now);
        boolean changed = false;
        Set<Order> orders = new HashSet<>();
        for (Assignment assignment : book.assignments.values()) {
            for (Assignment a = assignment; a != null; a = a.previous) orders.add(a.order);
        }
        orders.addAll(book.latest.values());
        Set<Order> expired = new HashSet<>();
        for (Order order : orders) {
            if (order.lifetime.update(now, raid)) expired.add(order);
        }
        for (Order order : orders) {
            if (order.kind != Kind.ATTACK || expired.contains(order) || order.engaging) continue;
            Entity enemy = order.enemy == null ? null : level.getEntity(order.enemy);
            if (!(enemy instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
                if (raid) {
                    // Owner, 27 Sep: keep attacking the next raider until a new order.
                    notifyIssuer(level, order, Component.translatable("hearthstead.command.target_down_engage",
                        groupName(order.group), order.enemyName));
                    order.engaging = true;
                    order.enemy = null;
                    order.enemyName = "";
                    changed = true;
                    continue;
                }
                expired.add(order);
                notifyIssuer(level, order, Component.translatable("hearthstead.command.target_down",
                    groupName(order.group), order.enemyName));
            }
        }
        if (!expired.isEmpty()) {
            for (Iterator<Map.Entry<UUID, Assignment>> it = book.assignments.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Assignment> entry = it.next();
                Assignment current = entry.getValue();
                if (!expired.contains(current.order)) continue;
                Assignment fallback = current.previous;
                while (fallback != null && expired.contains(fallback.order)) fallback = fallback.previous;
                if (fallback == null) it.remove();
                else entry.setValue(fallback);
                changed = true;
            }
            for (Iterator<Map.Entry<Group, Order>> it = book.latest.entrySet().iterator(); it.hasNext(); ) {
                Order order = it.next().getValue();
                if (!expired.contains(order)) continue;
                it.remove();
                if (order.kind != Kind.ATTACK) {
                    notifyIssuer(level, order, Component.translatable("hearthstead.command.expired",
                        groupName(order.group)));
                }
                changed = true;
            }
        }
        changed |= refreshEscorts(level, book);
        return changed;
    }

    private static boolean refreshEscorts(ServerLevel level, Book book) {
        Map<Order, List<Map.Entry<UUID, Assignment>>> escorts = new HashMap<>();
        for (Map.Entry<UUID, Assignment> entry : book.assignments.entrySet()) {
            if (entry.getValue().order.kind == Kind.FOLLOW) {
                escorts.computeIfAbsent(entry.getValue().order, o -> new ArrayList<>()).add(entry);
            }
        }
        boolean changed = false;
        for (Map.Entry<Order, List<Map.Entry<UUID, Assignment>>> escort : escorts.entrySet()) {
            Order order = escort.getKey();
            ServerPlayer leader = level.getServer().getPlayerList().getPlayer(order.issuer);
            if (leader == null || !leader.isAlive() || leader.isSpectator() || leader.level() != level) {
                // Leader gone: stand down to smart defence, never wander off.
                for (Map.Entry<UUID, Assignment> entry : escort.getValue()) {
                    book.assignments.remove(entry.getKey());
                }
                book.latest.values().remove(order);
                changed = true;
                continue;
            }
            List<FieldTerrain.PlacedSlot> ring = FieldTerrain.placeEscort(level, leader.blockPosition(),
                FormationMath.octant(leader.getYRot()), order.heard, order.group.ranged());
            for (Map.Entry<UUID, Assignment> entry : escort.getValue()) {
                Assignment a = entry.getValue();
                if (a.slotIndex < 0 || a.slotIndex >= ring.size()) continue;
                BlockPos next = ring.get(a.slotIndex).pos();
                if (!next.equals(a.slot)) {
                    a.slot = next.immutable();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void notifyIssuer(ServerLevel level, Order order, Component message) {
        ServerPlayer issuer = level.getServer().getPlayerList().getPlayer(order.issuer);
        if (issuer != null) issuer.displayClientMessage(message, true);
    }

    private static void playPendingSounds(MinecraftServer server, State state) {
        if (state.sounds.isEmpty()) return;
        for (Iterator<PendingSound> it = state.sounds.iterator(); it.hasNext(); ) {
            PendingSound sound = it.next();
            ServerLevel level = server.getLevel(sound.dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            if (level.getGameTime() < sound.playAt()) continue;
            it.remove();
            level.playSound(null, sound.at().x, sound.at().y + 1.0D, sound.at().z,
                ackSound(sound.group()), SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    // ------------------------------------------------------------------- sync

    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null
            && net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
                player.connection, FieldOrderStatePayload.TYPE.id());
    }

    private static void sync(MinecraftServer server, State state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!canReceive(player)) continue; // login, mock or vanilla connection
            Settlement settlement = commandedSettlement(player);
            if (settlement == null) {
                if (state.sentHash.remove(player.getUUID()) != null) {
                    com.hearthstead.network.PayloadSend.toPlayer(player, new FieldOrderStatePayload(0, List.of(),
                        List.of(), List.of()));
                }
                continue;
            }
            FieldOrderStatePayload payload = snapshot(player.serverLevel(), settlement,
                state.books.get(settlement.id));
            int hash = Objects.hash(payload.revision(), payload.roster(), payload.groups(),
                payload.slots());
            Integer last = state.sentHash.get(player.getUUID());
            long sentAt = state.sentAt.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
            if (last != null && last == hash && state.ticks - sentAt < HEARTBEAT_TICKS) continue;
            state.sentHash.put(player.getUUID(), hash);
            state.sentAt.put(player.getUUID(), state.ticks);
            com.hearthstead.network.PayloadSend.toPlayer(player, payload);
        }
        state.sentHash.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        state.sentAt.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    static FieldOrderStatePayload snapshot(ServerLevel level, Settlement settlement, @Nullable Book book) {
        List<FieldOrderStatePayload.RosterEntry> rosterEntries = new ArrayList<>();
        for (Group role : Group.ROLES) {
            for (SettlerEntity soldier : roster(level, settlement, role, null).heard) {
                rosterEntries.add(new FieldOrderStatePayload.RosterEntry(soldier.getId(), role.wireId()));
            }
        }
        List<FieldOrderStatePayload.GroupLine> groups = new ArrayList<>();
        List<FieldOrderStatePayload.SlotEntry> slots = new ArrayList<>();
        int revision = 0;
        if (book != null && book.dimension.equals(level.dimension())) {
            revision = book.revision;
            for (Map.Entry<Group, Order> entry : book.latest.entrySet()) {
                Order o = entry.getValue();
                Entity enemy = o.enemy == null ? null : level.getEntity(o.enemy);
                groups.add(new FieldOrderStatePayload.GroupLine(o.group.wireId(), o.kind.wireId(), o.issuerName,
                    o.enemyName, o.heard, o.total, o.unreachable, o.holdFire, o.center, o.octant,
                    enemy == null ? -1 : enemy.getId(), o.id));
            }
            for (Map.Entry<UUID, Assignment> entry : book.assignments.entrySet()) {
                if (!(level.getEntity(entry.getKey()) instanceof SettlerEntity soldier) || !soldier.isAlive()) continue;
                Assignment a = entry.getValue();
                BlockPos shown = a.order.kind == Kind.ATTACK ? Objects.requireNonNullElse(anchor(soldier), a.slot)
                    : a.slot;
                slots.add(new FieldOrderStatePayload.SlotEntry(soldier.getId(), a.order.group.wireId(),
                    a.order.kind.wireId(), shown, a.reachable, a.holdFire));
            }
        }
        return new FieldOrderStatePayload(revision, rosterEntries, groups, slots);
    }

    // ---------------------------------------------------------- lifecycle/QA

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    /**
     * Live reachability from the soldier's own route attempts: a slot proven
     * unreachable turns its dot red and the HUD count warm; a later route
     * that works clears it again.
     */
    public static void markReachable(SettlerEntity settler, boolean reachable) {
        Assignment assignment = assignment(settler);
        if (assignment == null || assignment.reachable == reachable
            || !(settler.level() instanceof ServerLevel level)) return;
        assignment.reachable = reachable;
        assignment.order.unreachable = Math.max(0, assignment.order.unreachable + (reachable ? -1 : 1));
        State state = STATES.get(level.getServer());
        UUID settlementId = settler.getSettlementId();
        Book book = state == null || settlementId == null ? null : state.books.get(settlementId);
        if (book != null) book.revision++;
    }

    /** Drops this soldier's field order (a newer order, e.g. a summon, replaced it). */
    public static void release(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level)) return;
        State state = STATES.get(level.getServer());
        UUID settlementId = settler.getSettlementId();
        Book book = state == null || settlementId == null ? null : state.books.get(settlementId);
        if (book != null && book.assignments.remove(settler.getUUID()) != null) book.revision++;
    }

    /** Test seam: forget all field orders on this server. */
    public static void resetForTests(MinecraftServer server) {
        STATES.remove(server);
        enabledOverride = null;
    }

    /** Test seam: run the periodic update for one settlement now. */
    public static void updateNow(ServerLevel level, Settlement settlement) {
        State state = STATES.get(level.getServer());
        Book book = state == null ? null : state.books.get(settlement.id);
        if (book != null && update(level, settlement, book)) book.revision++;
    }

    /** Read-only view of the latest order per arm for tests and HUD code. */
    @Nullable
    public static Order latest(ServerLevel level, UUID settlementId, Group arm) {
        State state = STATES.get(level.getServer());
        Book book = state == null ? null : state.books.get(settlementId);
        return book == null ? null : book.latest.get(arm);
    }

    private FieldOrders() {
    }
}
