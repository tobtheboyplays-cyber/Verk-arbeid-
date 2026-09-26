package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Shared "walk away, then vanish out of sight" for every resolved departure
 * (owner rule: no poof). A paid raid band, a toll brute, a peddler with his
 * llama, a caravan: the caller hands the group over and this helper owns it
 * until it is gone.
 *
 * <ol>
 *   <li>Farewell beat (1.5 s): the leader faces the nearest player, says the
 *       farewell line and waves; raiders grunt.</li>
 *   <li>Walk out together at a calm pace, leader in front, weapons lowered,
 *       toward a point 48-64 blocks from {@code from} (preferably back the way
 *       they came), in legs over loaded ground.</li>
 *   <li>Despawn only unseen ({@link DepartureRules}); stuck for 20 s picks
 *       another way; after 120 s they go as soon as nobody sees them.</li>
 * </ol>
 * While leaving they are non-hostile, never target anyone, and only a player
 * can hurt them. A player's blow breaks the truce: a raider band turns on
 * the players (relation penalty and a notice). A leaver whose chunk unloads
 * is not brought back: it is removed when it would load again.
 */
public final class Departure {
    public static final String TAG = "HearthsteadDeparture";
    public static final double WALK_SPEED = 0.7D;
    private static final int FAREWELL_TICKS = 30;
    private static final int LEG = 14;

    /** Optional raid truce: who the band belongs to, for the broken-truce penalty. */
    public record Truce(@Nullable UUID settlementId, SpeakerProfile leader) {
    }

    private static final class Group {
        final UUID id = UUID.randomUUID();
        final ServerLevel level;
        final List<UUID> members = new ArrayList<>();
        final UUID leader;
        final BlockPos from;
        Vec3 direction;
        Vec3 exit;
        final Component farewell;
        final Truce truce;
        final long started;
        boolean broken;
        boolean said;

        Group(ServerLevel level, UUID leader, BlockPos from, Vec3 direction, Vec3 exit, Component farewell,
              Truce truce) {
            this.level = level;
            this.leader = leader;
            this.from = from;
            this.direction = direction;
            this.exit = exit;
            this.farewell = farewell;
            this.truce = truce;
            this.started = level.getGameTime();
        }
    }

    private static final class Leaver {
        final UUID group;
        final Vec3 start;
        final int index;
        Vec3 mark;
        long markTick;
        long ageOffset;

        Leaver(UUID group, Vec3 start, int index, long now) {
            this.group = group;
            this.start = start;
            this.index = index;
            this.mark = start;
            this.markTick = now;
        }
    }

    private static final Map<UUID, Group> GROUPS = new LinkedHashMap<>();
    private static final Map<UUID, Leaver> LEAVERS = new HashMap<>();
    private static final List<Consumer<Entity>> DESPAWN_LISTENERS = new CopyOnWriteArrayList<>();

    private Departure() {
    }

    // -------------------------------------------------------------- API ---

    public static void depart(ServerLevel level, List<? extends Entity> group, BlockPos from,
                              @Nullable BlockPos preferredExit, @Nullable Component farewell) {
        depart(level, group, from, preferredExit, farewell, null);
    }

