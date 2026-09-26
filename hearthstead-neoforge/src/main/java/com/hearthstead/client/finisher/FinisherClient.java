package com.hearthstead.client.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.finisher.FinisherPayloads;
import com.hearthstead.finisher.FinisherTimeline;
import com.hearthstead.finisher.FinisherVariant;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client half of executions: the synced finish windows (who glows), the R-key
 * interceptor shared with the Knights command, the executor's input lock, the
 * shared execution timelines every viewer samples, and the prompt.
 *
 * <p>Every client runs the same {@link FinisherTimeline} from the start
 * packet: identical animation, hit-stop, impact effects and fall for the
 * executor and all onlookers. Nothing here slows time or moves a camera,
 * except the small distance-scaled impact shake every nearby player gets.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FinisherClient {
    /** Client reach for the prompt (the server re-checks with its own config + slack). */
    public static final double CLIENT_REACH = 3.0D;
    /** Degrees off the crosshair a glowing enemy still counts as "looked at". */
    public static final double AIM_CONE_DEGREES = 22.0D;
    /** Victim clips keep posing this long after the impact (the fall and the lie). */
    public static final int VICTIM_TAIL_TICKS = 20;
    private static final int REQUEST_COOLDOWN = 6;

    /** One synced finish window. */
    record WindowView(byte state, long expiresAt) {
    }

    /** One execution as this client plays it. */
    public static final class Exec {
        final int victimId;
        final int leadId;
        final int partnerId;
        @Nullable final FinisherVariant variant;
        final boolean guard;
        final long startTick;
        final long startGameTime;
        final float victimYaw;
        final float leadYaw;
        final float partnerYaw;
        boolean impactFired;
        boolean ended;

        Exec(FinisherPayloads.Start p, long now, long levelTime) {
            victimId = p.victimId();
            leadId = p.leadId();
            partnerId = p.partnerId();
            variant = FinisherVariant.byOrdinal(p.variant());
            guard = p.isGuard();
            startTick = now - p.elapsedTicks();
            startGameTime = levelTime - p.elapsedTicks();
            victimYaw = p.victimYaw();
            leadYaw = p.leadYaw();
            partnerYaw = p.partnerYaw();
        }

        public float realTicks(float partial) {
            return (ticks - startTick) + partial;
        }

        @Nullable
        public FinisherVariant variant() {
            return variant;
        }

        public boolean isReady() {
            return variant == null;
        }

        /** Client level game time the clip started at (PlayerClips' clock). */
        public long startGameTime() {
            return startGameTime;
        }

        public boolean isGuard() {
            return guard;
        }

        public int victimId() {
            return victimId;
        }

        public int leadId() {
            return leadId;
        }

        public int partnerId() {
            return partnerId;
        }

        public float victimYaw() {
            return victimYaw;
        }

        public float yawOf(int entityId) {
            if (entityId == leadId) return leadYaw;
            if (entityId == partnerId) return partnerYaw;
            return victimYaw;
        }

        /** The actor lock (input, invulnerability) lasts the move plus the hit-stop. */
        public boolean actorsLocked(float partial) {
            return variant == null || realTicks(partial) < variant.lockTicks();
        }

        /** Bookkeeping horizon: the victim's fall and lie after the impact. */
        public boolean victimPosing(float partial) {
            return variant != null
                && realTicks(partial) < variant.impactTick() + FinisherTimeline.HIT_STOP_TICKS
                    + VICTIM_TAIL_TICKS;
        }
    }

    private static long ticks;
    private static final Map<Integer, WindowView> WINDOWS = new HashMap<>();
    /** Off-balance enemies: entity id -> client tick the wobble ends. */
    private static final Map<Integer, Long> BALANCE = new HashMap<>();
    /** Entities whose pose stack the wobble pushed this frame (popped in Post). */
    private static final java.util.Set<Integer> WOBBLE_PUSHED = new java.util.HashSet<>();
    private static final Map<Integer, Exec> BY_VICTIM = new HashMap<>();
    private static final Map<Integer, Exec> BY_ACTOR = new HashMap<>();
    private static long lastRequestTick = -100L;
    @Nullable private static LivingEntity promptTarget;
    private static byte promptState;
    private static final Map<Integer, Integer> SAVED_DEATH_TIME = new HashMap<>();

    private FinisherClient() {
    }

    public static long ticks() {
        return ticks;
    }

    /** Live executions (render thread; do not modify). */
    static java.util.Collection<Exec> executions() {
        return BY_VICTIM.values();
    }

    // ------------------------------------------------------------------ packets

    public static void acceptWindow(FinisherPayloads.Window payload) {
        if (payload.state() == FinisherPayloads.Window.CLOSED) {
            WINDOWS.remove(payload.entityId());
        } else {
            WINDOWS.put(payload.entityId(), new WindowView(payload.state(),
                ticks + Math.max(1, payload.ticksLeft())));
        }
    }

    public static void acceptBalance(FinisherPayloads.Balance payload) {
        if (payload.ticks() <= 0) {
            BALANCE.remove(payload.entityId());
        } else {
            BALANCE.put(payload.entityId(), ticks + payload.ticks());
        }
    }

    public static void acceptStart(FinisherPayloads.Start payload) {
        Exec existing = BY_VICTIM.get(payload.victimId());
        if (existing != null && existing.variant == FinisherVariant.byOrdinal(payload.variant())
            && existing.leadId == payload.leadId() && existing.partnerId == payload.partnerId()
            && Math.abs(existing.startTick - (ticks - payload.elapsedTicks())) <= 2) {
            return; // the same start delivered twice (tracking + actor copy)
        }
        if (existing != null) {
            dropActors(existing);
        }
        ClientLevel level = Minecraft.getInstance().level;
        Exec exec = new Exec(payload, ticks, level == null ? 0L : level.getGameTime());
        BY_VICTIM.put(exec.victimId, exec);
        if (!exec.guard) {
            BY_ACTOR.put(exec.leadId, exec);
            if (exec.partnerId >= 0) {
                BY_ACTOR.put(exec.partnerId, exec);
            }
        }
        if (!exec.isReady()) {
            WINDOWS.remove(exec.victimId);
            FinisherPoseHooks.startVictim(exec, level == null ? null : level.getEntity(exec.victimId),
                payload.elapsedTicks());
        }
        FinisherFx.onStart(exec);
    }

    public static void acceptEnd(FinisherPayloads.End payload) {
        Exec exec = BY_VICTIM.get(payload.victimId());
        if (exec == null) {
            return;
        }
        if (payload.aborted()) {
            BY_VICTIM.remove(exec.victimId);
            dropActors(exec);
            FinisherPoseHooks.stopVictim(exec.victimId);
        } else {
            exec.ended = true;
        }
    }

    private static void dropActors(Exec exec) {
        BY_ACTOR.remove(exec.leadId, exec);
        if (exec.partnerId >= 0) {
            BY_ACTOR.remove(exec.partnerId, exec);
        }
    }

    // ------------------------------------------------------------------ queries

    @Nullable
    public static Exec forVictim(Entity entity) {
        return BY_VICTIM.get(entity.getId());
    }

    @Nullable
    public static Exec forActor(Entity entity) {
        return BY_ACTOR.get(entity.getId());
    }

    public static boolean isLocalPlayerExecuting() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Exec exec = BY_ACTOR.get(player.getId());
        return exec != null && exec.actorsLocked(0.0F);
    }

    /** 0 = no glow; otherwise the pulsing torso glow strength (0.25..1). */
    public static float glowStrength(Entity entity, float partial) {
        WindowView view = WINDOWS.get(entity.getId());
        if (view == null || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return 0.0F;
        }
        float t = ticks + partial;
        long left = view.expiresAt() - ticks;
        // Calm heartbeat while open, quicker as the window closes, quickest while a
        // co-op partner is being waited for.
        float rate = view.state() == FinisherPayloads.Window.JOIN ? 1.4F : left < 12 ? 1.25F : 0.62F;
        float pulse = 0.5F + 0.5F * Mth.sin(t * rate);
        return 0.45F + 0.55F * pulse;
    }

    /**
     * Client flag for HUDs (the settler UI lane's look-at HP counter glyph):
     * true exactly while the server says BOTH finisher conditions hold (off
     * balance AND below 10% health), i.e. while the red torso glow shows.
     */
    public static boolean isFinishable(Entity entity) {
        return WINDOWS.containsKey(entity.getId());
    }

    /** Client flag: this enemy is currently off balance (wobbling). */
    public static boolean isOffBalance(Entity entity) {
        Long until = BALANCE.get(entity.getId());
        return until != null && until > ticks;
    }

    // ------------------------------------------------------------------ key

    /** The Knights-key interceptor (CommandKeyHooks); also the separate key's action. */
    public static boolean tryConsume(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (isLocalPlayerExecuting()) {
            return true; // swallow: the executor is locked in the move
        }
        if (FinisherClientSetup.separateKeyBound()) {
            return false; // finishers live on their own key; R stays pure Knights command
        }
        return tryStart(mc);
    }

    static boolean tryStart(Minecraft mc) {
        LivingEntity target = findTarget(mc);
        if (target == null) {
            return false;
        }
        if (ticks - lastRequestTick >= REQUEST_COOLDOWN) {
            lastRequestTick = ticks;
            PacketDistributor.sendToServer(new FinisherPayloads.Request(target.getId()));
        }
        return true;
    }

    /** The glowing enemy under (or right next to) the crosshair, in reach and in sight. */
    @Nullable
    static LivingEntity findTarget(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || WINDOWS.isEmpty() || player.isSpectator()
            || !player.isAlive() || com.hearthstead.revive.ReviveService.isDowned(player)) {
            return null;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity living
            && WINDOWS.containsKey(living.getId()) && inReach(player, living)) {
            return living;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double bestCos = Math.cos(Math.toRadians(AIM_CONE_DEGREES));
        LivingEntity best = null;
        for (Map.Entry<Integer, WindowView> entry : WINDOWS.entrySet()) {
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                || !inReach(player, living)) {
                continue;
            }
            Vec3 torso = living.position().add(0.0D, living.getBbHeight() * 0.55D, 0.0D);
            Vec3 to = torso.subtract(eye);
            double len = to.length();
            if (len < 1.0E-3D) {
                continue;
            }
            double cos = to.scale(1.0D / len).dot(look);
            if (cos < bestCos) {
                continue;
            }
            HitResult blocked = level.clip(new ClipContext(eye, torso, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
            if (blocked.getType() != HitResult.Type.MISS) {
                continue;
            }
            bestCos = cos;
            best = living;
        }
        return best;
    }

    static boolean inReach(LocalPlayer player, LivingEntity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz) - target.getBbWidth() * 0.5D;
        double dy = target.getY() - player.getY();
        return horizontal <= CLIENT_REACH && dy > -2.0D && dy < 2.0D;
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        ClientLevel level = mc.level;
        if (level == null) {
            WINDOWS.clear();
            BY_VICTIM.clear();
            BY_ACTOR.clear();
            return;
        }
        WINDOWS.values().removeIf(view -> view.expiresAt() < ticks);
        BALANCE.values().removeIf(until -> until <= ticks);
        FinisherClientSetup.pollSeparateKey(mc);
        tickExecutions(mc, level);
        tickGlowParticles(level);
        promptTarget = mc.screen == null && !isLocalPlayerExecuting() ? findTarget(mc) : null;
        promptState = promptTarget == null ? 0 : WINDOWS.getOrDefault(promptTarget.getId(),
            new WindowView((byte) 0, 0L)).state();
    }

    private static void tickExecutions(Minecraft mc, ClientLevel level) {
        for (Iterator<Exec> it = BY_VICTIM.values().iterator(); it.hasNext(); ) {
            Exec exec = it.next();
            Entity victim = level.getEntity(exec.victimId);
            float real = exec.realTicks(0.0F);
            if (exec.variant != null && !exec.impactFired && real >= exec.variant.impactTick()) {
                exec.impactFired = true;
                if (victim instanceof LivingEntity living) {
                    FinisherFx.onImpact(exec, living);
                }
            }
            boolean over = exec.variant != null && !exec.actorsLocked(0.0F)
                && !exec.victimPosing(0.0F);
            if (over || (victim == null && (exec.ended || !exec.actorsLocked(0.0F)))
                || real > 200.0F) {
                it.remove();
                dropActors(exec);
                continue;
            }
            if (exec.variant != null && !exec.actorsLocked(0.0F)) {
                dropActors(exec);
            }
            if (victim != null && !exec.guard) {
                lockYaw(victim, exec.victimYaw);
            }
            if (!exec.guard && exec.actorsLocked(0.0F)) {
                Entity lead = level.getEntity(exec.leadId);
                if (lead != null) {
                    lockYaw(lead, exec.leadYaw);
                }
                Entity partner = exec.partnerId >= 0 ? level.getEntity(exec.partnerId) : null;
                if (partner != null) {
                    lockYaw(partner, exec.partnerYaw);
                }
            }
        }
    }

    private static void lockYaw(Entity entity, float yaw) {
        entity.setYRot(yaw);
        entity.yRotO = yaw;
        if (entity instanceof LivingEntity living) {
            living.setYBodyRot(yaw);
            living.yBodyRotO = yaw;
            living.setYHeadRot(yaw);
            living.yHeadRotO = yaw;
        }
    }

    private static void tickGlowParticles(ClientLevel level) {
        if (WINDOWS.isEmpty() || !HearthsteadClientConfig.finisherGlowParticles() || (ticks & 1L) != 0L) {
            return;
        }
        DustParticleOptions ember = new DustParticleOptions(new Vector3f(1.0F, 0.12F, 0.05F), 0.85F);
        for (Integer id : new ArrayList<>(WINDOWS.keySet())) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            double w = living.getBbWidth() * 0.45D;
            double y = living.getY() + living.getBbHeight() * (0.45D + level.random.nextDouble() * 0.25D);
            level.addParticle(ember,
                living.getX() + (level.random.nextDouble() - 0.5D) * 2.0D * w, y,
                living.getZ() + (level.random.nextDouble() - 0.5D) * 2.0D * w,
                0.0D, 0.015D, 0.0D);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BALANCE.clear();
        WOBBLE_PUSHED.clear();
        WINDOWS.clear();
        BY_VICTIM.clear();
        BY_ACTOR.clear();
        SAVED_DEATH_TIME.clear();
    }

    // ------------------------------------------------------------------ executor lock

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (isLocalPlayerExecuting()) {
            var input = event.getInput();
            input.forwardImpulse = 0.0F;
            input.leftImpulse = 0.0F;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (isLocalPlayerExecuting()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onTurn(CalculatePlayerTurnEvent event) {
        if (isLocalPlayerExecuting()) {
            // Vanilla turns by (s * 0.6 + 0.2)^3 * 8: s = -1/3 freezes the view on the victim.
            event.setMouseSensitivity(-1.0D / 3.0D);
            event.setCinematicCameraEnabled(false);
        }
    }

    // ------------------------------------------------------------------ victim death pose

    /**
     * An executed victim falls with its authored clip, not vanilla's sideways
     * death tip: the death timer is hidden from the renderer while its clip
     * still poses it (the entity is still removed on the server's schedule).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity.deathTime <= 0) {
            return;
        }
        Exec exec = BY_VICTIM.get(entity.getId());
        if (exec != null && exec.variant != null && FinisherPoseHooks.hasVictimClip(entity)) {
            SAVED_DEATH_TIME.put(entity.getId(), entity.deathTime);
            entity.deathTime = 0;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        Integer saved = SAVED_DEATH_TIME.remove(event.getEntity().getId());
        if (saved != null) {
            event.getEntity().deathTime = saved;
        }
    }

    // ------------------------------------------------------------------ off-balance wobble

    /**
     * The visible "lost balance" read, for every enemy model: the whole body
     * sways around its feet, a quick stumble that settles into a heavier,
     * slower rock as the spell runs out. Pure render transform, same for all.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderWobblePre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Long until = BALANCE.get(entity.getId());
        if (until == null || !entity.isAlive() || BY_VICTIM.containsKey(entity.getId())) {
            return;
        }
        float partial = event.getPartialTick();
        float left = until - ticks - partial;
        if (left <= 0.0F) {
            return;
        }
        float age = com.hearthstead.finisher.FinishWindowTracker.OFF_BALANCE_TICKS - left;
        float fade = Math.min(1.0F, left / 10.0F) * Math.min(1.0F, age / 3.0F + 0.35F);
        float t = (ticks + partial + entity.getId() * 7) * 0.33F;
        float roll = (7.0F * Mth.sin(t * 1.3F) + 2.5F * Mth.sin(t * 3.1F + 0.7F)) * fade;
        float pitch = (4.0F + 3.0F * Mth.sin(t * 0.9F + 1.9F)) * fade;
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-entity.getVisualRotationYInDegrees()));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-pitch));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(entity.getVisualRotationYInDegrees()));
        WOBBLE_PUSHED.add(entity.getId());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderWobblePost(RenderLivingEvent.Post<?, ?> event) {
        if (WOBBLE_PUSHED.remove(event.getEntity().getId())) {
            event.getPoseStack().popPose();
        }
    }

    // ------------------------------------------------------------------ prompt

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LivingEntity target = promptTarget;
        if (target == null || mc.options.hideGui || mc.player == null) {
            return;
        }
        Component keyName = FinisherClientSetup.promptKeyName();
        // Shared HUD look (client/ui2/Ui2Hud): walnut plate, gold key, plain verb; the
        // finisher's red pulse lives on a thin left bar so it still reads as urgent.
        Component key = Component.literal("[").append(keyName).append("]");
        Component verb = Component.translatable(promptState == FinisherPayloads.Window.JOIN
            ? "hearthstead.finisher.verb_join" : "hearthstead.finisher.verb");
        GuiGraphics g = event.getGuiGraphics();
        int kw = mc.font.width(key);
        int tw = kw + 4 + mc.font.width(verb);
        int x = (g.guiWidth() - tw) / 2;
        int y = g.guiHeight() / 2 + 14;
        float pulse = 0.5F + 0.5F * Mth.sin((ticks + event.getPartialTick().getGameTimeDeltaPartialTick(false)) * 0.62F);
        com.hearthstead.client.ui2.Ui2Hud.plate(g, x - 6, y - 3, tw + 12, 14);
        g.fill(x - 6, y - 3, x - 5, y + 11,
            com.hearthstead.client.ui2.Ui2Hud.fade(com.hearthstead.client.ui2.Ui2Hud.WARN, 0.45F + 0.55F * pulse));
        g.drawString(mc.font, key, x, y, com.hearthstead.client.ui2.Ui2Hud.KEY, false);
        g.drawString(mc.font, verb, x + kw + 4, y, com.hearthstead.client.ui2.Ui2Hud.TEXT, false);
    }

    /** For GameTests / QA overlays: current prompt target id or -1. */
    public static int promptTargetId() {
        return promptTarget == null ? -1 : promptTarget.getId();
    }

    static boolean isRaider(Entity entity) {
        return entity instanceof RaiderEntity;
    }
}
