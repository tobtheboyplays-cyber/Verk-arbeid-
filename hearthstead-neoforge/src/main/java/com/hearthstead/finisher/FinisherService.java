package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.CinematicOpportunity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server authority for executions ("Finish him").
 *
 * <p>Owns the finish windows (who glows), the request validation (window,
 * reach, line of sight, nobody busy), the co-op double latch, the running
 * executions (participants locked and invulnerable, the victim frozen and
 * untouchable, exactly one kill at the impact tick), the impact effects
 * everyone sees (sound layers, dust, sparks, a weapon flash, the body fall,
 * guards cheering), stats and the captain trophy.</p>
 *
 * <p>Presentation is identical for every viewer: the {@link FinisherPayloads.Start}
 * packet carries the variant and the facings the server locked, and every
 * client samples the same {@link FinisherTimeline} (real time, one shared
 * 3-tick hit-stop). There is no slow motion and no per-player camera move.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FinisherService {
    public static final ResourceKey<DamageType> EXECUTION = ResourceKey.create(
        Registries.DAMAGE_TYPE, Hearthstead.id("execution"));
    /** Server slack over the client's reach test (latency, bounding boxes). */
    public static final double REACH_SLACK = 0.75D;
    /** A player's hit this big counts as a "heavy hit" stagger for the window. */
    public static final float PLAYER_HEAVY_HIT_FRACTION = 0.30F;
    public static final float PLAYER_HEAVY_HIT_MIN = 6.0F;
    /** Knockback strength (vanilla units) that knocks an enemy off balance. */
    public static final double BIG_KNOCKBACK = 0.85D;
    /** A slowness this strong is a stun (warhammer, captain pommel stun, role stagger). */
    public static final int STUN_SLOWNESS_AMPLIFIER = 3;
    /** The body hits the ground this many clip ticks after the impact. */
    public static final int BODY_FALL_AFTER_IMPACT = 10;
    public static final int CHEER_AFTER_IMPACT = 8;
    public static final double CHEER_RADIUS = 16.0D;
    public static final double SCAN_RADIUS = 32.0D;
    /** Largest body the choreography is authored for (a Brute fits). */
    public static final float MAX_VICTIM_HEIGHT = 3.2F;

    public enum Result {
        STARTED,
        PENDING_DOUBLE,
        JOINED_DOUBLE,
        DISABLED,
        INVALID_TARGET,
        BUSY,
        NO_WINDOW,
        OUT_OF_REACH,
        NO_LINE_OF_SIGHT;

        public boolean accepted() {
            return this == STARTED || this == PENDING_DOUBLE || this == JOINED_DOUBLE;
        }
    }

    /** One running (or READY-held) execution. */
    public static final class Execution {
        final LivingEntity victim;
        final Player lead;
        @Nullable Player partner;
        @Nullable FinisherVariant variant;
        final EnemyClass enemy;
        long startTick;
        boolean killed;
        boolean guard;
        boolean bodyFell;
        boolean cheered;
        boolean restoreAi;
        Vec3 victimAnchor;
        float victimYaw;
        Vec3 leadAnchor;
        float leadYaw;
        @Nullable Vec3 partnerAnchor;
        float partnerYaw;

        Execution(LivingEntity victim, Player lead, EnemyClass enemy) {
            this.victim = victim;
            this.lead = lead;
            this.enemy = enemy;
        }

        public boolean isReady() {
            return variant == null;
        }

        public boolean killed() {
            return killed;
        }

        @Nullable
        public FinisherVariant variant() {
            return variant;
        }

        public long startTick() {
            return startTick;
        }

        public long killTick() {
            return variant == null ? Long.MAX_VALUE : startTick + variant.impactTick();
        }

        public long endTick() {
            return variant == null ? Long.MAX_VALUE : startTick + variant.lockTicks();
        }

        public LivingEntity victim() {
            return victim;
        }

        public Player lead() {
            return lead;
        }

        @Nullable
        public Player partner() {
            return partner;
        }
    }

    static final class LevelState {
        final FinishWindowTracker windows = new FinishWindowTracker();
        final DoubleFinisherLatch latch = new DoubleFinisherLatch();
        final Map<UUID, Execution> byVictim = new HashMap<>();
        final Map<UUID, Execution> byActor = new HashMap<>();
        final Map<UUID, FinisherVariant> lastVariant = new HashMap<>();
        final Set<UUID> watched = new HashSet<>();
        /** Enemies that were staggered/stunned last tick (off-balance starts on the rising edge). */
        final Set<UUID> wasStaggered = new HashSet<>();
        /** Guard rarity is rolled once per open window: victim -> allowed. */
        final Map<UUID, Boolean> guardRoll = new HashMap<>();

    }

    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();
    static final String FROZEN_TAG = "HearthsteadFinisherFrozen";
    /** QA/filming: the next solo execution plays this move (op command only). */
    static FinisherVariant qaNextVariant;
    /** QA/filming: a scripted partner that joins a double on this victim a few ticks in. */
    static final Map<UUID, ServerPlayer> QA_AUTO_JOIN = new HashMap<>();
    /** The one victim whose execution damage is being applied right now. */
    private static LivingEntity killing;

    private FinisherService() {
    }

    static LevelState state(ServerLevel level) {
        return STATES.computeIfAbsent(level, l -> new LevelState());
    }

    // ------------------------------------------------------------------ queries

    public static boolean isFinishable(LivingEntity entity) {
        return entity.level() instanceof ServerLevel level
            && state(level).windows.isOpen(entity.getUUID(), level.getGameTime());
    }

    public static FinishWindowTracker.State windowState(LivingEntity entity) {
        return entity.level() instanceof ServerLevel level
            ? state(level).windows.state(entity.getUUID()) : FinishWindowTracker.State.NONE;
    }

    @Nullable
    public static Execution executionOf(LivingEntity victim) {
        return victim.level() instanceof ServerLevel level
            ? state(level).byVictim.get(victim.getUUID()) : null;
    }

    public static boolean isExecuting(Player player) {
        return player.level() instanceof ServerLevel level
            && state(level).byActor.containsKey(player.getUUID());
    }

    /** True while an execution or a double latch holds this enemy. */
    public static boolean isHeld(LivingEntity victim) {
        return victim.level() instanceof ServerLevel level
            && state(level).byVictim.containsKey(victim.getUUID());
    }

    public static boolean isCandidateEnemy(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity instanceof Player
            || !(entity instanceof Enemy) || !living.isAlive()) {
            return false;
        }
        if (entity.getType().is(Tags.EntityTypes.BOSSES)) {
            return false;
        }
        return entity.getBbHeight() <= MAX_VICTIM_HEIGHT;
    }

    public static EnemyClass classify(LivingEntity victim) {
        if (victim instanceof RaiderEntity raider) {
            return EnemyClass.of(true, raider.variant() == RaiderEntity.Variant.BRUTE,
                raider.isCaptain(), raider.isGoblinThiefDemo());
        }
        return EnemyClass.of(false, false, false, false);
    }

    public static boolean inReach(Player player, LivingEntity target, double slack) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz) - target.getBbWidth() * 0.5D;
        double dy = target.getY() - player.getY();
        return horizontal <= HearthsteadServerConfig.finisherReach() + slack
            && dy > -2.0D && dy < 2.0D;
    }

    public static boolean hasLineOfSight(Player player, LivingEntity target) {
        Vec3 eye = player.getEyePosition();
        Vec3[] points = {
            target.getBoundingBox().getCenter(),
            target.getEyePosition(),
            target.position().add(0.0D, target.getBbHeight() * 0.25D, 0.0D)
        };
        for (Vec3 point : points) {
            HitResult hit = player.level().clip(new ClipContext(eye, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ requests

    /** Network entry point (main server thread). */
    public static void handleRequest(ServerPlayer player, int targetId) {
        Entity target = player.level().getEntity(targetId);
        Result result = request(player, target instanceof LivingEntity living ? living : null);
        if (!result.accepted()) {
            Hearthstead.LOGGER.debug("Finisher request by {} on {} rejected: {}",
                player.getGameProfile().getName(), targetId, result);
        }
    }

    public static Result request(Player player, @Nullable LivingEntity target) {
        if (!HearthsteadServerConfig.finisherEnabled()) {
            return Result.DISABLED;
        }
        if (!(player.level() instanceof ServerLevel level) || !player.isAlive()
            || player.isSpectator() || com.hearthstead.revive.ReviveService.isDowned(player)
            || target == null || target.level() != level
            || !isCandidateEnemy(target)) {
            return Result.INVALID_TARGET;
        }
        LevelState st = state(level);
        long now = level.getGameTime();
        if (st.byActor.containsKey(player.getUUID())) {
            return Result.BUSY;
        }
        UUID id = target.getUUID();
        DoubleFinisherLatch.Pending pending = st.latch.get(id);
        if (pending != null) {
            if (!inReach(player, target, REACH_SLACK)) {
                return Result.OUT_OF_REACH;
            }
            if (!hasLineOfSight(player, target)) {
                return Result.NO_LINE_OF_SIGHT;
            }
            DoubleFinisherLatch.Pending joined = st.latch.join(id, player.getUUID(), now);
            Execution held = st.byVictim.get(id);
            if (joined == null || held == null || !held.isReady()) {
                return Result.BUSY;
            }
            startDouble(level, st, held, player);
            return Result.JOINED_DOUBLE;
        }
        if (st.byVictim.containsKey(id)) {
            return Result.BUSY;
        }
        if (!st.windows.isOpen(id, now)) {
            return Result.NO_WINDOW;
        }
        if (!inReach(player, target, REACH_SLACK)) {
            return Result.OUT_OF_REACH;
        }
        if (!hasLineOfSight(player, target)) {
            return Result.NO_LINE_OF_SIGHT;
        }
        // Server-authoritative: both conditions (off-balance, below 10% HP) re-checked on use.
        if (!st.windows.reserve(id, now, target.getHealth(), target.getMaxHealth())) {
            return Result.NO_WINDOW;
        }
        EnemyClass enemy = classify(target);
        Execution e = new Execution(target, player, enemy);
        freezeVictim(e);
        st.byVictim.put(id, e);
        st.byActor.put(player.getUUID(), e);
        if (enemy.allowsDouble() && partnerCandidateExists(level, st, player, target)) {
            st.latch.open(id, player.getUUID(), now);
            e.startTick = now;
            placeSolo(level, e, FinisherVariant.DOUBLE_PIN_EXECUTION.actorDistance());
            broadcastStart(e, 0);
            broadcastWindow(target, FinisherPayloads.Window.JOIN, DoubleFinisherLatch.PAIR_TICKS);
            return Result.PENDING_DOUBLE;
        }
        startSolo(level, st, e);
        return Result.STARTED;
    }

    private static boolean partnerCandidateExists(ServerLevel level, LevelState st, Player lead,
                                                  LivingEntity victim) {
        for (Player other : level.players()) {
            if (other != lead && other.isAlive() && !other.isSpectator()
                && !com.hearthstead.revive.ReviveService.isDowned(other)
                && !st.byActor.containsKey(other.getUUID())
                && inReach(other, victim, 1.0D) && hasLineOfSight(other, victim)) {
                return true;
            }
        }
        return false;
    }

    private static void startSolo(ServerLevel level, LevelState st, Execution e) {
        WeaponClass weapon = WeaponClass.of(e.lead.getMainHandItem());
        FinisherVariant previous = st.lastVariant.get(e.lead.getUUID());
        FinisherVariant variant = qaNextVariant != null && !qaNextVariant.isDouble()
            ? qaNextVariant : FinisherSelector.pick(weapon, e.enemy, previous,
                bound -> level.random.nextInt(bound));
        qaNextVariant = null;
        st.lastVariant.put(e.lead.getUUID(), variant);
        e.variant = variant;
        e.startTick = level.getGameTime();
        placeSolo(level, e, variant.actorDistance());
        broadcastStart(e, 0);
        broadcastWindow(e.victim, FinisherPayloads.Window.CLOSED, 0);
        playStartCue(level, e);
    }

    private static void startDouble(ServerLevel level, LevelState st, Execution e, Player partner) {
        e.partner = partner;
        e.variant = FinisherVariant.DOUBLE_PIN_EXECUTION;
        e.startTick = level.getGameTime();
        st.byActor.put(partner.getUUID(), e);
        placeDouble(level, e);
        broadcastStart(e, 0);
        broadcastWindow(e.victim, FinisherPayloads.Window.CLOSED, 0);
        playStartCue(level, e);
    }

    private static void playStartCue(ServerLevel level, Execution e) {
        level.playSound(null, e.lead.getX(), e.lead.getY(), e.lead.getZ(),
            FinisherSounds.WINDUP, SoundSource.PLAYERS, 1.0F, 0.95F + level.random.nextFloat() * 0.1F);
    }

    // ------------------------------------------------------------------ staging

    private static void freezeVictim(Execution e) {
        LivingEntity victim = e.victim;
        if (victim instanceof Mob mob) {
            e.restoreAi = !mob.isNoAi();
            mob.getNavigation().stop();
            mob.setNoAi(true);
            // Self-healing if the chunk is saved mid-move (NoAI is persisted by vanilla).
            mob.getPersistentData().putBoolean(FROZEN_TAG, e.restoreAi);
        }
        if (victim instanceof RaiderEntity raider) {
            raider.cancelPendingMeleeMove();
            raider.clearCinematicOpportunity(CinematicOpportunity.ClearReason.TARGET_LOST);
        }
        victim.setDeltaMovement(Vec3.ZERO);
        e.victimAnchor = victim.position();
    }

    private static void unfreezeVictim(Execution e) {
        if (e.victim instanceof Mob mob) {
            mob.getPersistentData().remove(FROZEN_TAG);
            if (e.restoreAi && mob.isAlive()) {
                mob.setNoAi(false);
            }
        }
    }

    /** A victim saved while frozen by an execution gets its AI back when it loads again. */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)
            || !mob.getPersistentData().contains(FROZEN_TAG)) {
            return;
        }
        LevelState st = event.getLevel() instanceof ServerLevel level ? STATES.get(level) : null;
        if (st != null && st.byVictim.containsKey(mob.getUUID())) {
            return;
        }
        boolean restore = mob.getPersistentData().getBoolean(FROZEN_TAG);
        mob.getPersistentData().remove(FROZEN_TAG);
        if (restore) {
            mob.setNoAi(false);
        }
    }

    /** Victim faces the lead; the lead steps to the move's distance if the ground allows. */
    private static void placeSolo(ServerLevel level, Execution e, double distance) {
        Vec3 v = e.victimAnchor;
        Vec3 toLead = horizontal(e.lead.position().subtract(v), e.lead.getLookAngle().reverse());
        e.victimYaw = yawToward(toLead);
        e.leadYaw = Mth.wrapDegrees(e.victimYaw + 180.0F);
        e.leadAnchor = slot(level, e.lead, v.add(toLead.scale(distance)));
        applyVictimPose(e);
        moveActor(e.lead, e.leadAnchor, e.leadYaw);
    }

    /** Partner in front of the victim, lead at the victim's left side, both facing it. */
    private static void placeDouble(ServerLevel level, Execution e) {
        Vec3 v = e.victimAnchor;
        Player partner = e.partner;
        Vec3 toPartner = horizontal(partner.position().subtract(v), partner.getLookAngle().reverse());
        e.victimYaw = yawToward(toPartner);
        // An entity facing +Z has its left hand at +X: left = (fz, -fx).
        Vec3 left = new Vec3(toPartner.z, 0.0D, -toPartner.x);
        e.partnerYaw = Mth.wrapDegrees(e.victimYaw + 180.0F);
        e.leadYaw = yawToward(left.reverse());
        e.partnerAnchor = slot(level, partner,
            v.add(toPartner.scale(FinisherVariant.DOUBLE_PARTNER_DISTANCE)));
        e.leadAnchor = slot(level, e.lead,
            v.add(left.scale(FinisherVariant.DOUBLE_PIN_EXECUTION.actorDistance())));
        applyVictimPose(e);
        moveActor(e.lead, e.leadAnchor, e.leadYaw);
        moveActor(partner, e.partnerAnchor, e.partnerYaw);
    }

    private static Vec3 horizontal(Vec3 v, Vec3 fallback) {
        Vec3 flat = new Vec3(v.x, 0.0D, v.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(fallback.x, 0.0D, fallback.z);
        }
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        return flat.normalize();
    }

    /** Minecraft yaw that looks along {@code dir} (horizontal). */
    public static float yawToward(Vec3 dir) {
        return Mth.wrapDegrees((float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90.0F);
    }

    /** The authored slot if the body fits there on solid footing, else where the actor stands. */
    private static Vec3 slot(ServerLevel level, Player actor, Vec3 wanted) {
        Vec3 target = new Vec3(wanted.x, actor.getY(), wanted.z);
        AABB box = actor.getBoundingBox().move(target.subtract(actor.position()));
        BlockPos below = BlockPos.containing(target.x, target.y - 0.2D, target.z);
        boolean footing = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        if (footing && level.noCollision(actor, box)) {
            return target;
        }
        return actor.position();
    }

    private static void applyVictimPose(Execution e) {
        LivingEntity v = e.victim;
        v.moveTo(e.victimAnchor.x, e.victimAnchor.y, e.victimAnchor.z, e.victimYaw, 0.0F);
        v.setYRot(e.victimYaw);
        v.setYHeadRot(e.victimYaw);
        v.setYBodyRot(e.victimYaw);
        v.setDeltaMovement(Vec3.ZERO);
    }

    private static void moveActor(Player actor, Vec3 anchor, float yaw) {
        if (actor.level() instanceof ServerLevel level) {
            actor.teleportTo(level, anchor.x, anchor.y, anchor.z,
                EnumSet.noneOf(RelativeMovement.class), yaw,
                Mth.clamp(actor.getXRot(), -10.0F, 35.0F));   // keep the player's own view pitch
        }
        actor.setYHeadRot(yaw);
        actor.setYBodyRot(yaw);
        actor.setDeltaMovement(Vec3.ZERO);
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelState st = STATES.get(level);
        if (st == null && level.players().isEmpty()) {
            return;
        }
        st = state(level);
        long now = level.getGameTime();
        tickWindows(level, st, now);
        if (!QA_AUTO_JOIN.isEmpty()) {
            for (Map.Entry<UUID, ServerPlayer> join : new ArrayList<>(QA_AUTO_JOIN.entrySet())) {
                DoubleFinisherLatch.Pending p = st.latch.get(join.getKey());
                if (p != null && now - p.openedAt() >= 4) {
                    QA_AUTO_JOIN.remove(join.getKey());
                    Entity victim = level.getEntity(join.getKey());
                    if (victim instanceof LivingEntity living) {
                        request(join.getValue(), living);
                    }
                }
            }
        }
        for (DoubleFinisherLatch.Pending lapsed : st.latch.drainExpired(now)) {
            Execution e = st.byVictim.get(lapsed.victim());
            if (e == null || !e.isReady()) {
                continue;
            }
            if (!e.lead.isAlive() || e.lead.isRemoved() || !e.victim.isAlive()) {
                abort(level, st, e);
            } else {
                startSolo(level, st, e);
            }
        }
        if (!st.byVictim.isEmpty()) {
            for (Execution e : new ArrayList<>(st.byVictim.values())) {
                tickExecution(level, st, e, now);
            }
        }
    }

    private static void tickWindows(ServerLevel level, LevelState st, long now) {
        Set<LivingEntity> candidates = new HashSet<>();
        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            candidates.addAll(level.getEntitiesOfClass(RaiderEntity.class,
                player.getBoundingBox().inflate(SCAN_RADIUS), LivingEntity::isAlive));
        }
        for (java.util.Iterator<UUID> it = st.watched.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity entity = level.getEntity(id);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                it.remove();
                st.wasStaggered.remove(id);
                if (!st.byVictim.containsKey(id)) {
                    st.windows.forget(id);
                }
                continue;
            }
            if (st.windows.state(id) == FinishWindowTracker.State.NONE
                && !st.windows.isOffBalance(id, now) && !stunned(living)) {
                it.remove();
                st.wasStaggered.remove(id);
                continue;
            }
            candidates.add(living);
        }
        for (LivingEntity living : candidates) {
            if (!isCandidateEnemy(living) || st.byVictim.containsKey(living.getUUID())) {
                continue;
            }
            UUID id = living.getUUID();
            // Off-balance starts on the rising edge of any stagger/stun (guard heavy, bash,
            // combo, brute whiff recovery, warhammer or captain stun, role stagger).
            boolean staggeredNow = stunned(living);
            if (staggeredNow && st.wasStaggered.add(id)) {
                markOffBalance(level, st, living, FinishWindowTracker.OFF_BALANCE_TICKS);
            } else if (!staggeredNow) {
                st.wasStaggered.remove(id);
            }
            FinishWindowTracker.Transition t = st.windows.evaluate(id, now,
                living.getHealth(), living.getMaxHealth());
            if (t == FinishWindowTracker.Transition.OPENED) {
                st.watched.add(id);
                st.guardRoll.remove(id);
                broadcastWindow(living, FinisherPayloads.Window.OPEN, st.windows.ticksLeft(id, now));
            } else if (t == FinishWindowTracker.Transition.CLOSED) {
                st.guardRoll.remove(id);
                broadcastWindow(living, FinisherPayloads.Window.CLOSED, 0);
            }
        }
    }

    /** Staggered, recovering from a whiffed blow, or stunned right now. */
    static boolean stunned(LivingEntity living) {
        if (living instanceof RaiderEntity raider
            && (raider.isStaggered() || raider.isRecoveringFromWhiff())) {
            return true;
        }
        var slow = living.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= STUN_SLOWNESS_AMPLIFIER;
    }

    /**
     * Knocks an enemy off balance for {@code ticks}: the first of the two
     * finisher conditions. Synced to tracking clients (the wobble). Other
     * lanes (parry, new weapons) call this through FinisherHooks.
     */
    public static void markOffBalance(LivingEntity entity, int ticks) {
        if (entity.level() instanceof ServerLevel level && isCandidateEnemy(entity)) {
            markOffBalance(level, state(level), entity, ticks);
        }
    }

    private static void markOffBalance(ServerLevel level, LevelState st, LivingEntity entity, int ticks) {
        long now = level.getGameTime();
        st.windows.markOffBalance(entity.getUUID(), now, ticks);
        st.watched.add(entity.getUUID());
        PacketDistributor.sendToPlayersTrackingEntity(entity, new FinisherPayloads.Balance(entity.getId(),
            st.windows.offBalanceTicksLeft(entity.getUUID(), now)));
    }

    public static boolean isOffBalance(LivingEntity entity) {
        return entity.level() instanceof ServerLevel level
            && state(level).windows.isOffBalance(entity.getUUID(), level.getGameTime());
    }

    private static void tickExecution(ServerLevel level, LevelState st, Execution e, long now) {
        LivingEntity victim = e.victim;
        if (!e.killed && (victim.isRemoved() || !victim.isAlive() || victim.level() != level)) {
            abort(level, st, e);
            return;
        }
        if (!e.killed && !e.guard && !validActor(e.lead, level)) {
            abort(level, st, e);
            return;
        }
        hold(e);
        if (e.isReady()) {
            return;
        }
        if (!e.killed && now >= e.killTick()) {
            kill(level, st, e);
        }
        if (e.killed && !e.bodyFell && now >= e.killTick() + FinisherTimeline.HIT_STOP_TICKS
            + BODY_FALL_AFTER_IMPACT) {
            e.bodyFell = true;
            bodyFall(level, e);
        }
        if (e.killed && !e.cheered && now >= e.killTick() + CHEER_AFTER_IMPACT) {
            e.cheered = true;
            guardsCheer(level, e);
        }
        if (now >= e.endTick()) {
            releaseActors(st, e);
            if (e.bodyFell && e.cheered) {
                finish(level, st, e);
            }
        }
    }

    private static boolean validActor(@Nullable Player actor, ServerLevel level) {
        return actor != null && actor.isAlive() && !actor.isRemoved() && actor.level() == level;
    }

    private static void hold(Execution e) {
        LivingEntity v = e.victim;
        if (!e.guard) {
            if (v.position().distanceToSqr(e.victimAnchor) > 0.0025D
                || Math.abs(Mth.wrapDegrees(v.getYRot() - e.victimYaw)) > 0.5F) {
                applyVictimPose(e);
            }
            v.setDeltaMovement(Vec3.ZERO);
            v.setYBodyRot(e.victimYaw);
            v.setYHeadRot(e.victimYaw);
        }
        if (e.guard) {
            return;
        }
        holdActor(e.lead, e.leadAnchor, e.leadYaw, e.killed);
        if (e.partner != null && e.partnerAnchor != null) {
            holdActor(e.partner, e.partnerAnchor, e.partnerYaw, e.killed);
        }
    }

    private static void holdActor(Player actor, Vec3 anchor, float yaw, boolean afterKill) {
        if (!actor.isAlive()) {
            return;
        }
        actor.setDeltaMovement(Vec3.ZERO);
        if (actor.position().distanceToSqr(anchor) > 0.35D * 0.35D) {
            moveActor(actor, anchor, yaw);
        }
    }

    private static void kill(ServerLevel level, LevelState st, Execution e) {
        LivingEntity victim = e.victim;
        Entity attacker = e.lead;
        DamageSource source = executionSource(level, attacker);
        killing = victim;
        try {
            victim.invulnerableTime = 0;
            victim.hurt(source, 1.0E6F);
            if (victim.isAlive()) {
                // A totem, an odd mod or a damage cap: the execution still resolves, once.
                victim.setHealth(0.0F);
                victim.die(source);
            }
        } finally {
            killing = null;
        }
        e.killed = true;
        st.windows.markExecuted(victim.getUUID());
        st.watched.remove(victim.getUUID());
        impactEffects(level, e);
        if (!e.guard) {
            FinisherStats.record(e.lead, e.variant != null && e.variant.isDouble());
            if (e.partner != null) {
                FinisherStats.record(e.partner, true);
            }
        }
        if (victim instanceof RaiderEntity raider && raider.isCaptain()) {
            FinisherTrophies.drop(level, raider, e.guard ? null : e.lead,
                e.variant != null && e.variant.isDouble());
        }
    }

    public static DamageSource executionSource(ServerLevel level, @Nullable Entity attacker) {
        Holder<DamageType> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
            .getHolderOrThrow(EXECUTION);
        return new DamageSource(type, attacker, attacker);
    }

    private static void finish(ServerLevel level, LevelState st, Execution e) {
        remove(st, e);
        broadcastEnd(e, false);
        if (e.victim.isRemoved()) {
            st.windows.forget(e.victim.getUUID());
        }
    }

    /** The lock ends with the move; the victim's fall may still be settling. */
    private static void releaseActors(LevelState st, Execution e) {
        if (e.lead != null) {
            st.byActor.remove(e.lead.getUUID(), e);
        }
        if (e.partner != null) {
            st.byActor.remove(e.partner.getUUID(), e);
        }
    }

    private static void abort(ServerLevel level, LevelState st, Execution e) {
        remove(st, e);
        st.latch.cancel(e.victim.getUUID());
        if (!e.killed) {
            st.windows.release(e.victim.getUUID(), level.getGameTime());
            unfreezeVictim(e);
        }
        broadcastEnd(e, true);
    }

    private static void remove(LevelState st, Execution e) {
        st.byVictim.remove(e.victim.getUUID(), e);
        releaseActors(st, e);
    }

    // ------------------------------------------------------------------ effects

    private static void impactEffects(ServerLevel level, Execution e) {
        LivingEntity v = e.victim;
        float scale = e.enemy.impactScale();
        boolean dbl = e.variant != null && e.variant.isDouble();
        WeaponClass weapon = WeaponClass.of(e.lead.getMainHandItem());
        double x = v.getX();
        double y = v.getY();
        double z = v.getZ();
        // Sound layers: the stinger, the body blow, and the weapon's own voice.
        level.playSound(null, x, y, z, dbl ? FinisherSounds.DOUBLE : stingerFor(e.lead.getMainHandItem(), weapon),
            SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, x, y, z, ModSounds.COMBAT_HEAVY_IMPACT.get(), SoundSource.PLAYERS,
            0.9F, 0.85F + level.random.nextFloat() * 0.1F);
        SoundEvent voice = switch (weapon) {
            case SWORD, AXE -> ModSounds.BLADE_HIT.get();
            case MACE -> ModSounds.SHIELD_THUD.get();
            case BARE -> SoundEvents.PLAYER_ATTACK_KNOCKBACK;
        };
        level.playSound(null, x, y, z, voice, SoundSource.PLAYERS, 1.0F,
            0.8F + level.random.nextFloat() * 0.1F);
        // Contact point: chest height on the side facing the executor.
        Vec3 facing = Vec3.directionFromRotation(0.0F, e.victimYaw);
        Vec3 chest = new Vec3(x, y + v.getBbHeight() * 0.62D, z).add(facing.scale(v.getBbWidth() * 0.5D));
        level.sendParticles(ParticleTypes.FLASH, chest.x, chest.y, chest.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CRIT, chest.x, chest.y, chest.z,
            (int) (12 + 10 * scale), 0.25D, 0.25D, 0.25D, 0.45D);
        if (weapon == WeaponClass.SWORD || weapon == WeaponClass.AXE) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, chest.x, chest.y, chest.z, 1, 0, 0, 0, 0);
        }
        if (e.variant != null && e.variant.sparks()) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, chest.x, chest.y + 0.2D, chest.z,
                18, 0.2D, 0.2D, 0.2D, 0.35D);
        }
        groundDust(level, v, (int) (10 + 22 * scale), 0.35D + 0.5D * scale, 0.05D);
    }

    private static void bodyFall(ServerLevel level, Execution e) {
        LivingEntity v = e.victim;
        float scale = e.enemy.impactScale();
        level.playSound(null, v.getX(), v.getY(), v.getZ(), FinisherSounds.BODY_FALL,
            SoundSource.PLAYERS, 0.8F + 0.3F * scale, 1.05F - 0.15F * scale);
        groundDust(level, v, (int) (16 + 30 * scale), 0.5D + 0.6D * scale, 0.08D);
        level.sendParticles(ParticleTypes.POOF, v.getX(), v.getY() + 0.1D, v.getZ(),
            (int) (4 + 6 * scale), 0.4D * scale + 0.2D, 0.05D, 0.4D * scale + 0.2D, 0.02D);
    }

    private static void groundDust(ServerLevel level, LivingEntity v, int count, double spread,
                                   double speed) {
        BlockPos below = BlockPos.containing(v.getX(), v.getY() - 0.2D, v.getZ());
        BlockState ground = level.getBlockState(below);
        if (ground.isAir()) {
            return;
        }
        ParticleOptions dust = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        level.sendParticles(dust, v.getX(), v.getY() + 0.1D, v.getZ(), count,
            spread, 0.05D, spread, speed);
    }

    private static void guardsCheer(ServerLevel level, Execution e) {
        LivingEntity v = e.victim;
        List<SettlerEntity> guards = level.getEntitiesOfClass(SettlerEntity.class,
            v.getBoundingBox().inflate(CHEER_RADIUS),
            s -> s.isAlive() && s.getProfession().martial());
        if (guards.isEmpty()) {
            return;
        }
        SettlerEntity loudest = guards.get(0);
        for (SettlerEntity guard : guards) {
            if (guard.getTarget() == null || !guard.getTarget().isAlive()) {
                guard.celebrate();
            }
            if (guard.distanceToSqr(v) < loudest.distanceToSqr(v)) {
                loudest = guard;
            }
        }
        level.playSound(null, loudest.getX(), loudest.getY(), loudest.getZ(),
            FinisherSounds.GUARD_CHEER, SoundSource.NEUTRAL, 0.9F,
            0.95F + level.random.nextFloat() * 0.1F);
    }

    // ------------------------------------------------------------------ guards

    /**
     * Gate on a guard claiming a raider's cinematic opportunity. Never while a
     * player execution holds the raider. Guards execute only STAGGERED enemies
     * (spec section 5; a window opened merely by low health keeps the drive's
     * ordinary-damage presentation), rolled once per window so they stay
     * rarer than players.
     */
    public static boolean guardMayClaim(SettlerEntity guard, RaiderEntity raider) {
        if (!(raider.level() instanceof ServerLevel level)) {
            return true;
        }
        LevelState st = state(level);
        UUID id = raider.getUUID();
        if (st.byVictim.containsKey(id) || st.latch.isPending(id)) {
            return false;
        }
        if (!HearthsteadServerConfig.finisherEnabled()
            || !st.windows.isOpen(id, level.getGameTime())) {
            return true;
        }
        return st.guardRoll.computeIfAbsent(id,
            k -> level.random.nextDouble() < HearthsteadServerConfig.finisherGuardChance());
    }

    /**
     * The guard's finishing drive just connected. Inside an open window this
     * becomes a real execution (guaranteed kill, the shared impact staging);
     * otherwise nothing happens and ordinary damage stands.
     *
     * @return true when the raider was executed
     */
    public static boolean onGuardFinisherContact(SettlerEntity guard, RaiderEntity raider) {
        if (!(raider.level() instanceof ServerLevel level) || !raider.isAlive()
            || !HearthsteadServerConfig.finisherEnabled()) {
            return false;
        }
        LevelState st = state(level);
        UUID id = raider.getUUID();
        long now = level.getGameTime();
        if (st.byVictim.containsKey(id) || !Boolean.TRUE.equals(st.guardRoll.get(id))
            || !st.windows.reserve(id, now, raider.getHealth(), raider.getMaxHealth())) {
            return false;
        }
        Execution e = new Execution(raider, null, classify(raider));
        e.guard = true;
        e.variant = FinisherVariant.SWORD_PARRY_THRUST;
        // Staged from its impact frame: the guard's own drive already played the wind-up.
        e.startTick = now - e.variant.impactTick();
        e.victimAnchor = raider.position();
        e.victimYaw = yawToward(horizontal(guard.position().subtract(raider.position()),
            guard.getLookAngle().reverse()));
        e.leadYaw = Mth.wrapDegrees(e.victimYaw + 180.0F);
        applyVictimPose(e);
        st.byVictim.put(id, e);
        broadcastStart(e, e.variant.impactTick());
        broadcastWindow(raider, FinisherPayloads.Window.CLOSED, 0);
        Entity attacker = guard;
        DamageSource source = executionSource(level, attacker);
        killing = raider;
        try {
            raider.invulnerableTime = 0;
            raider.hurt(source, 1.0E6F);
            if (raider.isAlive()) {
                raider.setHealth(0.0F);
                raider.die(source);
            }
        } finally {
            killing = null;
        }
        e.killed = true;
        st.windows.markExecuted(id);
        st.watched.remove(id);
        impactEffectsGuard(level, e, guard);
        if (raider.isCaptain()) {
            FinisherTrophies.drop(level, raider, null, false);
        }
        return true;
    }

    /** Each weapon family has its own finishing stinger (sound pass); swords keep the original. */
    private static SoundEvent stingerFor(net.minecraft.world.item.ItemStack stack, WeaponClass weapon) {
        if (stack.getItem() instanceof com.hearthstead.item.role.RoleWeaponItem role
            && role.kind() == com.hearthstead.item.role.RoleWeaponItem.Kind.SPEAR) {
            return ModSounds.EXECUTION_STINGER_SPEAR.get();
        }
        return switch (weapon) {
            case AXE -> ModSounds.EXECUTION_STINGER_AXE.get();
            case MACE -> ModSounds.EXECUTION_STINGER_MACE.get();
            case BARE -> ModSounds.EXECUTION_STINGER_BARE.get();
            default -> FinisherSounds.STINGER;
        };
    }

    private static void impactEffectsGuard(ServerLevel level, Execution e, SettlerEntity guard) {
        LivingEntity v = e.victim;
        level.playSound(null, v.getX(), v.getY(), v.getZ(),
            stingerFor(guard.getMainHandItem(), WeaponClass.of(guard.getMainHandItem())),
            SoundSource.NEUTRAL, 0.9F, 1.0F);
        Vec3 facing = Vec3.directionFromRotation(0.0F, e.victimYaw);
        Vec3 chest = new Vec3(v.getX(), v.getY() + v.getBbHeight() * 0.62D, v.getZ())
            .add(facing.scale(v.getBbWidth() * 0.5D));
        level.sendParticles(ParticleTypes.FLASH, chest.x, chest.y, chest.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CRIT, chest.x, chest.y, chest.z, 16, 0.25D, 0.25D,
            0.25D, 0.4D);
        groundDust(level, v, 20, 0.6D, 0.05D);
    }

    // ------------------------------------------------------------------ network

    static void broadcastWindow(LivingEntity entity, byte state, int ticksLeft) {
        if (entity.level() instanceof ServerLevel) {
            com.hearthstead.network.PayloadSend.toTracking(entity,
                new FinisherPayloads.Window(entity.getId(), state, ticksLeft));
        }
    }

    private static FinisherPayloads.Start startPayload(Execution e, int elapsed) {
        int flags = e.guard ? FinisherPayloads.Start.FLAG_GUARD : 0;
        return new FinisherPayloads.Start(e.victim.getId(),
            e.lead == null ? -1 : e.lead.getId(),
            e.partner == null ? -1 : e.partner.getId(),
            e.variant == null ? -1 : e.variant.ordinal(), elapsed,
            e.victimYaw, e.leadYaw, e.partnerYaw, flags);
    }

    private static void broadcastStart(Execution e, int elapsed) {
        FinisherPayloads.Start payload = startPayload(e, elapsed);
        com.hearthstead.network.PayloadSend.toTracking(e.victim, payload);
        sendToActor(e.lead, e.victim, payload);
        sendToActor(e.partner, e.victim, payload);
    }

    private static void broadcastEnd(Execution e, boolean aborted) {
        FinisherPayloads.End payload = new FinisherPayloads.End(e.victim.getId(), aborted);
        if (e.victim.level() instanceof ServerLevel && !e.victim.isRemoved()) {
            com.hearthstead.network.PayloadSend.toTracking(e.victim, payload);
        }
        sendToActor(e.lead, e.victim, payload);
        sendToActor(e.partner, e.victim, payload);
    }

    /** Actors always hear about their own execution (clients de-duplicate). */
    private static void sendToActor(@Nullable Player actor, LivingEntity victim,
                                    net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (actor instanceof ServerPlayer sp && sp.connection != null
            && !(sp instanceof net.neoforged.neoforge.common.util.FakePlayer)) {
            com.hearthstead.network.PayloadSend.toPlayer(sp, payload);
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(event.getTarget() instanceof LivingEntity target)
            || !(target.level() instanceof ServerLevel level)) {
            return;
        }
        LevelState st = STATES.get(level);
        if (st == null) {
            return;
        }
        long now = level.getGameTime();
        Execution e = st.byVictim.get(target.getUUID());
        if (e != null) {
            int elapsed = e.isReady() ? 0 : (int) Math.max(0L, now - e.startTick);
            com.hearthstead.network.PayloadSend.toPlayer(player, startPayload(e, elapsed));
        } else if (st.windows.isOpen(target.getUUID(), now)) {
            com.hearthstead.network.PayloadSend.toPlayer(player, new FinisherPayloads.Window(target.getId(),
                FinisherPayloads.Window.OPEN, st.windows.ticksLeft(target.getUUID(), now)));
        }
    }

    // ------------------------------------------------------------------ guards on damage

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelState st = STATES.get(level);
        if (st == null || st.byVictim.isEmpty()) {
            return;
        }
        boolean bypass = event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        if (entity instanceof Player player) {
            // Standard for executions: the executor cannot be interrupted or hurt.
            if (!bypass && st.byActor.containsKey(player.getUUID())) {
                event.setCanceled(true);
            }
            return;
        }
        Execution e = st.byVictim.get(entity.getUUID());
        if (e != null && entity != killing && !bypass) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelState st = STATES.get(level);
        if (st != null && (st.byVictim.containsKey(entity.getUUID())
            || st.byActor.containsKey(entity.getUUID()))) {
            event.setCanceled(true);
            return;
        }
        // A big knockback sends an enemy stumbling: off balance.
        if (event.getStrength() >= BIG_KNOCKBACK && entity instanceof Enemy && isCandidateEnemy(entity)) {
            markOffBalance(level, state(level), entity, FinishWindowTracker.OFF_BALANCE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player
            || !(entity instanceof Enemy) || !entity.isAlive()) {
            return;
        }
        LevelState st = state(level);
        long now = level.getGameTime();
        UUID id = entity.getUUID();
        st.watched.add(id);
        // A player's heavy hit knocks the enemy off balance (a big blow for its size).
        if (event.getSource().getEntity() instanceof Player
            && event.getNewDamage() >= Math.max(PLAYER_HEAVY_HIT_MIN,
                entity.getMaxHealth() * PLAYER_HEAVY_HIT_FRACTION)) {
            markOffBalance(level, st, entity, FinishWindowTracker.OFF_BALANCE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelState st = STATES.get(level);
        if (st == null) {
            return;
        }
        Entity entity = event.getEntity();
        UUID id = entity.getUUID();
        Execution asVictim = st.byVictim.get(id);
        if (asVictim != null && !asVictim.killed) {
            abort(level, st, asVictim);
        }
        Execution asActor = st.byActor.get(id);
        if (asActor != null && !asActor.killed && asActor.lead == entity) {
            abort(level, st, asActor);
        } else if (asActor != null && entity == asActor.partner) {
            st.byActor.remove(id, asActor);
        }
        if (!(entity instanceof Player) && st.byVictim.get(id) == null) {
            st.windows.forget(id);
            st.watched.remove(id);
            st.wasStaggered.remove(id);
            st.guardRoll.remove(id);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        killing = null;
    }

    // ------------------------------------------------------------------ test seams

    /**
     * GameTest/QA seam: knocks the enemy off balance now (as a stagger would)
     * and re-evaluates its window. The window only opens if its health is
     * ALSO below 10%, exactly as in play.
     */
    public static boolean forceOpenWindow(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        LevelState st = state(level);
        long now = level.getGameTime();
        markOffBalance(level, st, entity, FinishWindowTracker.OFF_BALANCE_TICKS);
        FinishWindowTracker.Transition t = st.windows.evaluate(entity.getUUID(), now,
            entity.getHealth(), entity.getMaxHealth());
        if (t == FinishWindowTracker.Transition.OPENED) {
            broadcastWindow(entity, FinisherPayloads.Window.OPEN, st.windows.ticksLeft(entity.getUUID(), now));
        }
        return st.windows.isOpen(entity.getUUID(), now);
    }

    /** Owner rule check for other execution paths (the knight Captain's EXECUTION special). */
    public static boolean executionAllowed(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel level) || !target.isAlive()) {
            return false;
        }
        LevelState st = state(level);
        return !st.byVictim.containsKey(target.getUUID()) && FinishWindowTracker.eligible(
            st.windows.isOffBalance(target.getUUID(), level.getGameTime()),
            target.getHealth(), target.getMaxHealth());
    }

    /** GameTest seam: pins the next guard roll for this raider. */
    public static void forceGuardRoll(RaiderEntity raider, boolean allowed) {
        if (raider.level() instanceof ServerLevel level) {
            state(level).guardRoll.put(raider.getUUID(), allowed);
        }
    }
}
