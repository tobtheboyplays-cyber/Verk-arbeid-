package com.hearthstead.client.command;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldTerrain;
import com.hearthstead.settlement.guard.FormationMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "What you look at is the order": resolves the crosshair into a field order
 * for one role (or all). Ground = line/position, enemy = charge/focus, wall
 * or tower = ranged climb it (melee hold its base), own feet = follow me.
 * Healers ignore enemies under the crosshair and take the ground behind them.
 */
public final class CommandAim {
    /** Looking this steeply down at your own feet (or open air below) means "follow me". */
    private static final float FEET_PITCH = 60.0F;
    private static final double FEET_RADIUS_SQR = 3.0D * 3.0D;

    public record Target(Group group, Kind kind, @Nullable BlockPos pos, int octant, int enemyId,
                         String enemyName, boolean high, @Nullable Component problem) {
        public boolean valid() { return problem == null; }
        /** Legacy name used by the HUD: the commanded group. */
        public Group arm() { return group; }
    }

    /** One preview dot. */
    public record Dot(BlockPos pos, boolean valid, Group role) {
    }

    public static Target resolve(Minecraft mc, Group group, float partialTick) {
        LocalPlayer player = mc.player;
        int octant = FormationMath.octant(player.getYRot());
        double range = FieldOrderRules.AIM_RANGE;
        HitResult blockHit = player.pick(range, partialTick, false);
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        Vec3 end = eye.add(look.scale(range));
        double maxSqr = blockHit.getType() == HitResult.Type.MISS ? range * range
            : eye.distanceToSqr(blockHit.getLocation());
        if (!group.support()) {
            AABB sweep = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, eye, end, sweep,
                CommandAim::enemy, maxSqr);
            if (entityHit != null) {
                Entity enemy = entityHit.getEntity();
                return new Target(group, Kind.ATTACK, enemy.blockPosition(), octant, enemy.getId(),
                    enemy.getDisplayName().getString(), false, null);
            }
        }
        BlockPos feet = player.blockPosition();
        boolean steep = player.getXRot() >= FEET_PITCH;
        if (steep && (blockHit.getType() == HitResult.Type.MISS
            || blockHit.getLocation().distanceToSqr(Vec3.atBottomCenterOf(feet)) <= FEET_RADIUS_SQR)) {
            return new Target(group, Kind.FOLLOW, feet, octant, -1, "", false, null);
        }
        if (!(blockHit instanceof BlockHitResult hit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return invalid(group, octant, "hearthstead.command.hud.no_aim");
        }
        FieldTerrain.Aim aim = FieldTerrain.resolve(mc.level, hit.getBlockPos(), hit.getDirection(), feet);
        if (aim == null) return invalid(group, octant, "hearthstead.command.hud.no_ground");
        if (aim.high()) {
            if (group == Group.ALL || group.ranged()) {
                return new Target(group, Kind.HIGH_GROUND, aim.stand(), octant, -1, "", true, null);
            }
            return new Target(group, Kind.LINE, aim.base(), octant, -1, "", true, null);
        }
        return new Target(group, Kind.LINE, aim.stand(), octant, -1, "", false, null);
    }

    private static Target invalid(Group group, int octant, String key) {
        return new Target(group, Kind.LINE, null, octant, -1, "", false, Component.translatable(key));
    }

    private static boolean enemy(Entity entity) {
        return entity instanceof Monster monster && monster.isAlive() && !monster.isSpectator();
    }

    /**
     * Preview dots, laid out exactly as the server will assign them: per role
     * in earshot, the same formation, depth layering and wall/base rules.
     */
    public static List<Dot> previewDots(Minecraft mc, Target target, int width) {
        List<Dot> dots = new ArrayList<>();
        if (!target.valid() || target.pos() == null) return dots;
        Map<Group, Integer> counts = new EnumMap<>(Group.class);
        for (SettlerEntity soldier : CommandClientState.inEarshot(target.group())) {
            Group role = CommandClientState.roleOf(soldier.getId());
            if (role != null) counts.merge(role, 1, Integer::sum);
        }
        if (counts.isEmpty()) counts.put(target.group() == Group.ALL ? Group.KNIGHTS : target.group(), 1);
        Map<Group, Integer> depths = FormationMath.layerDepths(counts);
        for (Map.Entry<Group, Integer> entry : counts.entrySet()) {
            Group role = entry.getKey();
            Kind kind = FieldOrderRules.kindFor(role, target.kind()).orElse(null);
            if (kind == null) continue;
            BlockPos center = target.pos();
            if (target.kind() == Kind.HIGH_GROUND && kind == Kind.LINE) {
                center = FieldTerrain.baseToward(mc.level, target.pos(), mc.player.blockPosition());
            }
            if (target.group() == Group.ALL && kind == Kind.LINE) {
                center = FormationMath.behind(center, target.octant(), depths.getOrDefault(role, 0));
            }
            int n = entry.getValue();
            int w = target.group() == Group.ALL ? FormationMath.defaultWidth(role, n) : width;
            List<FieldTerrain.PlacedSlot> placed = switch (kind) {
                case LINE -> FieldTerrain.placeLine(mc.level, center, target.octant(), n, w, role);
                case HIGH_GROUND -> FieldTerrain.placeHigh(mc.level, center, n);
                case FOLLOW -> FieldTerrain.placeEscort(mc.level, mc.player.blockPosition(), target.octant(), n,
                    role.ranged());
                default -> List.of();
            };
            for (FieldTerrain.PlacedSlot slot : placed) dots.add(new Dot(slot.pos(), slot.valid(), role));
        }
        return dots;
    }

    private CommandAim() {
    }
}