    /**
     * Hands {@code group} over: the first member leads. {@code from} is what
     * they walk away from (the Banner); {@code preferredExit} a road point,
     * else they head back the way they came. {@code farewell} is spoken by
     * the leader (may be null). {@code truce} marks a raid band.
     */
    public static void depart(ServerLevel level, List<? extends Entity> group, BlockPos from,
                              @Nullable BlockPos preferredExit, @Nullable Component farewell, @Nullable Truce truce) {
        List<Entity> members = new ArrayList<>();
        for (Entity entity : group) {
            if (entity != null && entity.isAlive() && !entity.isRemoved() && entity.level() == level && !isDeparting(entity)) {
                members.add(entity);
            }
        }
        if (members.isEmpty()) return;
        Entity leader = members.get(0);
        Vec3 centroid = Vec3.ZERO;
        for (Entity e : members) centroid = centroid.add(e.position());
        centroid = centroid.scale(1.0D / members.size());
        Vec3 origin = Vec3.atBottomCenterOf(from);
        Vec3 away = new Vec3(centroid.x - origin.x, 0.0D, centroid.z - origin.z);
        if (preferredExit != null) {
            away = new Vec3(preferredExit.getX() + 0.5D - origin.x, 0.0D, preferredExit.getZ() + 0.5D - origin.z);
        }
        if (away.lengthSqr() < 1.0E-3D) {
            double a = level.getRandom().nextDouble() * Math.PI * 2.0D;
            away = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
        }
        Vec3 direction = away.normalize();
        double distance = DepartureRules.RETREAT_MIN
            + level.getRandom().nextDouble() * (DepartureRules.RETREAT_MAX - DepartureRules.RETREAT_MIN);
        Vec3 exit = preferredExit != null ? Vec3.atBottomCenterOf(preferredExit) : origin.add(direction.scale(distance));
        Group g = new Group(level, leader.getUUID(), from, direction, exit, farewell, truce);
        GROUPS.put(g.id, g);
        long now = level.getGameTime();
        for (int i = 0; i < members.size(); i++) {
            Entity member = members.get(i);
            g.members.add(member.getUUID());
            LEAVERS.put(member.getUUID(), new Leaver(g.id, member.position(), i, now));
            member.getPersistentData().putUUID(TAG, g.id);
            if (!(member instanceof Mob mob)) continue;
            pacify(mob);
            if (mob instanceof RaiderEntity raider) raider.detachForDeparture();
        }
    }

    public static boolean isDeparting(Entity entity) {
        return entity != null && entity.getPersistentData().hasUUID(TAG);
    }

    /** Called once per member when it is removed out of sight. */
    public static void onDespawned(Consumer<Entity> listener) {
        DESPAWN_LISTENERS.add(listener);
    }

    /** Test hook: pretend {@code entity} has been leaving for {@code ticks} longer. */
    public static void ageForTest(Entity entity, long ticks) {
        Leaver leaver = LEAVERS.get(entity.getUUID());
        if (leaver != null) leaver.ageOffset += ticks;
    }

    public static void clear() {
        GROUPS.clear();
        LEAVERS.clear();
    }

    // ------------------------------------------------------------- tick ---

    public static void tick(MinecraftServer server) {
        if (GROUPS.isEmpty()) return;
        for (Group group : List.copyOf(GROUPS.values())) {
            tickGroup(group);
            if (group.members.isEmpty()) GROUPS.remove(group.id);
        }
    }

    private static void tickGroup(Group g) {
        ServerLevel level = g.level;
        long now = level.getGameTime();
        long age = now - g.started;
        Mob leader = level.getEntity(g.leader) instanceof Mob m && m.isAlive() ? m : null;
        for (UUID id : List.copyOf(g.members)) {
            Leaver leaver = LEAVERS.get(id);
            Entity entity = level.getEntity(id);
            if (leaver == null || entity == null || !entity.isAlive() || entity.isRemoved()) {
                g.members.remove(id);
                LEAVERS.remove(id);
                continue;
            }
            if (!(entity instanceof Mob mob)) {
                // Carts and other non-walkers: gone as soon as nobody sees them after the farewell.
                if (age >= FAREWELL_TICKS && now % 10 == 0 && unseen(level, entity)) {
                    despawnEntity(entity);
                    g.members.remove(id);
                    LEAVERS.remove(id);
                }
                continue;
            }
            mob.setTarget(null);
            mob.setAggressive(false);
            if (age < FAREWELL_TICKS) {
                farewellTick(g, mob, leader, age);
                continue;
            }
            long leaverAge = age + leaver.ageOffset;
            if (now % 10 == (id.hashCode() & 7)) {
                if (mob.position().distanceTo(leaver.mark) >= 2.0D) {
                    leaver.mark = mob.position();
                    leaver.markTick = now;
                } else if (DepartureRules.stuck(mob.position().distanceTo(leaver.mark), now - leaver.markTick)) {
                    turn(g, 50.0D);
                    leaver.markTick = now;
                }
                if (mayDespawn(level, mob, leaver, leaverAge)) {
                    despawn(mob);
                    g.members.remove(id);
                    LEAVERS.remove(id);
                    continue;
                }
                walk(g, mob, leader, leaver);
            }
        }
    }

