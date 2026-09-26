package com.hearthstead.qa;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.*;
import java.util.ArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Explicit fresh-world construction assistance; never manufactures a traveler or Coins. */
public final class EarlyCoinsRecruitQa {
    private static final String KEY = "HearthsteadEarlyCoinsRecruitQa";
    private EarlyCoinsRecruitQa() {}

    public static int execute(CommandSourceStack source, String action) {
        try {
            var player = source.getPlayerOrException();
            ServerLevel level = source.getLevel();
            BlockPos pos = BlockPos.containing(source.getPosition());
            require(level.getBlockEntity(pos) instanceof HearthBlockEntity, "bound_hearth_required");
            HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(pos);
            Settlement village = hearth.getSettlementId() == null ? null
                : SettlementManager.byId(level, hearth.getSettlementId());
            require(village != null && village.center.equals(pos) && Mayor.find(level, village) != null,
                "exact_living_settlement_required");
            CompoundTag bookmark = player.getPersistentData().getCompound(KEY);
            boolean unstaffed = action.equals("prepare_unstaffed");
            if (action.equals("prepare") || unstaffed) {
                require(bookmark.isEmpty() && village.population() == 4 && village.travelerId == null,
                    "fresh_four_founders_required");
                // Seed-site land candidates, each rechecked against the actual current world.
                int[][] offsets = {{0,18,8}, {-1,-24,5}, {-35,-3,5}, {3,35,5}};
                var plots = new ArrayList<BlockPos>();
                for (int[] offset : offsets) {
                    BlockPos plot = constructionPlot(level, pos, village.radius,
                        pos.offset(offset[0],0,offset[1]), offset[2]);
                    require(plot != null && Math.abs(plot.getY()+1-pos.getY()) <= 2,
                        "unsuitable_real_terrain_plot_" + plots.size());
                    plots.add(plot);
                }
                SettlerEntity host = null;
                if (!unstaffed) {
                    host = SettlementManager.loadedMembers(level, village).stream()
                        .filter(actor -> actor.getProfession() == Profession.NONE
                            && !actor.getUUID().equals(village.mayorId)).findFirst().orElse(null);
                    require(host != null, "existing_unemployed_founder_required");
                }
                for (int slot=1; slot<=8; slot++) require(hearth.getInventory().getStackInSlot(slot).isEmpty(),
                    "food_setup_slots_not_empty");
                bookmark.putUUID("Settlement", village.id);
                bookmark.putBoolean("Attempted", true);
                player.getPersistentData().put(KEY, bookmark);
                for (int i=0; i<plots.size(); i++)
                    constructionFoundation(level, plots.get(i), offsets[i][2]);
                Building tavern = compactTavern(level, village, plots.getFirst());
                for (int i=1; i<plots.size(); i++)
                    RaidQaFixtureService.prepareClientRoom(level, village, plots.get(i), BuildingType.HOUSE);
                if (unstaffed) require(tavern.workers.isEmpty(), "new_tavern_must_start_unstaffed");
                else require(Employment.hire(level, village, tavern, host).ok(), "real_host_hire_refused");
                for (int slot=1; slot<=8; slot++)
                    hearth.getInventory().setStackInSlot(slot, new ItemStack(Items.BREAD, 64));
                hearth.setChanged();
                require(village.population() == 4 && village.capacity() >= 5,
                    "same_four_founders_and_real_capacity_required");
                bookmark.putBoolean("Prepared", true);
                player.getPersistentData().put(KEY, bookmark);
                source.sendSuccess(() -> Component.literal("HSQA_EC_RECRUIT_PREPARED settlement=" + village.id
                    + " population=4 capacity=" + village.capacity() + " assistance=buildings_and_food_only"
                    + (unstaffed ? "_unstaffed" : "")), true);
                return 1;
            }
            require(bookmark.getBoolean("Prepared") && bookmark.hasUUID("Settlement")
                && village.id.equals(bookmark.getUUID("Settlement")), "prepared_same_settlement_required");
            var transaction = village.recruitment;
            if (action.equals("arm")) {
                require(!bookmark.contains("Transaction") && village.population() == 4
                    && transaction.status() == RecruitmentTransaction.Status.WAITING_ADMISSION
                    && transaction.quote() != null && transaction.quote().version() == 2
                    && transaction.quote().transactionId().equals(transaction.transactionId())
                    && transaction.quote().travelerId().equals(transaction.travelerId())
                    && transaction.arrivedTick() >= transaction.spawnedTick(), "actual_v2_arrival_required");
                require(level.getEntity(transaction.travelerId()) instanceof SettlerEntity guest
                    && guest.isAlive() && guest.isTraveler() && !guest.isBound(), "same_unowned_guest_required");
                require(RecruitmentPolicy.assess(level,village,RecruitmentPolicy.Stage.WAITING_ADMISSION,player).eligible(),
                    "real_bed_food_and_payment_required");
                bookmark.putUUID("Transaction",transaction.transactionId());
                bookmark.putUUID("Traveler",transaction.travelerId());
                bookmark.putInt("Price",transaction.quote().coins());
                bookmark.putInt("BeforeCoins",coins(level,village,hearth,player));
                player.getPersistentData().put(KEY,bookmark);
                source.sendSuccess(() -> Component.literal("HSQA_EC_PAYMENT_ARMED settlement=" + village.id
                    + " transaction=" + transaction.transactionId() + " traveler=" + transaction.travelerId()
                    + " price=" + transaction.quote().coins()),true);
                return 1;
            }
            require(action.equals("paid") && bookmark.hasUUID("Transaction"), "unknown_or_unarmed_action");
            require(transaction.status() == RecruitmentTransaction.Status.ADMITTED
                && bookmark.getUUID("Transaction").equals(transaction.transactionId())
                && village.population() == 5 && village.record(bookmark.getUUID("Traveler")) != null
                && level.getEntity(bookmark.getUUID("Traveler")) instanceof SettlerEntity joined
                && !joined.isTraveler() && village.id.equals(joined.getSettlementId()), "same_guest_must_join_once");
            var receipt = transaction.admissionReceipt();
            require(receipt != null && receipt.valid() && receipt.playerId().equals(player.getUUID())
                && receipt.removedItemCount() == bookmark.getInt("Price")
                && bookmark.getInt("BeforeCoins") - coins(level,village,hearth,player) == bookmark.getInt("Price")
                && ReadyFood.count(hearth.getInventory()) >= RecruitmentPolicy.requiredReserve(5),
                "exact_physical_payment_receipt_and_reserve_required");
            source.sendSuccess(() -> Component.literal("HSQA_EC_PAID_RECRUITMENT settlement=" + village.id
                + " transaction=" + transaction.transactionId() + " population=5 coinsPaid=" + bookmark.getInt("Price")),true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_EC_RECRUIT_FAIL reason=" + failure.getMessage()));
            return 0;
        }
    }

    /** Preflight the complete local edit volume before any bookmark or block mutation. */
    private static BlockPos constructionPlot(ServerLevel level, BlockPos hearth, int radius,
                                             BlockPos hint, int size) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = -2; x <= size; x++) for (int z = -3; z <= size; z++) {
            BlockPos column = hint.offset(x, 0, z);
            int dx = column.getX() - hearth.getX(), dz = column.getZ() - hearth.getZ();
            if (dx * dx + dz * dz > Math.min(radius, 48) * Math.min(radius, 48) || !level.getWorldBorder().isWithinBounds(column)) return null;
            level.getChunkAt(column);
            int feet = naturalFeet(level, column);
            if (feet == Integer.MIN_VALUE) return null;
            low = Math.min(low, feet);
            high = Math.max(high, feet);
        }
        if (high - low > 2) return null;
        int floorY = (low + high) / 2 - 1;
        if (Math.abs(floorY + 1 - hearth.getY()) > 2) return null;
        for (int x = -2; x <= size; x++) for (int z = -3; z <= size; z++) {
            BlockPos column = new BlockPos(hint.getX() + x, floorY, hint.getZ() + z);
            int feet = naturalFeet(level, column);
            for (int y = Math.min(feet - 1, floorY - 1); y <= floorY + 8; y++) {
                BlockPos at = new BlockPos(column.getX(), y, column.getZ());
                var state = level.getBlockState(at);
                if (level.getBlockEntity(at) != null || !state.getFluidState().isEmpty()
                    || !(naturalGround(state) || removableVegetation(state))) return null;
            }
        }
        // The unedited front strip must already meet the authored two-block porch.
        for (int x = 1; x <= 3; x++) {
            BlockPos approach = new BlockPos(hint.getX() + x, floorY + 1, hint.getZ() - 3);
            int feet = naturalFeet(level, approach);
            if (Math.abs(feet - floorY - 1) > 1
                || !level.getBlockState(approach.atY(feet)).isAir()
                || !level.getBlockState(approach.atY(feet + 1)).isAir()) return null;
        }
        var volume = new net.minecraft.world.phys.AABB(hint.getX() - 2, low - 2, hint.getZ() - 3,
            hint.getX() + size + 1, floorY + 9, hint.getZ() + size + 1);
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, volume).isEmpty()) return null;
        return new BlockPos(hint.getX(), floorY, hint.getZ());
    }

    private static int naturalFeet(ServerLevel level, BlockPos column) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            column.getX(), column.getZ());
        // Tree trunks are vegetation, not a building's natural foundation height.
        for (int depth = 0; depth <= 16; depth++, y--) {
            BlockPos below = new BlockPos(column.getX(), y - 1, column.getZ());
            var state = level.getBlockState(below);
            if (!state.getFluidState().isEmpty() || level.getBlockEntity(below) != null) return Integer.MIN_VALUE;
            if (naturalGround(state)) return y;
            if (!removableVegetation(state)) return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean naturalGround(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
            || state.is(net.minecraft.world.level.block.Blocks.DIRT)
            || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
            || state.is(net.minecraft.world.level.block.Blocks.PODZOL)
            || state.is(net.minecraft.world.level.block.Blocks.STONE)
            || state.is(net.minecraft.world.level.block.Blocks.GRAVEL);
    }

    private static boolean removableVegetation(net.minecraft.world.level.block.state.BlockState state) {
        return state.isAir() || state.canBeReplaced()
            || state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    private static void constructionFoundation(ServerLevel level, BlockPos origin, int size) {
        for (int x = -1; x < size; x++) for (int z = -2; z < size; z++) {
            BlockPos floor = origin.offset(x, 0, z);
            int feet = naturalFeet(level, floor);
            for (int y = feet - 1; y < floor.getY(); y++)
                level.setBlockAndUpdate(floor.atY(y), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 8; y++)
                level.setBlockAndUpdate(floor.above(y), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    private static com.hearthstead.settlement.Building compactTavern(ServerLevel level, Settlement village, BlockPos origin) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z), net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS.defaultBlockState());
            for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(origin.offset(x, y, z),
                (x == 0 || x == 7 || z == 0 || z == 7)
                    ? net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 4, z), net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS.defaultBlockState());
        }
        for (int x : new int[]{1, 2}) {
            var door = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.NORTH);
            level.setBlockAndUpdate(origin.offset(x, 1, 0), door);
            level.setBlockAndUpdate(origin.offset(x, 2, 0), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        }
        for (int x : new int[]{2, 5}) {
            level.setBlockAndUpdate(origin.offset(x, 1, 5), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.SOUTH));
            level.setBlockAndUpdate(origin.offset(x, 1, 4), net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 2, 4), net.minecraft.world.level.block.Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, 4, 5), net.minecraft.world.level.block.Blocks.GLOWSTONE.defaultBlockState());
        }
        level.setBlockAndUpdate(origin.offset(4, 4, 2), net.minecraft.world.level.block.Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(1, 1, 2), net.minecraft.world.level.block.Blocks.BELL.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(6, 1, 2), net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
        BlockPos stock = origin.offset(4, 1, 2);
        level.setBlockAndUpdate(stock, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        net.minecraft.world.Container chest = (net.minecraft.world.Container) level.getBlockEntity(stock);
        chest.setItem(0, new ItemStack(Items.BREAD, 32));
        chest.setItem(1, new ItemStack(Items.GLASS_BOTTLE));
        chest.setChanged();
        BlockPos plaquePos = origin.offset(3, 2, -1);
        level.setBlockAndUpdate(plaquePos, com.hearthstead.registry.ModBlocks.PLAQUE.get().defaultBlockState());
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) level.getBlockEntity(plaquePos);
        require(plaque.insertPlan(level, com.hearthstead.block.PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.TAVERN)), "compact_tavern_plan_refused");
        var building = plaque.building(level);
        require(building != null && building.valid && building.type == BuildingType.TAVERN
            && village.buildings.contains(building) && building.id.equals(plaque.buildingId()),
            "compact_tavern_survey_refused");
        for (int x : new int[]{2, 5}) require(TavernSeating.site(level, building,
            origin.offset(x, 1, 5), origin.offset(x - 1, 1, 5)) != null, "compact_tavern_dining_refused");
        return building;
    }

    private static int coins(ServerLevel level, Settlement village, HearthBlockEntity hearth,
                              net.minecraft.server.level.ServerPlayer player) {
        var inventory = CoinTreasury.open(level,village,hearth,player);
        int count=0;
        for (int slot=0;slot<inventory.getSlots();slot++) {
            ItemStack stack=inventory.getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) count+=stack.getCount();
        }
        return count;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
