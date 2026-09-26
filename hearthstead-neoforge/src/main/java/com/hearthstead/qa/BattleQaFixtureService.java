package com.hearthstead.qa;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Admin-authored combat preview. Never writes Journey or first-raid lifecycle state. */
public final class BattleQaFixtureService {
    private static final String KEY = "HearthsteadBattleQa";
    private static final int RADIUS = 44;
    private static final int GROUNDED_SITE_SEARCH_RADIUS = 256;
    private static final int GROUNDED_SITE_SEARCH_STEP = 16;
    private static final int GROUNDED_PLAINS_SEARCH_RADIUS = 2048;
    private static final int GROUNDED_PLAINS_HORIZONTAL_STEP = 32;
    private static final int GROUNDED_PLAINS_VERTICAL_STEP = 64;
    private BattleQaFixtureService() { }

    public enum Stage { INVALID, PREPARED, STARTED }
    public record Result(Stage stage, UUID sessionId, BlockPos origin,
                         BlockPos camera, String detail) {
        public boolean ok() { return stage != Stage.INVALID; }
    }

    public static Result prepare(ServerLevel level, ServerPlayer actor, BlockPos hint) {
        return prepare(level, actor, hint, false, false);
    }

    public static Result prepareGrounded(ServerLevel level, ServerPlayer actor, BlockPos hint) {
        return prepare(level, actor, hint, true, false);
    }

    public static Result prepareGroundedVillage(ServerLevel level, ServerPlayer actor, BlockPos hint) {
        return prepare(level, actor, hint, true, true);
    }