    private static void farewellTick(Group g, Mob mob, @Nullable Mob leader, long age) {
        mob.getNavigation().stop();
        Player nearest = g.level.getNearestPlayer(mob, 24.0D);
        if (nearest != null) mob.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
        if (mob != leader) return;
        if (!g.said) {
            g.said = true;
            if (g.farewell != null) {
                Component line = Component.translatable("conversation.hearthstead.departure.says",
                    mob.getDisplayName(), g.farewell).withStyle(ChatFormatting.GRAY);
                for (ServerPlayer player : g.level.players()) {
                    if (player.distanceToSqr(mob) <= 40.0D * 40.0D) player.sendSystemMessage(line);
                }
            }
            if (mob instanceof RaiderEntity) {
                g.level.playSound(null, mob.blockPosition(), SoundEvents.VINDICATOR_AMBIENT, SoundSource.HOSTILE,
                    1.0F, 0.75F);
            }
        }
        if (age == 3 || age == 15) mob.swing(InteractionHand.MAIN_HAND, true);
    }

    private static void walk(Group g, Mob mob, @Nullable Mob leader, Leaver leaver) {
        if (mob == leader || leader == null) {
            Vec3 pos = mob.position();
            Vec3 toExit = new Vec3(g.exit.x - pos.x, 0.0D, g.exit.z - pos.z);
            if (toExit.length() < 3.0D) {
                // At the exit but still in view: keep going the same way.
                g.exit = g.exit.add(g.direction.scale(LEG));
                toExit = new Vec3(g.exit.x - pos.x, 0.0D, g.exit.z - pos.z);
            }
            Vec3 step = toExit.length() > LEG ? toExit.normalize().scale(LEG) : toExit;
            moveOnGround(g.level, mob, pos.add(step), WALK_SPEED);
            return;
        }
        // Followers trail the leader: a short file behind him, slightly spread.
        Vec3 back = g.direction.scale(-(1.8D + leaver.index * 1.3D));
        Vec3 side = new Vec3(-g.direction.z, 0.0D, g.direction.x).scale((leaver.index % 2 == 0 ? 1 : -1) * 0.9D);
        Vec3 target = leader.position().add(back).add(side);
        if (mob.position().distanceTo(target) > 1.5D) moveOnGround(g.level, mob, target, WALK_SPEED + 0.15D);
    }

    private static void moveOnGround(ServerLevel level, Mob mob, Vec3 target, double speed) {
        BlockPos column = BlockPos.containing(target);
        if (!level.hasChunkAt(column)) {
            mob.getNavigation().moveTo(target.x, mob.getY(), target.z, speed);
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        mob.getNavigation().moveTo(target.x, y, target.z, speed);
    }

    private static void turn(Group g, double degrees) {
        double r = Math.toRadians(degrees);
        Vec3 d = g.direction;
        g.direction = new Vec3(d.x * Math.cos(r) - d.z * Math.sin(r), 0.0D, d.x * Math.sin(r) + d.z * Math.cos(r)).normalize();
        Vec3 origin = Vec3.atBottomCenterOf(g.from);
        g.exit = origin.add(g.direction.scale(DepartureRules.RETREAT_MAX));
    }

    private static boolean mayDespawn(ServerLevel level, Mob mob, Leaver leaver, long age) {
        double walked = mob.position().distanceTo(leaver.start);
        if (!DepartureRules.walkedOff(walked, age)) return false;
        return unseen(level, mob);
    }

    /** Owner rule: nobody sees it (see {@link DepartureRules}). */
    public static boolean unseen(ServerLevel level, Entity mob) {
        List<DepartureRules.Viewer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double d = player.distanceTo(mob);
            viewers.add(new DepartureRules.Viewer(d, d <= DepartureRules.FAR && player.hasLineOfSight(mob)));
        }
        return DepartureRules.unseen(viewers);
    }

