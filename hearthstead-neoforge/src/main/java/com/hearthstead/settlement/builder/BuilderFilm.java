package com.hearthstead.settlement.builder;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * QA film setup (op only, never used by gameplay): a fresh settlement around
 * the caller with a Builder's Hut raised instantly from its blueprint (as if
 * hand-built), a hired Builder, the hut stocked with exactly the bill, and
 * two real orders queued -- the two-storey house and a palisade with a gate.
 * The Builder then does everything himself, on camera.
 */
public final class BuilderFilm {

    private BuilderFilm() {
    }

    public static int setup(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos p = BlockPos.containing(source.getPosition());
        // A clean, flat lot.
        for (int x = -28; x <= 28; x++) {
            for (int z = -28; z <= 28; z++) {
                level.setBlock(p.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(p.offset(x, -2, z), Blocks.DIRT.defaultBlockState(), 2);
                for (int y = 0; y <= 18; y++) {
                    level.setBlock(p.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old -> old.center != null && old.center.distSqr(p) < 96 * 96);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Bannerhold", p);
        settlement.radius = 40;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        level.setBlock(p, ModBlocks.HEARTH.get().defaultBlockState(), 3);
        if (level.getBlockEntity(p) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
            hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 64));
        }

        // The Builder's Hut, standing (the player's first hand-built step).
        Blueprint hutPrint = BlueprintLibrary.get(level.getServer(), "builders_hut_small");
        if (hutPrint == null) {
            source.sendFailure(Component.literal("builderfilm: builders_hut_small blueprint missing"));
            return 0;
        }
        BlockPos hutOrigin = p.offset(-20, 0, -6);
        for (Blueprint.Cell cell : hutPrint.cells()) {
            level.setBlock(hutOrigin.offset(cell.x(), cell.y(), cell.z()), cell.state(), 2);
        }
        int[] pp = hutPrint.meta().plaquePos();
        BlockPos plaquePos = hutOrigin.offset(pp[0], pp[1], pp[2]);
        Building hut = null;
        if (level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque) {
            plaque.insertPlan(level, PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),
                BuildingType.BUILDERS_HUT));
            hut = plaque.building(level);
        }
        if (hut == null || !hut.valid) {
            source.sendFailure(Component.literal("builderfilm: the hut did not register at " + plaquePos));
            return 0;
        }

        // Orders first, so the stock can be exactly their bill.
        Blueprint house = BlueprintLibrary.get(level.getServer(), "house_two_storey");
        if (house == null) {
            source.sendFailure(Component.literal("builderfilm: house_two_storey blueprint missing"));
            return 0;
        }
        BuildPlanner.Plan housePlan = BuildPlanner.planBlueprint(level, settlement, house, p.offset(4, 0, -4),
            0, false, null);
        BuildPlanner.Plan wallPlan = BuildPlanner.planLine(level, settlement, p.offset(-22, 0, 14),
            p.offset(-6, 0, 14), BuildPlanner.PALISADE, true, 0, null);
        if (housePlan.job() == null || wallPlan.job() == null) {
            source.sendFailure(Component.literal("builderfilm: plans refused: " + housePlan.validation().reasonKey()
                + " / " + wallPlan.validation().reasonKey()));
            return 0;
        }
        Map<Item, Integer> bill = new LinkedHashMap<>(BuilderMaterials.total(housePlan.job()));
        BuilderMaterials.total(wallPlan.job()).forEach((k, v) -> bill.merge(k, v, Integer::sum));
        bill.merge(Items.LADDER, 12, Integer::sum);
        List<Container> chests = BuilderStock.hutContainers(level, hut);
        int dropped = 0;
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                ItemStack rest = BuilderStock.insertAll(chests, new ItemStack(e.getKey(), n));
                dropped += rest.getCount();
                left -= n;
                if (!rest.isEmpty()) {
                    break;
                }
            }
        }
        BuildJobs.commit(level, settlement, housePlan.job());
        BuildJobs.commit(level, settlement, wallPlan.job());

        SettlerEntity builder = ModEntities.SETTLER.get().create(level);
        if (builder == null) {
            return 0;
        }
        BlockPos door = hutOrigin.offset(pp[0], 0, -2);
        builder.moveTo(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0, 0);
        builder.finalizeSpawn(level, level.getCurrentDifficultyAt(door), MobSpawnType.COMMAND, null);
        level.addFreshEntity(builder);
        builder.setSettlerName("Ada");
        builder.bindTo(settlement.id, settlement.center);
        settlement.putRecord(builder.getUUID(), "Ada", Profession.NONE);
        boolean hired = Employment.hire(level, settlement, hut, builder).ok();
        // Camera: above and behind the palisade, looking at hut, house and wall.
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            double cx = p.getX() - 8.5;
            double cy = p.getY() + 13.0;
            double cz = p.getZ() + 27.5;
            double dx = (p.getX() + 2.0) - cx;
            double dy = (p.getY() + 3.0) - (cy + 1.62);
            double dz = (p.getZ() - 1.0) - cz;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.teleportTo(level, cx, cy, cz, yaw, pitch);
        }
        int missing = dropped;
        UUID hutId = hut.id;
        source.sendSuccess(() -> Component.literal("builderfilm: hut " + hutId + " builder hired=" + hired
            + " house steps=" + housePlan.job().size() + " wall steps=" + wallPlan.job().size()
            + " stock-not-fitted=" + missing), true);
        return hired ? 1 : 0;
    }
}
