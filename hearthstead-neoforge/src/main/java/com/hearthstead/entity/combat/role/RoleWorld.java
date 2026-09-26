package com.hearthstead.entity.combat.role;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.revive.ReviveService;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side world state of the battle roles (plan/BATTLE-ROLES.md): the
 * heal-over-time ledger, active wards, frost runes, the role damage rules
 * (spear flank, longsword vs arrows), role attribute modifiers and the
 * Healer's revive hook. Runtime-only by design: a reload simply ends a
 * bandage, a ward or a frost rune early, never grants anything twice.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class RoleWorld {
    public static final String GUARD_BROKEN_KEY = "HearthsteadGuardBrokenUntil";
    static final ResourceLocation WARD_ID = Hearthstead.id("rune_ward");
    static final ResourceLocation FRAILTY_ID = Hearthstead.id("role_frailty");
    static final ResourceLocation ROLE_SPEED_ID = Hearthstead.id("role_speed");
    /** Rune Mage max-health penalty: fragile by design. */
    public static final double MAGE_HEALTH_PENALTY = -6.0D;
    public static final double HEALER_SPEED_BONUS = 0.05D;

    private record Ward(ResourceKey<Level> dim, float granted, float totalAtGrant, long expires) {
    }

    /** A live frost rune. Ages by its own counter (never the global clock) and
     *  keeps its settlement by reference, so it cannot miss its first pulse. */
    public static final class FrostZone {
        final ResourceKey<Level> dim;
        final Vec3 centre;
        final int lifetime;
        final Settlement settlement;
        int age;

        FrostZone(ResourceKey<Level> dim, Vec3 centre, int lifetime, Settlement settlement) {
            this.dim = dim;
            this.centre = centre;
            this.lifetime = lifetime;
            this.settlement = settlement;
        }

        public Vec3 centre() {
            return centre;
        }
    }

    private static final class State {
        final HealLedger heals = new HealLedger();
        final Map<UUID, ResourceKey<Level>> healDims = new HashMap<>();
        final Map<UUID, Ward> wards = new HashMap<>();
        final List<FrostZone> frost = new ArrayList<>();
        final Map<UUID, Long> braceBrokenUntil = new HashMap<>();
        final Map<UUID, Long> lastHurt = new HashMap<>();
        final Map<UUID, UUID> lastAttacker = new HashMap<>();
        long wardsGranted;
        long frostSlows;
    }

    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static boolean rescuerRegistered;

    private RoleWorld() {
    }

    private static State state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, s -> new State());
    }

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    // ================================================================ heal

    /** Starts a bandage/herb heal on {@code patient}; false if not worth a supply. */
    public static boolean startHeal(ServerLevel level, LivingEntity patient, float total) {
        if (patient == null || !patient.isAlive() || patient.getHealth() >= patient.getMaxHealth()) {
            return false;
        }
        State st = state(level.getServer());
        if (!st.heals.start(patient.getUUID(), total, level.getGameTime())) {
            return false;
        }
        st.healDims.put(patient.getUUID(), level.dimension());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY(1.0D),
            patient.getZ(), 5, 0.3D, 0.3D, 0.3D, 0.0D);
        return true;
    }

    public static boolean healing(ServerLevel level, LivingEntity patient) {
        return state(level.getServer()).heals.healing(patient.getUUID());
    }

    public static float healRemaining(ServerLevel level, LivingEntity patient) {
        return state(level.getServer()).heals.remaining(patient.getUUID());
    }

    // ================================================================ ward

    /** Wards one ally; a second ward refreshes rather than stacks. */
    public static boolean applyWard(ServerLevel level, LivingEntity ally, float amount, int ticks) {
        AttributeInstance max = ally.getAttribute(Attributes.MAX_ABSORPTION);
        if (max == null || !ally.isAlive()) {
            return false;
        }
        State st = state(level.getServer());
        if (st.wards.containsKey(ally.getUUID())) {
            expireWard(ally, st.wards.remove(ally.getUUID()));
        }
        if (!max.hasModifier(WARD_ID)) {
            max.addTransientModifier(new AttributeModifier(WARD_ID, amount,
                AttributeModifier.Operation.ADD_VALUE));
        }
        float before = ally.getAbsorptionAmount();
        ally.setAbsorptionAmount(before + amount);
        float total = ally.getAbsorptionAmount();
        float granted = Math.max(0.0F, total - before);
        st.wards.put(ally.getUUID(), new Ward(level.dimension(), granted, total,
            level.getGameTime() + ticks));
        st.wardsGranted++;
        level.sendParticles(ParticleTypes.ENCHANT, ally.getX(), ally.getY(0.6D), ally.getZ(),
            24, 0.5D, 0.6D, 0.5D, 0.4D);
        return true;
    }

    public static boolean warded(ServerLevel level, LivingEntity ally) {
        return state(level.getServer()).wards.containsKey(ally.getUUID());
    }

    public static long wardsGranted(MinecraftServer server) {
        return state(server).wardsGranted;
    }

    private static void expireWard(LivingEntity ally, Ward ward) {
        if (ally != null) {
            float left = RuneSpell.wardLeftAtExpiry(ward.granted(), ward.totalAtGrant(),
                ally.getAbsorptionAmount());
            if (left > 0.0F) {
                ally.setAbsorptionAmount(ally.getAbsorptionAmount() - left);
            }
            AttributeInstance max = ally.getAttribute(Attributes.MAX_ABSORPTION);
            if (max != null) {
                max.removeModifier(WARD_ID);
            }
        }
    }

    // =============================================================== frost

    public static void addFrost(ServerLevel level, Vec3 centre, int lifetime, Settlement settlement) {
        FrostZone zone = new FrostZone(level.dimension(), centre, lifetime, settlement);
        State st = state(level.getServer());
        st.frost.add(zone);
        chill(level, zone, st);   // the rune bites the moment it is drawn
    }

    private static void chill(ServerLevel level, FrostZone z, State st) {
        AABB box = new AABB(z.centre, z.centre).inflate(RuneSpell.FROST_RADIUS, 2.0D,
            RuneSpell.FROST_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double dx = e.getX() - z.centre.x;
            double dz = e.getZ() - z.centre.z;
            if (dx * dx + dz * dz <= RuneSpell.FROST_RADIUS * RuneSpell.FROST_RADIUS
                && RoleCombat.hostileTo(z.settlement, e)) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    RuneSpell.FROST_SLOW_TICKS, RuneSpell.FROST_SLOW_AMPLIFIER, false, true));
                st.frostSlows++;
            }
        }
    }

    public static List<FrostZone> frostZones(MinecraftServer server) {
        return List.copyOf(state(server).frost);
    }

    public static long frostSlows(MinecraftServer server) {
        return state(server).frostSlows;
    }

    // ============================================================ damage

    /** Flank hits knock a spearman out of brace until this tick. */
    public static long braceBrokenUntil(ServerLevel level, UUID spearman) {
        return state(level.getServer()).braceBrokenUntil.getOrDefault(spearman, Long.MIN_VALUE);
    }

    public static long lastHurt(ServerLevel level, UUID settler) {
        return state(level.getServer()).lastHurt.getOrDefault(settler, Long.MIN_VALUE);
    }

    @Nullable
    public static UUID lastAttacker(ServerLevel level, UUID settler) {
        return state(level.getServer()).lastAttacker.get(settler);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof SettlerEntity victim)
            || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        Profession profession = victim.getProfession();
        if (!profession.battlefield() || !RoleCombat.enabled()) {
            return;
        }
        State st = state(level.getServer());
        long now = level.getGameTime();
        st.lastHurt.put(victim.getUUID(), now);
        Entity attacker = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (attacker != null) {
            st.lastAttacker.put(victim.getUUID(), attacker.getUUID());
        }
        if (profession == Profession.SPEARMAN) {
            Entity from = direct != null ? direct : attacker;
            if (from != null && !event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
                float bearing = RoleCombatRules.yawToward(victim.getX(), victim.getZ(),
                    from.getX(), from.getZ());
                if (RoleCombatRules.isFlankHit(victim.yBodyRot, bearing)) {
                    event.setAmount(event.getAmount() * RoleCombatRules.SPEAR_FLANK_DAMAGE_MULTIPLIER);
                    st.braceBrokenUntil.put(victim.getUUID(),
                        now + RoleCombatRules.FLANK_BRACE_BREAK_TICKS);
                }
            }
        } else if (profession == Profession.LONGSWORDSMAN
            && event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            event.setAmount(event.getAmount() * RoleCombatRules.LONGSWORD_PROJECTILE_DAMAGE_MULTIPLIER);
        }
    }

    /**
     * A fallen Healer's bandages or Rune Mage's rune stones are real items
     * (INV-3): they drop where the settler fell instead of vanishing with the
     * offhand (vanilla's hand-drop chance is ~8.5% and needs a player kill).
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof SettlerEntity settler)
            || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Profession p = settler.getProfession();
        if (p != Profession.HEALER && p != Profession.RUNE_MAGE) {
            return;
        }
        net.minecraft.world.item.ItemStack off = settler.getOffhandItem();
        boolean supply = com.hearthstead.item.role.BandageItem.supplyHeal(off) > 0.0F
            || off.is(com.hearthstead.registry.RoleItems.RUNE_STONE.get());
        if (off.isEmpty() || !supply) {
            return;
        }
        // Hand the supply to the corpse's own durable terminal transfer
        // (SettlerEntity.transferTerminalCargo, run right after this event and
        // retried by tickDeath until the drop is accepted): the source is only
        // cleared once the deferred-drop ledger owns the stack (BH-16 follow-up).
        settler.getPersistentData().putBoolean(
            com.hearthstead.entity.ai.GroundCollectionSession.PERSISTENT_OFFHAND_OWNERSHIP_TAG, true);
    }

    // ======================================================= attributes

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof SettlerEntity settler
            && settler.level() instanceof ServerLevel
            && settler.tickCount % 20 == 7) {
            syncRoleAttributes(settler);
        }
    }

    /** Idempotent: the role's modifiers exactly, nobody else's. */
    public static void syncRoleAttributes(SettlerEntity settler) {
        Profession p = RoleCombat.enabled() ? settler.getProfession() : Profession.NONE;
        setModifier(settler.getAttribute(Attributes.MAX_HEALTH), FRAILTY_ID,
            p == Profession.RUNE_MAGE ? MAGE_HEALTH_PENALTY : 0.0D,
            AttributeModifier.Operation.ADD_VALUE);
        double speed = p == Profession.LONGSWORDSMAN ? RoleCombatRules.LONGSWORD_SPEED_PENALTY
            : p == Profession.HEALER ? HEALER_SPEED_BONUS : 0.0D;
        setModifier(settler.getAttribute(Attributes.MOVEMENT_SPEED), ROLE_SPEED_ID, speed,
            AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        if (settler.getHealth() > settler.getMaxHealth()) {
            settler.setHealth(settler.getMaxHealth());
        }
    }

    private static void setModifier(@Nullable AttributeInstance attr, ResourceLocation id,
                                    double amount, AttributeModifier.Operation op) {
        if (attr == null) {
            return;
        }
        AttributeModifier current = attr.getModifier(id);
        if (amount == 0.0D) {
            if (current != null) {
                attr.removeModifier(id);
            }
            return;
        }
        if (current == null || current.amount() != amount || current.operation() != op) {
            attr.removeModifier(id);
            attr.addTransientModifier(new AttributeModifier(id, amount, op));
        }
    }

    // ============================================================= tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        State st = STATES.get(server);
        if (st == null) {
            return;
        }
        long now = now(server);
        // Heal-over-time pulses.
        for (HealLedger.Pulse pulse : st.heals.due(now)) {
            LivingEntity patient = find(server, st.healDims.get(pulse.patient()), pulse.patient());
            if (patient == null || !patient.isAlive()) {
                st.heals.cancel(pulse.patient());
                st.healDims.remove(pulse.patient());
                continue;
            }
            patient.heal(pulse.amount());
            if (patient.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY(1.1D),
                    patient.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
            }
        }
        st.healDims.keySet().removeIf(id -> !st.heals.healing(id));
        // Ward expiry and shimmer.
        Iterator<Map.Entry<UUID, Ward>> wards = st.wards.entrySet().iterator();
        while (wards.hasNext()) {
            Map.Entry<UUID, Ward> e = wards.next();
            LivingEntity ally = find(server, e.getValue().dim(), e.getKey());
            if (ally == null || !ally.isAlive() || now >= e.getValue().expires()) {
                expireWard(ally, e.getValue());
                wards.remove();
            } else if (now % 10 == 0 && ally.level() instanceof ServerLevel level) {
                ringParticles(level, ally.position().add(0, 0.1D, 0), 0.9D,
                    ParticleTypes.ENCHANT, 8);
            }
        }
        // Frost runes: Slowness II to enemies inside, refreshed each second.
        Iterator<FrostZone> zones = st.frost.iterator();
        while (zones.hasNext()) {
            FrostZone z = zones.next();
            ServerLevel level = server.getLevel(z.dim);
            z.age++;
            if (level == null || z.age > z.lifetime) {
                zones.remove();
                continue;
            }
            if (z.age % 10 == 0) {
                ringParticles(level, z.centre, RuneSpell.FROST_RADIUS, ParticleTypes.SNOWFLAKE, 16);
            }
            if (z.age % 20 == 0) {
                chill(level, z, st);
            }
        }
        // Housekeeping: forget stale damage marks.
        if (now % 200 == 0) {
            st.lastHurt.values().removeIf(t -> now - t > 1200);
            st.braceBrokenUntil.values().removeIf(t -> t < now);
            st.lastAttacker.keySet().removeIf(id -> !st.lastHurt.containsKey(id));
        }
    }

    public static void ringParticles(ServerLevel level, Vec3 centre, double radius,
                                     net.minecraft.core.particles.ParticleOptions particle, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2.0D * i / points;
            level.sendParticles(particle, centre.x + Math.cos(a) * radius, centre.y + 0.1D,
                centre.z + Math.sin(a) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    @Nullable
    private static LivingEntity find(MinecraftServer server, @Nullable ResourceKey<Level> dim, UUID id) {
        // Players change dimension: always find them by the player list.
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            return player;
        }
        if (dim != null) {
            ServerLevel level = server.getLevel(dim);
            if (level != null && level.getEntity(id) instanceof LivingEntity living) {
                return living;
            }
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    // ========================================================= lifecycle

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!rescuerRegistered) {
            rescuerRegistered = true;
            ReviveService.registerRescuer(new HealerRescuer());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().getServer() == null) {
            return;
        }
        State st = STATES.get(event.getEntity().getServer());
        if (st != null) {
            Ward ward = st.wards.remove(event.getEntity().getUUID());
            if (ward != null) {
                expireWard(event.getEntity(), ward);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        State st = STATES.remove(event.getServer());
        if (st == null) {
            return;
        }
        for (Map.Entry<UUID, Ward> e : st.wards.entrySet()) {
            expireWard(find(event.getServer(), e.getValue().dim(), e.getKey()), e.getValue());
        }
    }

    /** GameTest seam: forget all runtime state for this server. */
    public static void resetForTests(MinecraftServer server) {
        STATES.remove(server);
    }
}