    private static void despawn(Mob mob) {
        despawnEntity(mob);
    }

    private static void despawnEntity(Entity mob) {
        for (Consumer<Entity> listener : DESPAWN_LISTENERS) {
            try {
                listener.accept(mob);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Departure despawn listener failed", failure);
            }
        }
        mob.discard();
    }

    // --------------------------------------------------------- behaviour ---

    /** Calm walker: every attack and target goal removed; floats, looks at players. */
    private static void pacify(Mob mob) {
        for (var goal : List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(mob.targetSelector.getAvailableGoals())) mob.targetSelector.removeGoal(goal.getGoal());
        mob.goalSelector.addGoal(0, new FloatGoal(mob));
        mob.goalSelector.addGoal(8, new LookAtPlayerGoal(mob, Player.class, 8.0F));
        mob.goalSelector.addGoal(9, new RandomLookAroundGoal(mob));
        mob.setTarget(null);
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    /**
     * While leaving only a player may hurt a leaver (guards and turrets hold).
     * A player's blow breaks the truce. Returns true to cancel the damage.
     */
    public static boolean onIncomingDamage(LivingEntity victim, DamageSource source) {
        if (!isDeparting(victim) || victim.level().isClientSide) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        Entity attacker = source.getEntity();
        if (attacker == null) return false;
        if (!(attacker instanceof Player player)) return true;
        Leaver leaver = LEAVERS.get(victim.getUUID());
        Group group = leaver == null ? null : GROUPS.get(leaver.group);
        if (group != null && !group.broken) breakTruce(group, player);
        return false;
    }

    private static void breakTruce(Group g, Player attacker) {
        g.broken = true;
        GROUPS.remove(g.id);
        Mob leader = g.level.getEntity(g.leader) instanceof Mob m ? m : null;
        boolean raiders = false;
        for (UUID id : g.members) {
            LEAVERS.remove(id);
            if (!(g.level.getEntity(id) instanceof Mob mob) || !mob.isAlive()) continue;
            mob.getPersistentData().remove(TAG);
            if (mob instanceof RaiderEntity raider) {
                raiders = true;
                rearm(raider, attacker);
            }
        }
        if (!raiders) return;
        if (g.truce != null && attacker instanceof ServerPlayer player) {
            RelationSavedData.get(player.server).change(g.truce.settlementId(), g.truce.leader(), -25, -5,
                "conversation.hearthstead.departure.memory.broken", g.level.getGameTime());
        }
        Component name = leader != null ? leader.getDisplayName() : Component.literal("?");
        Component notice = Component.translatable("conversation.hearthstead.departure.broken",
            attacker.getName(), name).withStyle(ChatFormatting.RED);
        for (ServerPlayer player : g.level.players()) {
            if (leader == null || player.distanceToSqr(leader) <= 64.0D * 64.0D) player.sendSystemMessage(notice);
        }
    }

    /** A raider whose truce was broken fights the players again. */
    private static void rearm(RaiderEntity raider, Player attacker) {
        for (var goal : List.copyOf(raider.goalSelector.getAvailableGoals())) raider.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(raider.targetSelector.getAvailableGoals())) raider.targetSelector.removeGoal(goal.getGoal());
        raider.goalSelector.addGoal(0, new FloatGoal(raider));
        raider.goalSelector.addGoal(3, new com.hearthstead.entity.ai.RaiderMeleeGoal(raider, 1.0D));
        raider.goalSelector.addGoal(8, new LookAtPlayerGoal(raider, Player.class, 8.0F));
        raider.goalSelector.addGoal(9, new RandomLookAroundGoal(raider));
        raider.targetSelector.addGoal(1, new HurtByTargetGoal(raider));
        raider.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(raider, Player.class, true));
        raider.setTarget(attacker);
    }

    /**
     * A leaver loading again (its chunk unloaded, or the server restarted)
     * is not revived: returns true when the join should be cancelled.
     */
    public static boolean dropOnLoad(Entity entity) {
        return isDeparting(entity) && !LEAVERS.containsKey(entity.getUUID());
    }

    public static int activeCount() {
        return LEAVERS.size();
    }
}