    private static Result prepare(ServerLevel level, ServerPlayer actor, BlockPos hint, boolean grounded,
                                  boolean needsVillage) {
        if (!context(level, actor) || hint == null) return invalid("invalid_context");
        if (actor.getPersistentData().contains(KEY)) {
            Result existing = status(level, actor);
            if (existing.ok()) visit(level, actor, existing.camera());
            return existing;
        }
        BlockPos center = grounded ? findGroundSite(level, hint, needsVillage) : new BlockPos(hint.getX() + 512,
            Math.max(200, hint.getY() + 32), hint.getZ());
        if (center == null) return invalid(grounded
            ? needsVillage ? "no_safe_dry_ground_village_site_near_plains_within_2048"
                : "no_safe_dry_ground_site_near_plains_within_2048"
            : "sky_site_missing");
        if (center.getY() + 8 >= level.getMaxBuildHeight()) return invalid("site_too_high");
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Settlement other : data.settlements.values()) {
            double dx = other.center.getX() - center.getX();
            double dz = other.center.getZ() - center.getZ();
            if (dx * dx + dz * dz < Math.pow(other.radius + RADIUS + 64, 2)) {
                return invalid("site_near_existing_settlement");
            }
        }
        // Load only this bounded site, then prove every edited cell is empty.
        for (int x = -RADIUS; x <= RADIUS + 15; x += 16) {
            for (int z = -RADIUS; z <= RADIUS + 15; z += 16) {
                BlockPos p = center.offset(Math.min(x, RADIUS), 0, Math.min(z, RADIUS));
                if (!level.getWorldBorder().isWithinBounds(p)) return invalid("outside_world_border");
                level.getChunkAt(p);
            }
        }
        if (!grounded) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-RADIUS, -1, -RADIUS),
                center.offset(RADIUS, 5, RADIUS))) {
            if (!level.getBlockState(p).isAir()) return invalid("site_not_empty");
        }
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                level.setBlockAndUpdate(center.offset(x, -1, z),
                    (x % 8 == 0 && z % 8 == 0 ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS).defaultBlockState());
                if (Math.abs(x) == RADIUS || Math.abs(z) == RADIUS) {
                    level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.GLASS.defaultBlockState());
                    level.setBlockAndUpdate(center.offset(x, 1, z), Blocks.GLASS.defaultBlockState());
                }
            }
        }
        } else {
            foundation(level, center, -1, 1, -1, 1);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos step = center.relative(direction, 2).below(2);
                if (level.getBlockState(step).isAir() || level.getBlockState(step).canBeReplaced())
                    level.setBlockAndUpdate(step, Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "BattleQA-" + actor.getUUID(), center);
        settlement.radius = 40;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        CompoundTag marker = new CompoundTag();
        marker.putUUID("Settlement", settlement.id);
        marker.putString("Dimension", level.dimension().location().toString());
        marker.putLong("Center", center.asLong());
        marker.putBoolean("Grounded", grounded);
        BlockPos camera = grounded ? new BlockPos(center.getX(),
            level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                center.getX(), center.getZ()-12), center.getZ()-12) : center.offset(0,0,-12);
        marker.putLong("Camera", camera.asLong());
        actor.getPersistentData().put(KEY, marker); // Partial failure is locked, never duplicated.
        level.setBlockAndUpdate(center, ModBlocks.HEARTH.get().defaultBlockState());
        if (!(level.getBlockEntity(center) instanceof HearthBlockEntity hearth)) return invalid("hearth_creation_failed");
        hearth.bindSettlement(settlement.id);
        Building barracks = workplace(settlement, BuildingType.BARRACKS, center.offset(-7, 0, 2));
        Building tower = workplace(settlement, BuildingType.WATCHTOWER, center.offset(0, 0, -5));
        if (grounded) {
            groundedRoom(level, barracks.anchor, false);
            groundedRoom(level, tower.anchor, true);
            hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 64));
        }
        // The ordinary building sweep requires the declaring plaque to exist.
        // Blank plaques retain these explicitly admin-authored bounds without
        // claiming that the fixture passed a player room survey.
        for (Building employer : List.of(barracks, tower)) {
            level.setBlockAndUpdate(employer.plaquePos.south(), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(employer.plaquePos, ModBlocks.PLAQUE.get().defaultBlockState());
        }
        BlockPos chestPos = tower.anchor.offset(0, 0, -2);
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(chestPos) instanceof Container chest)) return invalid("arrow_chest_failed");
        chest.setItem(0, new ItemStack(Items.ARROW, 64));
        chest.setItem(1, new ItemStack(Items.ARROW, 64));
        chest.setChanged();
        marker.putLong("ArrowChest", chestPos.asLong());
        for (int i = 0; i < 4; i++) {
            Building employer = i < 2 ? barracks : tower;
            BlockPos post = center.offset(i % 2 == 0 ? -3 : 3, 0, i < 2 ? 6 : -4);
            if (grounded && i < 2) post = new BlockPos(post.getX(),
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    post.getX(), post.getZ()), post.getZ());
            // The compact grounded tower is a closed lower room. Put its two
            // ranged posts on the roof deck so the ordinary target board has
            // a real line of sight to approaching raiders; the deck is the
            // existing oak-plank roof, not a combat teleport or target edit.
            if (grounded && i >= 2) post = post.above(4);
            SettlerEntity defender = ModEntities.SETTLER.get().create(level);
            if (defender == null) return invalid("defender_creation_failed");
            defender.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0, 0);
            defender.finalizeSpawn(level, level.getCurrentDifficultyAt(post), MobSpawnType.MOB_SUMMONED, null);
            defender.setSettlerName((i < 2 ? "Battle Guard " : "Battle Archer ") + (i % 2 + 1));
            defender.bindTo(settlement.id, center);
            defender.setPersistenceRequired();
            defender.setNoAi(true);
            if (!level.addFreshEntity(defender)) return invalid("defender_spawn_refused");
            settlement.putRecord(defender.getUUID(), defender.getSettlerName(), Profession.NONE);
            if (grounded) marker.putUUID("Defender" + i, defender.getUUID());
            if (!Employment.hire(level, settlement, employer, defender).ok()) return invalid("hire_refused");
            // Explicit fresh QA initialization, never a production hiring heal.
            if (grounded) defender.setHealth(defender.getMaxHealth());
            defender.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(i < 2 ? (grounded ? Items.WOODEN_SWORD : Items.IRON_SWORD) : Items.BOW));
            defender.setItemSlot(EquipmentSlot.HEAD, new ItemStack(grounded ? Items.LEATHER_HELMET : Items.IRON_HELMET));
            defender.setItemSlot(EquipmentSlot.CHEST, new ItemStack(grounded ? Items.LEATHER_CHESTPLATE : Items.IRON_CHESTPLATE));
            defender.setItemSlot(EquipmentSlot.LEGS, new ItemStack(grounded ? Items.LEATHER_LEGGINGS : Items.IRON_LEGGINGS));
            defender.setItemSlot(EquipmentSlot.FEET, new ItemStack(grounded ? Items.LEATHER_BOOTS : Items.IRON_BOOTS));
            var order = settlement.guardOrders.orderForMutation(settlement.id, defender.getUUID(),
                level.dimension().location()).orElseThrow();
            if (!order.issueStand(post, Direction.SOUTH, GuardOrder.DEFAULT_LEASH_RADIUS,
                    actor.getUUID(), employer.id, level.getGameTime())) return invalid("order_refused");
        }
        marker.putBoolean("Prepared", true);
        data.setDirty();
        visit(level, actor, camera);
        // Fill empty equipment slots only; never overwrite the player's possessions.
        if (actor.getMainHandItem().isEmpty()) actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        else give(actor, new ItemStack(Items.IRON_SWORD));
        if (actor.getOffhandItem().isEmpty()) actor.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        else give(actor, new ItemStack(Items.SHIELD));
        give(actor, new ItemStack(Items.COOKED_BEEF, 32));
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (actor.getItemBySlot(slot).isEmpty()) actor.setItemSlot(slot, new ItemStack(switch (slot) {
                case HEAD -> Items.IRON_HELMET;
                case CHEST -> Items.IRON_CHESTPLATE;
                case LEGS -> Items.IRON_LEGGINGS;
                default -> Items.IRON_BOOTS;
            }));
        }
        return status(level, actor);
    }

    public static Result start(ServerLevel level, ServerPlayer actor) {
        Result before = status(level, actor);
        if (!before.ok() || before.stage() == Stage.STARTED) return before;
        CompoundTag marker = actor.getPersistentData().getCompound(KEY);
        Settlement settlement = SettlementSavedData.get(level).settlements.get(before.sessionId());
        List<SettlerEntity> defenders = new ArrayList<>();
        List<UUID> defenderIds = new ArrayList<>();
        if (marker.getBoolean("Grounded")) {
            for (int i=0;i<4;i++) {
                if (!marker.hasUUID("Defender"+i)) return invalid("missing_original_defender_identity");
                UUID id=marker.getUUID("Defender"+i);
                if (defenderIds.contains(id)) return invalid("duplicate_original_defender_identity");
                defenderIds.add(id);
            }
        } else {
            for (Settlement.SettlerRecord record : settlement.settlers) defenderIds.add(record.entityId);
        }
        for (UUID defenderId : defenderIds) {
            if (!(level.getEntity(defenderId) instanceof SettlerEntity defender)
                    || !GuardAssignmentService.validate(level, settlement, defender, false).valid()
                    || !GuardAssignmentService.hasServiceableEquipment(defender)) {
                return invalid("defender_missing_or_not_ready");
            }
            defenders.add(defender);
        }
        if (defenders.size() != 4) return invalid("expected_four_defenders");
        visit(level, actor, before.camera());
        actor.setGameMode(GameType.SURVIVAL);
        actor.setHealth(actor.getMaxHealth());
        actor.getFoodData().setFoodLevel(20);
        RaidPlan planned = RaidDirector.planRaid(level, settlement, level.getDayTime() / 24000L);
        var band = RaidDirector.spawnBand(level, settlement,
            new RaidPlan(planned.captainId(), RaidObjective.BLOD, 0, planned.night()));
        if (band.isEmpty()) return invalid("band_spawn_failed");
        marker.putBoolean("Started", true);
        marker.putInt("Raiders", band.size());
        for (int i = 0; i < band.size(); i++) marker.putUUID("Raider" + i, band.get(i).getUUID());
        defenders.forEach(defender -> defender.setNoAi(false));
        SettlementSavedData.get(level).setDirty();
        return status(level, actor);
    }

    public static Result status(ServerLevel level, ServerPlayer actor) {
        if (!context(level, actor)) return invalid("invalid_context");
        CompoundTag marker = actor.getPersistentData().getCompound(KEY);
        if (!marker.hasUUID("Settlement")) return invalid("no_prepared_session");
        if (!marker.getString("Dimension").equals(level.dimension().location().toString())) return invalid("wrong_dimension");
        Settlement settlement = SettlementSavedData.get(level).settlements.get(marker.getUUID("Settlement"));
        if (settlement == null || !settlement.name.equals("BattleQA-" + actor.getUUID())) return invalid("fixture_identity_changed");
        if (!marker.getBoolean("Prepared")) return invalid("partial_fixture_requires_admin_review");
        long defenders;
        if (marker.getBoolean("Grounded")) {
            java.util.Set<UUID> ids=new java.util.HashSet<>();
            defenders=0;
            for(int i=0;i<4;i++) {
                if (!marker.hasUUID("Defender"+i)) return invalid("missing_original_defender_identity");
                UUID id=marker.getUUID("Defender"+i);
                if (!ids.add(id)) return invalid("duplicate_original_defender_identity");
                if(level.getEntity(id) instanceof SettlerEntity e && e.isAlive()) defenders++;
            }
        } else {
            defenders=settlement.settlers.stream().filter(r -> level.getEntity(r.entityId) instanceof SettlerEntity e && e.isAlive()).count();
        }
        int raiders = 0;
        for (int i = 0; i < marker.getInt("Raiders"); i++) {
            var entity = level.getEntity(marker.getUUID("Raider" + i));
            if (entity != null && entity.isAlive()) raiders++;
        }
        String archerEvidence = marker.getBoolean("Grounded")
            ? "; tower_arrows=" + arrowStock(level, marker)
                + "; archer_quiver=" + archerQuiver(level, marker)
            : "";
        return new Result(marker.getBoolean("Started") ? Stage.STARTED : Stage.PREPARED,
            settlement.id, settlement.center, marker.contains("Camera")
                ? BlockPos.of(marker.getLong("Camera")) : settlement.center.offset(0, 0, -12),
            "combat_only; terrain=" + (marker.getBoolean("Grounded") ? "grounded" : "sky")
                + "; center=" + settlement.center.toShortString()
                + "; defenders_loaded_alive=" + defenders + "; raiders_loaded_alive=" + raiders
                + archerEvidence
                + "; no_first_raid_progression_or_release_evidence");
    }

    /** Read-only combat-fixture evidence from the exact chest created above. */
    private static int arrowStock(ServerLevel level, CompoundTag marker) {
        if (!marker.contains("ArrowChest")
            || !(level.getBlockEntity(BlockPos.of(marker.getLong("ArrowChest")))
                instanceof Container chest)) return -1;
        int arrows = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(Items.ARROW)) arrows += stack.getCount();
        }
        return arrows;
    }

    /** Read-only sum for the two exact prepared Archers, never a combat credit. */
    private static int archerQuiver(ServerLevel level, CompoundTag marker) {
        int arrows = 0;
        for (int i = 2; i < 4; i++) {
            if (marker.hasUUID("Defender" + i)
                && level.getEntity(marker.getUUID("Defender" + i))
                    instanceof SettlerEntity archer) {
                arrows += archer.archerQuiverCount();
            }
        }
        return arrows;
    }

    /** Only the exact paused grounded QA session may receive additional village roles. */
    public static Settlement groundedSettlement(ServerLevel level, ServerPlayer actor) {
        Result current=status(level,actor);
        if(current.stage()!=Stage.PREPARED || !actor.getPersistentData().getCompound(KEY).getBoolean("Grounded")) return null;
        return SettlementSavedData.get(level).settlements.get(current.sessionId());
    }

    /**
     * Searches a finite 33 x 33 grid, nearest ring first. A grounded session
     * writes no blocks until its defense patch accepts untouched normal terrain.
     * Village mode additionally requires all civilian plots before writing.
     */
    private static BlockPos findGroundSite(ServerLevel level, BlockPos hint, boolean needsVillage) {
        BlockPos local = findGroundSiteInGrid(level, hint, needsVillage);
        if (local != null) return local;
        // ServerLevel#findClosestBiome3d is the mapped 1.21.1 locator behind
        // /locate biome. It samples the actual generator; it does not infer
        // a flat site or alter terrain.
        var nearestPlains = level.findClosestBiome3d(holder -> holder.is(Biomes.PLAINS), hint,
            GROUNDED_PLAINS_SEARCH_RADIUS, GROUNDED_PLAINS_HORIZONTAL_STEP,
            GROUNDED_PLAINS_VERTICAL_STEP);
        if (nearestPlains == null) return null;
        BlockPos biomeHint = new BlockPos(nearestPlains.getFirst().getX(), hint.getY(),
            nearestPlains.getFirst().getZ());
        // A shifted grid covers new terrain even when its center is inside
        // the first grid. Only an identical center repeats the same search.
        if (sameGridOrigin(hint, biomeHint)) return null;
        com.hearthstead.Hearthstead.LOGGER.info(
            "HSQA_GROUNDED_SEARCH phase=plains origin={} candidate={} radius={}",
            hint.toShortString(), biomeHint.toShortString(), GROUNDED_SITE_SEARCH_RADIUS);
        return findGroundSiteInGrid(level, biomeHint, needsVillage);
    }

    /** At most 1,089 candidate centers; this method performs no world writes. */
    private static BlockPos findGroundSiteInGrid(ServerLevel level, BlockPos hint, boolean needsVillage) {
        for (int radius = 0; radius <= GROUNDED_SITE_SEARCH_RADIUS;
                radius += GROUNDED_SITE_SEARCH_STEP) {
            if (radius == 0) {
                BlockPos found = qualifiedGroundSite(level, hint, needsVillage);
                if (found != null) return found;
                continue;
            }
            for (int x = -radius; x <= radius; x += GROUNDED_SITE_SEARCH_STEP) {
                BlockPos north = qualifiedGroundSite(level, hint.offset(x, 0, -radius), needsVillage);
                if (north != null) return north;
                BlockPos south = qualifiedGroundSite(level, hint.offset(x, 0, radius), needsVillage);
                if (south != null) return south;
            }
            for (int z = -radius + GROUNDED_SITE_SEARCH_STEP; z < radius;
                    z += GROUNDED_SITE_SEARCH_STEP) {
                BlockPos west = qualifiedGroundSite(level, hint.offset(-radius, 0, z), needsVillage);
                if (west != null) return west;
                BlockPos east = qualifiedGroundSite(level, hint.offset(radius, 0, z), needsVillage);
                if (east != null) return east;
            }
        }
        return null;
    }

    private static boolean sameGridOrigin(BlockPos origin, BlockPos candidate) {
        return candidate.getX() == origin.getX() && candidate.getZ() == origin.getZ();
    }

    private static BlockPos qualifiedGroundSite(ServerLevel level, BlockPos hint, boolean needsVillage) {
        BlockPos center = groundSiteAt(level, hint);
        return center != null && (!needsVillage || TavernClientQaFixture.groundedVillageFootprintAt(level, center))
            ? center : null;
    }

    static BlockPos groundSiteAt(ServerLevel level, BlockPos hint) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int[][] heights = new int[27][25];
        for (int x=-13; x<=13; x++) for (int z=-14; z<=10; z++) {
            int wx=hint.getX()+x, wz=hint.getZ()+z;
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(wx, hint.getY(), wz))) return null;
            level.getChunkAt(new BlockPos(wx,hint.getY(),wz));
            int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,wx,wz);
            if (y<=level.getMinBuildHeight()+1 || y+6>=level.getMaxBuildHeight()) return null;
            BlockPos feet=new BlockPos(wx,y,wz);
            var support=level.getBlockState(feet.below());
            if (!level.getFluidState(feet.below()).isEmpty() || !level.getFluidState(feet).isEmpty()
                || !support.isCollisionShapeFullBlock(level,feet.below())
                || level.getBlockEntity(feet.below()) != null) return null;
            for(int dy=0;dy<6;dy++) {
                BlockPos pos=feet.above(dy);
                var state=level.getBlockState(pos);
                if ((!state.isAir() && !state.canBeReplaced()) || level.getBlockEntity(pos)!=null) return null;
            }
            heights[x+13][z+14]=y;
            min=Math.min(min,y); max=Math.max(max,y);
            if(max-min>2) return null;
            if(x>-13 && Math.abs(y-heights[x+12][z+14])>1) return null;
            if(z>-14 && Math.abs(y-heights[x+13][z+13])>1) return null;
        }
        return new BlockPos(hint.getX(),max,hint.getZ());
    }

    private static void foundation(ServerLevel level, BlockPos origin,
                                   int minX,int maxX,int minZ,int maxZ) {
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            BlockPos floor=origin.offset(x,-1,z);
            for(int dy=0;dy<=2;dy++) {
                BlockPos pos=floor.below(dy);
                if(dy>0 && !level.getBlockState(pos).canBeReplaced() && !level.getBlockState(pos).isAir()) break;
                level.setBlockAndUpdate(pos,Blocks.COBBLESTONE.defaultBlockState());
            }
        }
    }

    /** Compact physical room; only its footprint and two entrance steps are authored. */
    static void groundedRoom(ServerLevel level, BlockPos origin, boolean tower) {
        foundation(level,origin,-4,4,-3,3);
        for(int x=-4;x<=4;x++) for(int z=-3;z<=3;z++) {
            level.setBlockAndUpdate(origin.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
            if(Math.abs(x)==4 || Math.abs(z)==3) {
                for(int y=0;y<3;y++) {
                    // Open arrow slits occupy actual head/arm height, not glass.
                    boolean opening = x==0 && z==3 && y<2
                        || tower && y>0 && (x==0 || z==0 || Math.abs(x)==3);
                    if(!opening) level.setBlockAndUpdate(origin.offset(x,y,z),
                        (Math.abs(x)==4 && Math.abs(z)==3 ? Blocks.OAK_LOG : Blocks.OAK_PLANKS).defaultBlockState());
                }
            }
        }
        BlockPos door=origin.offset(0,0,3);
        var state=Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.FACING,Direction.SOUTH);
        level.setBlockAndUpdate(door,state);
        level.setBlockAndUpdate(door.above(),state.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        for(int step=1;step<=2;step++) {
            BlockPos pos=door.offset(0,-step,step);
            if(level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
                level.setBlockAndUpdate(pos,Blocks.COBBLESTONE.defaultBlockState());
        }
        level.setBlockAndUpdate(origin.offset(2,2,0),Blocks.LANTERN.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true));
    }

    private static Building workplace(Settlement settlement, BuildingType type, BlockPos anchor) {
        // Deliberately authored fixture employment bounds, not a forged player room survey.
        Building building = new Building(UUID.randomUUID(), type, anchor, anchor,
            BoundingBox.fromCorners(anchor.offset(-4, 0, -3), anchor.offset(4, 3, 3)));
        building.valid = true;
        settlement.buildings.add(building);
        return building;
    }
    private static boolean context(ServerLevel level, ServerPlayer actor) {
        return level != null && actor != null && actor.serverLevel() == level
            && level.getServer().isSameThread() && actor.hasPermissions(2);
    }
    private static void visit(ServerLevel level, ServerPlayer actor, BlockPos camera) {
        actor.teleportTo(level, camera.getX() + 0.5, camera.getY(), camera.getZ() + 0.5, 0, 0);
        actor.fallDistance = 0;
    }
    private static void give(ServerPlayer actor, ItemStack stack) {
        if (!actor.getInventory().add(stack)) actor.drop(stack, false);
    }
    private static Result invalid(String detail) { return new Result(Stage.INVALID, null, null, null, detail); }
}
