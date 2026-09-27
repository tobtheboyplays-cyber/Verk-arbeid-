package com.hearthstead.event.worldevent;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlerNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Spawning and settler helpers shared by the event handlers. */
public final class WorldEventActors {
    /**
     * A wandering-trader despawn delay that never runs out within a visit (vanilla
     * counts it down every tick; 0 would make a leashed trader llama vanish at once).
     */
    public static final int NEVER_DESPAWN = Integer.MAX_VALUE / 2;

    private WorldEventActors() {
    }

    /**
     * An unbound settler body used as a visitor (refugee, minstrel). Unbound
     * settlers run none of the trade/household goals, so the event's
     * {@link WorldEventSettlerGoal} role fully owns what they do.
     */
    @Nullable
    public static SettlerEntity spawnVisitor(ServerLevel level, Settlement settlement,
                                             WorldEventSavedData.Active active, BlockPos feet,
                                             String role, double scale) {
        SettlerEntity visitor = ModEntities.SETTLER.get().create(level);
        if (visitor == null) return null;
        Set<String> taken = new HashSet<>();
        for (Settlement.SettlerRecord record : settlement.settlers) taken.add(record.name);
        String name = SettlerNames.pickSettlerName(level.random, taken);
        visitor.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, level.random.nextFloat() * 360F, 0F);
        visitor.setSettlerName(name);
        visitor.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
        if (scale != 1.0D && visitor.getAttribute(Attributes.SCALE) != null) {
            visitor.getAttribute(Attributes.SCALE).setBaseValue(scale);
        }
        visitor.setPersistenceRequired();
        WorldEventDirector.tag(visitor, settlement, active, role);
        visitor.setLookCostume(com.hearthstead.entity.look.CharacterLooks.costumeForRole(role));
        visitor.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", name);
        if (!level.addFreshEntity(visitor)) {
            active.actors.remove(visitor.getUUID());
            return null;
        }
        return visitor;
    }

    /** The visitor's own name (without any event marker in the shown name). */
    public static String plainName(Entity entity) {
        String name = entity.getPersistentData().getCompound(WorldEventDirector.TAG).getString("Name");
        return name.isEmpty() ? entity.getName().getString() : name;
    }

    /**
     * QA U6: the entity's name is its NAME only ("Brute chief"), never its
     * state ("..., demands food"): the custom name feeds death messages
     * ("slain by Brute chief") and the name plate. The talk UI draws its own
     * "!" over a speaker who waits; {@code what} and {@code waiting} are kept
     * for callers but no longer written into the name.
     */
    public static void markWaiting(Entity entity, Component what, boolean waiting) {
        String name = plainName(entity);
        if (!name.equals(entity.getCustomName() == null ? "" : entity.getCustomName().getString())) {
            entity.setCustomName(Component.literal(name));
        }
        entity.setCustomNameVisible(true);
    }

    public static List<SettlerEntity> members(ServerLevel level, Settlement settlement, Predicate<SettlerEntity> filter) {
        List<SettlerEntity> out = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.isAlive() && !settler.isTraveler() && filter.test(settler)) out.add(settler);
        }
        return out;
    }

    @Nullable
    public static SettlerEntity nearest(List<SettlerEntity> settlers, Entity to, double maxDistance) {
        SettlerEntity best = null;
        double bestDistance = maxDistance * maxDistance;
        for (SettlerEntity settler : settlers) {
            double d = settler.distanceToSqr(to);
            if (d <= bestDistance) { best = settler; bestDistance = d; }
        }
        return best;
    }

    @Nullable
    public static Building tavern(ServerLevel level, Settlement settlement) {
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == BuildingType.TAVERN && building.bounds != null
                && building.anchor != null && level.hasChunkAt(building.anchor)) {
                return building;
            }
        }
        return null;
    }

    /** Safe standing cells inside a building's bounds (bounded scan), nearest the anchor first. */
    public static List<BlockPos> standingSpots(ServerLevel level, Building building, int max) {
        List<BlockPos> out = new ArrayList<>();
        var box = building.bounds;
        int checked = 0;
        for (int y = box.minY(); y <= box.maxY() && out.size() < max; y++) {
            for (int x = box.minX(); x <= box.maxX() && out.size() < max; x++) {
                for (int z = box.minZ(); z <= box.maxZ() && out.size() < max; z++) {
                    if (++checked > 4096) return out;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (WorldEventCreatures.safeFeet(level, pos, 0.6F, 1.95F)
                        && level.getEntitiesOfClass(Entity.class, new AABB(pos)).isEmpty()) {
                        out.add(pos);
                    }
                }
            }
        }
        BlockPos anchor = building.anchor;
        out.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(anchor)));
        return out;
    }

    /**
     * A loaded Hunter of the settlement looses one real arrow at {@code prey}
     * (owner-attributed, so a Hunter kill is recognised). Only with a bow in
     * hand, line of sight and within range; returns whether a shot was made.
     */
    public static boolean hunterShot(ServerLevel level, Settlement settlement, Entity prey, int maxShots,
                                     WorldEventSavedData.Active active) {
        if (active.state.getInt("HunterShots") >= maxShots || !prey.isAlive()) return false;
        List<SettlerEntity> hunters = members(level, settlement, s -> s.getProfession() == Profession.HUNTER
            && !s.isSleeping() && s.getTarget() == null);
        SettlerEntity hunter = nearest(hunters, prey, 28.0D);
        if (hunter == null || !hunter.hasLineOfSight(prey)) return false;
        ItemStack bow = hunter.getMainHandItem().is(Items.BOW) ? hunter.getMainHandItem()
            : hunter.getOffhandItem().is(Items.BOW) ? hunter.getOffhandItem() : ItemStack.EMPTY;
        if (bow.isEmpty()) return false;
        Arrow arrow = new Arrow(level, hunter, new ItemStack(Items.ARROW), bow);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = prey.getX() - hunter.getX();
        double dy = prey.getY(0.4D) - arrow.getY();
        double dz = prey.getZ() - hunter.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * 0.18D, dz, 1.7F, 4.0F);
        hunter.getLookControl().setLookAt(prey, 30F, 30F);
        hunter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.playSound(null, hunter.blockPosition(), com.hearthstead.registry.ModSounds.WORK_BOW_LOOSE.get(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.addFreshEntity(arrow);
        active.state.putInt("HunterShots", active.state.getInt("HunterShots") + 1);
        return true;
    }

    /** First loaded actor carrying {@code role}, or null. */
    @Nullable
    public static Entity actor(ServerLevel level, WorldEventSavedData.Active active, String role) {
        for (UUID id : active.actors) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive() && role.equals(WorldEventDirector.tagRole(entity))) return entity;
        }
        return null;
    }

    public static List<Entity> actors(ServerLevel level, WorldEventSavedData.Active active) {
        List<Entity> out = new ArrayList<>();
        for (UUID id : active.actors) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) out.add(entity);
        }
        return out;
    }

    // ------------------------------------------- dead vs. merely unloaded --

    /** How long (eligible ticks) an actor may stay unloaded before it counts as gone. */
    public static final int ABSENT_GRACE_TICKS = 2_400;

    public enum Presence { PRESENT, ABSENT, DEAD, GONE }

    /** Called from the director's death hook: a confirmed death, with the actor's role. */
    static void markDead(WorldEventSavedData.Active active, Entity entity) {
        net.minecraft.nbt.ListTag dead = active.state.getList("Dead", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.CompoundTag row = new net.minecraft.nbt.CompoundTag();
        row.putUUID("Id", entity.getUUID());
        row.putString("Role", WorldEventDirector.tagRole(entity));
        dead.add(row);
        active.state.put("Dead", dead);
    }

    static boolean isDead(WorldEventSavedData.Active active, UUID id) {
        for (net.minecraft.nbt.Tag raw : active.state.getList("Dead", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            if (((net.minecraft.nbt.CompoundTag) raw).getUUID("Id").equals(id)) return true;
        }
        return false;
    }

    static boolean roleDied(WorldEventSavedData.Active active, String role) {
        for (net.minecraft.nbt.Tag raw : active.state.getList("Dead", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            if (role.equals(((net.minecraft.nbt.CompoundTag) raw).getString("Role"))) return true;
        }
        return false;
    }

    /** Role of an actor as recorded when it was tagged (known even while it is unloaded). */
    static String recordedRole(WorldEventSavedData.Active active, UUID id) {
        return active.state.getCompound("Roles").getString(id.toString());
    }

    /**
     * Whether the actor(s) with {@code role} are here, merely unloaded (an
     * unloaded chunk, or the Banner chunk loading first after a restart:
     * the caller must pause, not end), confirmed dead, or away past the grace.
     */
    public static Presence presence(ServerLevel level, WorldEventSavedData.Active active, String role) {
        return presence(level, active, id -> role.equals(recordedRole(active, id)));
    }

    /** Presence of the whole cast. */
    public static Presence presence(ServerLevel level, WorldEventSavedData.Active active) {
        return presence(level, active, id -> true);
    }

    private static Presence presence(ServerLevel level, WorldEventSavedData.Active active,
                                     java.util.function.Predicate<UUID> which) {
        boolean unloaded = false, died = false;
        for (UUID id : active.actors) {
            if (!which.test(id)) continue;
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                active.state.remove("AbsentSince");
                return Presence.PRESENT;
            }
            if (isDead(active, id)) died = true;
            else if (entity == null) unloaded = true;
        }
        if (unloaded) return absentClock(active);
        active.state.remove("AbsentSince");
        return died ? Presence.DEAD : Presence.GONE;
    }

    private static Presence absentClock(WorldEventSavedData.Active active) {
        if (!active.state.contains("AbsentSince")) active.state.putInt("AbsentSince", active.eligibleTicks);
        return active.eligibleTicks - active.state.getInt("AbsentSince") >= ABSENT_GRACE_TICKS
            ? Presence.GONE : Presence.ABSENT;
    }

    public static boolean isMartial(SettlerEntity settler) {
        return settler.getProfession().martial();
    }

    public static UUID stableId(String kind, UUID settlementId) {
        return UUID.nameUUIDFromBytes((kind + ":" + settlementId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
