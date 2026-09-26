package com.hearthstead.qa;

import com.hearthstead.BuildIdentity;
import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.InnkeeperAtmosphere;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Synthetic owned-client setup; observations never advance service or transfer items. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TavernClientQaFixture {
    private static final BlockPos HEARTH = new BlockPos(520, 80, 515);
    private static final BlockPos PLAQUE = new BlockPos(522, 81, 518);
    private static final BlockPos STOCK = new BlockPos(522, 80, 523);
    private static final BlockPos ALE_BARREL = new BlockPos(522, 80, 524), ALE_TAP = new BlockPos(523, 80, 524);
    private static final AABB AREA = new AABB(516, 79, 513, 535, 86, 535);
    // The initial two-guest proof serves bread only. DRINKING belongs to the
    // later Ale-specific proof, so requiring it here made a valid food service
    // impossible to certify.
    private static final EnumSet<TavernServingEntity.Phase> BREAD_SERVICE_PHASES = EnumSet.complementOf(
        EnumSet.of(TavernServingEntity.Phase.DRINKING));
    private static final Map<ServerLevel, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        final UUID id = UUID.randomUUID();
        final Settlement village;
        final Building tavern;
        final SettlerEntity host;
        final List<SettlerEntity> guests;
        final long start;
        final boolean sixRoles;
        final Map<UUID, EnumSet<TavernServingEntity.Phase>> phases = new HashMap<>();
        final Set<UUID> seated = new HashSet<>(), began = new HashSet<>(), finished = new HashSet<>();
        final Map<UUID, Float> mealHunger = new HashMap<>();
        boolean outside, entered, wave, completed, alarm, failed, sixVerified;
        int sixActivationWaitTicks;
        int aleStage, brewed, lastWheat = 6, lastIncome;
        long aleStart;
        final Set<UUID> alePoured = new HashSet<>(), alePaid = new HashSet<>(), aleReturned = new HashSet<>();
        final Set<UUID> aleMealBegan = new HashSet<>(), aleMealFinished = new HashSet<>();
        final Map<UUID, UUID> aleGuests = new HashMap<>();
        final Map<UUID, Float> aleHunger = new HashMap<>();
        final Set<UUID> previousPayment = new HashSet<>();
        Session(Settlement village, Building tavern, SettlerEntity host, List<SettlerEntity> guests, long start, boolean sixRoles) {
            this.village = village; this.tavern = tavern; this.host = host; this.guests = guests; this.start = start; this.sixRoles = sixRoles;
        }
    }
    private TavernClientQaFixture() {}

    public static int command(CommandSourceStack source, String action) {
        try {
            ServerLevel level = source.getLevel();
            require(source.getEntity() == null && source.hasPermission(4)
                && level.getServer().isSameThread() && level.dimension().equals(Level.OVERWORLD)
                && level.getServer().getPlayerList().getPlayerCount() == 1
                && Boolean.getBoolean("hearthstead.qa.trace"), "owned_console_only");
            String configured = System.getProperty("hsqa.instanceDir", "");
            require(!configured.isEmpty(), "instance_missing");
            Path instance = Path.of(configured).toAbsolutePath().normalize();
            Path world = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path marker = instance.resolve(".hsqa-instance-owned");
            require(world.equals(instance.resolve("world")) && !Files.isSymbolicLink(marker)
                && Files.isRegularFile(marker)
                && Files.readString(marker).trim().equals("hsqa-instance-v1:playtest"), "not_owned_playtest_world");
            if (action.equals("owner-restaurant-prepare")
                || action.equals("owner-restaurant-open-paid-visitor")
                || action.equals("owner-restaurant-assert")
                || action.equals("owner-restaurant-diagnose")) {
                // This branch is deliberately after every existing ownership
                // guard above and before synthetic fixture session lookup.
                return OwnerTavernRestaurantSceneProposal.commandAfterGuard(source, action);
            }
            if (action.equals("prepare-grounded-village")) {
                prepareGroundedVillage(level);
            } else if (action.equals("grounded-status")) {
                groundedVillageStatus(level);
            } else if (action.equals("prepare") || action.equals("prepare-six")) {
                prepare(level, action.equals("prepare-six"));
            }
            else {
                Session s = SESSIONS.get(level);
                require(s != null && !s.failed, "no_valid_session");
                if (action.equals("stage-native")) {
                    require(s.sixRoles && s.completed && !s.alarm
                        && !TavernHostService.hasSession(s.host), "native_stage_requires_completed_six_role_service");
                    observe(level, s, true);
                    verifySix(level, s.village);
                    SESSIONS.remove(level); // End the exact two-bread proof before explicitly seeding a new stock.
                    Container stock = (Container) level.getBlockEntity(STOCK);
                    stock.setItem(0, new ItemStack(Items.BREAD, 32));
                    stock.setChanged();
                    for (SettlerEntity guest : s.guests) guest.setHunger(65);
                    SettlementSavedData.get(level).setDirty();
                    Hearthstead.LOGGER.info("HSQA_SIX_ROLE native_staged=true settlement={} seededBread=32 seededPatronHunger=65 priorMeals=2", s.village.id);
                    source.sendSuccess(() -> Component.literal("HSQA_TAVERN command=stage-native result=OK"), true);
                    return 1;
                }
                if (action.equals("stage-ale") || action.equals("begin-ale")
                    || action.equals("ale-status") || action.equals("finish-ale")) {
                    if (action.equals("stage-ale")) stageAle(level, s);
                    else if (action.equals("begin-ale")) {
                        observeAle(level, s, true);
                        require(s.aleStage == 1 && s.brewed == 2, "two_fresh_contact_brews_required");
                        Container stock = (Container) level.getBlockEntity(STOCK);
                        require(count(stock, Items.BREAD) == 0 && count(stock, Items.GLASS_BOTTLE) == 1,
                            "ale_meal_seed_requires_original_returned_glass");
                        stock.setItem(0, new ItemStack(Items.BREAD, 2)); stock.setChanged();
                        for (SettlerEntity guest : s.guests) guest.setHunger(65);
                        s.aleStage = 2;
                        Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} service_started=true seededBread=2", s.id);
                    } else {
                        observeAle(level, s, true);
                        if (action.equals("finish-ale")) {
                            require(s.aleStage == 3, "ale_proof_not_complete"); SESSIONS.remove(level);
                        }
                    }
                    source.sendSuccess(() -> Component.literal("HSQA_TAVERN command=" + action + " result=OK"), true);
                    return 1;
                }
                if (action.equals("alarm")) {
                    require(s.completed && !s.alarm && s.guests.stream().anyMatch(TavernSeating::hasTavernSeat),
                        "alarm_requires_completed_service_and_live_seated_guest");
                    s.village.alertUntilGameTime = level.getGameTime() + 400;
                    SettlementSavedData.get(level).setDirty();
                    s.alarm = true;
                } else require(action.equals("status") || action.equals("finish"), "unknown_action");
                observe(level, s, true);
                if (s.sixRoles) verifySix(level, s.village);
                if (action.equals("finish")) {
                    require(s.completed && s.alarm && s.guests.stream().noneMatch(TavernSeating::hasTavernSeat)
                        && s.host.innkeeperSocialMode() == InnkeeperAtmosphere.NONE
                        && !TavernHostService.hasSession(s.host), "not_finished");
                    SESSIONS.remove(level); // Stop only diagnostic observation before the client disconnects.
                }
            }
            source.sendSuccess(() -> Component.literal("HSQA_TAVERN command=" + action + " result=OK"
                + " input=" + BuildIdentity.inputHash() + " artifact=" + BuildIdentity.artifactFileName()), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_TAVERN result=FAIL reason=" + failure.getMessage()));
            return 0;
        }
    }

    private static final String GROUNDED_VILLAGE = "HearthsteadGroundedVillageQa";
    private static final Map<ServerLevel, Boolean> GROUNDED_OBSERVERS = new WeakHashMap<>();
    private static final int[][] GROUNDED_PLOT_OFFSETS = {
        {18,-8,14},{-32,-8,9},{-32,10,7},{18,12,7},
        {-32,-28,5},{-22,-28,5},{18,-28,5},{28,-28,5},{-10,-36,5},
        {-24,14,7},{-24,26,9}
    };

    /** Adds real surveyed civilian workplaces to the exact prepared defense settlement. */
    private static void prepareGroundedVillage(ServerLevel level) {
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        Settlement village = BattleQaFixtureService.groundedSettlement(level, player);
        require(village != null, "grounded_defense_must_be_prepared_first");
        var persistent = player.getPersistentData();
        require(!persistent.contains(GROUNDED_VILLAGE), "grounded_village_already_attempted");
        require(village.settlers.size() == 4 && village.mayorId == null, "unexpected_original_roster");
        // Validate every plot before the first mutation. Only small, supported
        // building foundations are authored; no rectangular terrain platform.
        int[][] offsets = GROUNDED_PLOT_OFFSETS;
        java.util.ArrayList<BlockPos> plots = new java.util.ArrayList<>();
        for (int[] offset : offsets) {
            BlockPos plot = groundedPlot(level, village.center.offset(offset[0],0,offset[1]), offset[2]);
            require(plot != null && Math.abs(plot.getY() + 1 - village.center.getY()) <= 3,
                "grounded_village_unsuitable_plot_" + plots.size());
            plots.add(plot);
        }
        for (Settlement other : SettlementSavedData.get(level).settlements.values()) {
            if (other == village) continue;
            double dx=other.center.getX()-village.center.getX(), dz=other.center.getZ()-village.center.getZ();
            require(dx*dx+dz*dz > (other.radius+64.0)*(other.radius+64.0), "grounded_expansion_overlaps_settlement");
        }
        var marker = new net.minecraft.nbt.CompoundTag();
        marker.putUUID("Settlement", village.id);
        marker.putString("Stage", "building");
        persistent.put(GROUNDED_VILLAGE, marker); // Partial setup cannot be repeated to mint goods/actors.
        village.radius = 64; // All surveyed plots and their normal approaches belong to this one village.
        for (int i = 0; i < plots.size(); i++) groundedFoundation(level, plots.get(i), offsets[i][2]);
        Building tavern = groundedTavern(level, village, plots.get(0));
        Building lumber = RaidQaFixtureService.prepareClientRoom(level, village, plots.get(1), BuildingType.LUMBER_CAMP);
        Building farm = RaidQaFixtureService.prepareClientRoom(level, village, plots.get(2), BuildingType.FARMHOUSE);
        Building warehouse = RaidQaFixtureService.prepareClientRoom(level, village, plots.get(3), BuildingType.WAREHOUSE);
        for (int i = 4; i <= 8; i++) {
            RaidQaFixtureService.prepareClientRoom(level, village, plots.get(i), BuildingType.HOUSE);
        }
        SettlerEntity mayor = resident(level, village, "Alden", Vec3.atBottomCenterOf(plots.get(4).offset(2,1,-2)), 100);
        require(com.hearthstead.settlement.Mayor.appoint(level, village, mayor) == null, "grounded_mayor_refused");
        Building[] jobs = {tavern, lumber, farm, warehouse};
        String[] names = {"Mara", "Rowan", "Elin", "Finn"};
        Profession[] roles = {Profession.INNKEEPER, Profession.LUMBERER, Profession.FARMER, Profession.COURIER};
        for (int i = 0; i < jobs.length; i++) {
            SettlerEntity worker = resident(level, village, names[i],
                Vec3.atBottomCenterOf(plots.get(i).offset(3,1,-2)), 100);
            require(Employment.hire(level, village, jobs[i], worker).ok()
                && worker.getProfession() == roles[i], "grounded_real_hire_refused_" + roles[i]);
            if (roles[i] == Profession.LUMBERER) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                worker.bag.addItem(new ItemStack(Items.OAK_SAPLING,4));
            } else if (roles[i] == Profession.FARMER) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
                worker.bag.addItem(new ItemStack(Items.WHEAT_SEEDS,32));
            }
        }
        BlockPos field = plots.get(9), grove = plots.get(10);
        commitZone(level, village, farm, WorkZone.Type.FARM, field, field.offset(6,4,6));
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
            BlockPos soil = field.offset(x,0,z);
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState()
                .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE,7));
            level.setBlockAndUpdate(soil.above(), Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE,7));
        }
        level.setBlockAndUpdate(field.offset(3,0,3), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(field.offset(3,1,3), Blocks.AIR.defaultBlockState());
        commitZone(level, village, lumber, WorkZone.Type.LUMBER, grove, grove.offset(8,8,8));
        for (int z : new int[]{2,6}) {
            BlockPos trunk = grove.offset(4,1,z);
            level.setBlockAndUpdate(trunk.below(), Blocks.DIRT.defaultBlockState());
            for (int y = 0; y < 4; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
            for (int x = -2; x <= 2; x++) for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(trunk.offset(x,4,dz), Blocks.OAK_LEAVES.defaultBlockState());
        }
        marker.putUUID("Lumber", lumber.id); marker.putUUID("Farm", farm.id);
        marker.putUUID("Warehouse", warehouse.id); marker.putUUID("Tavern", tavern.id);
        marker.putString("Stage", "ready");
        GROUNDED_OBSERVERS.put(level, true);
        SettlementSavedData.get(level).setDirty();
        groundedVillageStatus(level);
    }

    /**
     * Pure terrain preflight used by BattleQA before it creates the defense
     * settlement. This deliberately shares the addon's exact eleven plot
     * predicates, including its local elevation allowance.
     */
    static boolean groundedVillageFootprintAt(ServerLevel level, BlockPos center) {
        for (int[] offset : GROUNDED_PLOT_OFFSETS) {
            BlockPos plot = groundedPlot(level, center.offset(offset[0], 0, offset[1]), offset[2]);
            if (plot == null || Math.abs(plot.getY() + 1 - center.getY()) > 3) return false;
        }
        return true;
    }

    /** Pure eligibility scan except loading bounded normal terrain chunks. */
    static BlockPos groundedPlot(ServerLevel level, BlockPos hint, int size) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = -2; x <= size; x++) for (int z = -3; z <= size; z++) {
            BlockPos column = hint.offset(x,0,z);
            if (!level.getWorldBorder().isWithinBounds(column)) return null;
            level.getChunkAt(column);
            int feetY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                column.getX(), column.getZ());
            BlockPos feet = new BlockPos(column.getX(),feetY,column.getZ());
            if (feetY <= level.getMinBuildHeight()+2 || feetY+9 >= level.getMaxBuildHeight()
                || !level.getFluidState(feet.below()).isEmpty()
                || !level.getBlockState(feet.below()).isCollisionShapeFullBlock(level,feet.below())) return null;
            for (int dy = -1; dy <= 8; dy++) {
                BlockPos at = feet.above(dy);
                if (level.getBlockEntity(at) != null || !level.getFluidState(at).isEmpty()) return null;
                if (dy >= 0 && !level.getBlockState(at).isAir() && !level.getBlockState(at).canBeReplaced()) return null;
            }
            low = Math.min(low,feetY); high = Math.max(high,feetY);
            if (high-low > 1) return null;
        }
        AABB box = new AABB(hint.getX()-2,low-1,hint.getZ()-3,
            hint.getX()+size+1,high+9,hint.getZ()+size+1);
        if (!level.getEntitiesOfClass(SettlerEntity.class,box).isEmpty()) return null;
        return new BlockPos(hint.getX(),high-1,hint.getZ());
    }

    static void groundedFoundation(ServerLevel level, BlockPos origin, int size) {
        for (int x = -1; x < size; x++) for (int z = -2; z < size; z++) {
            BlockPos top = origin.offset(x,0,z);
            // Plot scan allows at most one block of terrain variation.
            if (level.getBlockState(top.below()).canBeReplaced())
                level.setBlockAndUpdate(top.below(), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(top, Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 8; y++) {
                BlockPos at = top.above(y);
                if (!level.getBlockState(at).isAir()) level.setBlockAndUpdate(at,Blocks.AIR.defaultBlockState());
            }
        }
    }

    static Building groundedTavern(ServerLevel level, Settlement village, BlockPos origin) {
        for (int x = 0; x < 14; x++) for (int z = 0; z < 14; z++) {
            level.setBlockAndUpdate(origin.offset(x,0,z), Blocks.SPRUCE_PLANKS.defaultBlockState());
            for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(origin.offset(x,y,z),
                (x==0 || x==13 || z==0 || z==13) ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x,4,z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        }
        for (int x : new int[]{1,2}) {
            // A real double door must open away from its partner. Two LEFT
            // hinges leave the right-hand leaf across the passenger lane even
            // though both blocks report OPEN, which strands ordinary visitors
            // on the sill.
            var door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, x == 1 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
            level.setBlockAndUpdate(origin.offset(x,1,0),door);
            level.setBlockAndUpdate(origin.offset(x,2,0),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        }
        for (int x : new int[]{5,8}) {
            furnishTable(level, origin.offset(x,1,7), origin.offset(x,1,6));
            level.setBlockAndUpdate(origin.offset(x,4,9),Blocks.GLOWSTONE.defaultBlockState());
        }
        level.setBlockAndUpdate(origin.offset(6,4,3),Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(6,1,3),Blocks.BELL.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(3,1,5),Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(4,1,5),ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING,Direction.EAST));
        BlockPos stock = origin.offset(3,1,4);
        level.setBlockAndUpdate(stock,Blocks.CHEST.defaultBlockState());
        Container chest = (Container) level.getBlockEntity(stock);
        chest.setItem(0,new ItemStack(Items.BREAD,32));
        chest.setItem(1,new ItemStack(Items.GLASS_BOTTLE)); chest.setChanged();
        BlockPos plaquePos = origin.offset(3,2,-1);
        level.setBlockAndUpdate(plaquePos,ModBlocks.PLAQUE.get().defaultBlockState());
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) level.getBlockEntity(plaquePos);
        require(plaque.insertPlan(level,PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),BuildingType.TAVERN)),
            "grounded_tavern_plan_refused");
        Building result = plaque.building(level);
        require(result != null && result.valid && result.type == BuildingType.TAVERN
            && village.buildings.contains(result) && result.id.equals(plaque.buildingId()), "grounded_tavern_survey_refused");
        return result;
    }

    private static void groundedVillageStatus(ServerLevel level) {
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        var marker = player.getPersistentData().getCompound(GROUNDED_VILLAGE);
        require(marker.hasUUID("Settlement") && marker.getString("Stage").equals("ready"), "grounded_village_not_ready");
        Settlement village = SettlementSavedData.get(level).settlements.get(marker.getUUID("Settlement"));
        require(village != null && village.settlers.size()==9 && village.mayorId != null, "grounded_roster_changed");
        var members = com.hearthstead.settlement.SettlementManager.loadedMembers(level,village);
        require(members.size()==9 && members.stream().allMatch(SettlerEntity::isAlive), "grounded_members_not_alive_loaded");
        for (Profession role : new Profession[]{Profession.MAYOR,Profession.INNKEEPER,Profession.LUMBERER,Profession.FARMER,Profession.COURIER})
            require(members.stream().filter(e -> e.getProfession()==role).count()==1, "grounded_role_missing_"+role);
        int wood=0,wheat=0,warehouseWheat=0;
        for (Building building : village.buildings) {
            if (!building.valid || building.bounds==null) continue;
            for (BlockPos at : BlockPos.betweenClosed(new BlockPos(building.bounds.minX(),building.bounds.minY(),building.bounds.minZ()),
                    new BlockPos(building.bounds.maxX(),building.bounds.maxY(),building.bounds.maxZ()))) {
                if (level.getBlockEntity(at) instanceof Container container) {
                    wood += count(container,Items.OAK_LOG); wheat += count(container,Items.WHEAT);
                    if (building.id.equals(marker.getUUID("Warehouse"))) warehouseWheat += count(container,Items.WHEAT);
                }
            }
        }
        HearthBlockEntity hearth=(HearthBlockEntity)level.getBlockEntity(village.center);
        int hearthWheat=0;
        for(int slot=0;slot<hearth.getInventory().getSlots();slot++)
            hearthWheat += count(hearth.getInventory().getStackInSlot(slot),Items.WHEAT);
        Hearthstead.LOGGER.info("HSQA_GROUNDED_VILLAGE ready=true settlement={} members=9 workers=8 mayors=1 storedLogs={} storedWheat={} hearthWheat={} warehouseWheat={} progression=seeded_qa",village.id,wood,wheat,hearthWheat,warehouseWheat);
        if (wood > 0 && warehouseWheat > 0) {
            Hearthstead.LOGGER.info("HSQA_GROUNDED_VILLAGE_PRODUCTIVE settlement={} storedLogs={} warehouseWheat={}",village.id,wood,warehouseWheat);
            GROUNDED_OBSERVERS.remove(level); // Stop sampling after actual producer + delivery witness.
        }
    }

    private static void furnishTable(ServerLevel level, BlockPos chair, BlockPos table) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var chairId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_chair");
        var tableId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_table");
        if (net.neoforged.fml.ModList.get().isLoaded("another_furniture")) {
            require(registry.containsKey(chairId) && registry.containsKey(tableId), "furniture_registry_missing");
            var state = registry.get(chairId).defaultBlockState();
            var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
            if (state.hasProperty(facing)) state = state.setValue(facing, Direction.NORTH);
            level.setBlockAndUpdate(chair, state);
            level.setBlockAndUpdate(table, registry.get(tableId).defaultBlockState());
        } else {
            level.setBlockAndUpdate(chair, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(table, Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(table.above(), Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
        }
    }

    private static void prepare(ServerLevel level, boolean sixRoles) {
        require(!SESSIONS.containsKey(level), "already_prepared");
        BlockPos siteMin = sixRoles ? new BlockPos(478, 79, 495) : new BlockPos(516, 79, 513);
        BlockPos siteMax = sixRoles ? new BlockPos(540, 88, 552) : new BlockPos(534, 85, 534);
        for (BlockPos p : BlockPos.betweenClosed(siteMin, siteMax))
            require(level.hasChunkAt(p) && level.getBlockState(p).isAir() && level.getBlockEntity(p) == null,
                "site_not_empty_loaded_air");
        require(level.getEntitiesOfClass(SettlerEntity.class, AREA).isEmpty(), "site_has_residents");
        for (int x = siteMin.getX(); x <= siteMax.getX(); x++)
            for (int z = siteMin.getZ(); z <= siteMax.getZ(); z++)
            level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE_BRICKS.defaultBlockState());
        // Actual walls with an open doorway. Registered interior stays small enough for bounded scans.
        for (int x = 519; x <= 532; x++) for (int z = 519; z <= 532; z++) {
            for (int y = 80; y <= 83; y++) if (x == 519 || x == 532 || z == 519 || z == 532)
                level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.OAK_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 83, z), Blocks.OAK_PLANKS.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(522, 83, 528), Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(529, 83, 528), Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(529, 83, 522), Blocks.GLOWSTONE.defaultBlockState());
        // Two real doors keep the surveyed room enclosed and remain ordinary AI routes.
        for (int x = 520; x <= 521; x++) {
            // Pair the hinges so opening this concrete Tavern entrance leaves
            // both physical lanes clear for travelers and serving traffic.
            var door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, x == 520 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
            level.setBlockAndUpdate(new BlockPos(x, 80, 519), door);
            level.setBlockAndUpdate(new BlockPos(x, 81, 519),
                door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        level.setBlockAndUpdate(new BlockPos(525, 80, 522), Blocks.BELL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(522, 80, 524), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(523, 80, 524), ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING,Direction.EAST));
        level.setBlockAndUpdate(PLAQUE, ModBlocks.PLAQUE.get().defaultBlockState()); // NORTH, backed by wall SOUTH.
        level.setBlockAndUpdate(HEARTH, ModBlocks.HEARTH.get().defaultBlockState());
        level.setBlockAndUpdate(STOCK, Blocks.CHEST.defaultBlockState());
        for (int x : new int[]{525, 528}) {
            furnishTable(level, new BlockPos(x,80,526), new BlockPos(x,80,525));
        }
        Settlement village = new Settlement(UUID.randomUUID(), "Tavern client fixture", HEARTH);
        village.radius = sixRoles ? 64 : 32;
        ((HearthBlockEntity) level.getBlockEntity(HEARTH)).bindSettlement(village.id);
        SettlementSavedData.get(level).settlements.put(village.id, village);
        require(level.getBlockEntity(PLAQUE) instanceof PlaqueBlockEntity, "plaque_missing");
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) level.getBlockEntity(PLAQUE);
        require(plaque.insertPlan(level, PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.TAVERN)), "tavern_plan_refused");
        Building tavern = plaque.building(level);
        require(tavern != null && tavern.valid && tavern.type == BuildingType.TAVERN
            && plaque.state() == PlaqueState.LINKED_VALID
            && village.buildings.contains(tavern), "tavern_survey_refused");
        SettlerEntity host = resident(level, village, "Mara", new Vec3(523.5, 80, 521.5), 100);
        require(Employment.hire(level, village, tavern, host).ok(), "real_employment_refused");
        SettlerEntity first = resident(level, village, "Freya", new Vec3(529.5, 80, 529.5), 65);
        SettlerEntity second = resident(level, village, "Bram", new Vec3(522.5, 80, 529.5), 65);
        Container chest = (Container) level.getBlockEntity(STOCK);
        chest.setItem(0, new ItemStack(Items.BREAD, 2));
        chest.setItem(1, new ItemStack(Items.GLASS_BOTTLE));
        chest.setChanged();
        if (sixRoles) prepareWorkers(level, village);
        SettlementSavedData.get(level).setDirty();
        Session s = new Session(village, tavern, host, List.of(first, second), level.getGameTime(), sixRoles);
        SESSIONS.put(level, s);
        Hearthstead.LOGGER.info("HSQA_TAVERN session={} prepared=true settlement={} tavern={} host={} guestA={} guestB={} stock={} bread=2 glass=1 input={} artifact={}",
            s.id, village.id, tavern.id, host.getUUID(), first.getUUID(), second.getUUID(), STOCK.toShortString(),
            BuildIdentity.inputHash(), BuildIdentity.artifactFileName());
    }
    private static void prepareWorkers(ServerLevel level, Settlement village) {
        // Explicit owned QA setup: the Mayor is a separate resident, never a
        // worker removed from a real job or a migrated player-save citizen.
        require(village.mayorId == null, "unexpected_existing_mayor");
        SettlerEntity mayor = resident(level, village, "Alden",
            new Vec3(517.5, 80, 515.5), 100);
        require(com.hearthstead.settlement.Mayor.appoint(level, village, mayor) == null,
            "mayor_appointment_refused");
        BuildingType[] types = {BuildingType.LUMBER_CAMP, BuildingType.FARMHOUSE,
            BuildingType.WAREHOUSE, BuildingType.BARRACKS, BuildingType.WATCHTOWER};
        BlockPos[] rooms = {new BlockPos(482, 79, 516), new BlockPos(492, 79, 530),
            new BlockPos(502, 79, 516), new BlockPos(500, 79, 500), new BlockPos(512, 79, 500)};
        Profession[] roles = {Profession.LUMBERER, Profession.FARMER, Profession.COURIER,
            Profession.GUARD, Profession.ARCHER};
        for (int i = 0; i < types.length; i++) {
            Building building = RaidQaFixtureService.prepareClientRoom(level, village, rooms[i], types[i]);
            String name = switch (roles[i]) {
                case LUMBERER -> "Rowan";
                case FARMER -> "Elin";
                case COURIER -> "Finn";
                case GUARD -> "Garrick";
                case ARCHER -> "Sylas";
                default -> throw new IllegalStateException("Unexpected demo role");
            };
            SettlerEntity worker = resident(level, village, name,
                Vec3.atBottomCenterOf(rooms[i].offset(3, 1, -2)), 100);
            require(Employment.hire(level, village, building, worker).ok(), "hire_refused_" + roles[i]);
            require(worker.getProfession() == roles[i]
                && Employment.employerOf(village, worker.getUUID()) == building, "employment_mismatch");
            if (roles[i] == Profession.LUMBERER) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                commitZone(level, village, building, WorkZone.Type.LUMBER,
                    new BlockPos(480, 79, 536), new BlockPos(489, 86, 550));
                for (int z : new int[] {539, 546}) {
                    BlockPos trunk = new BlockPos(484, 80, z);
                    level.setBlockAndUpdate(trunk.below(), Blocks.DIRT.defaultBlockState());
                    for (int y = 0; y < 4; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
                    for (int x = -2; x <= 2; x++) for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(trunk.offset(x, 4, dz), Blocks.OAK_LEAVES.defaultBlockState());
                }
                worker.bag.addItem(new ItemStack(Items.OAK_SAPLING, 4));
            } else if (roles[i] == Profession.FARMER) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
                commitZone(level, village, building, WorkZone.Type.FARM,
                    new BlockPos(505, 79, 538), new BlockPos(511, 83, 544));
                for (int x = 506; x <= 510; x++) for (int z = 539; z <= 543; z++) {
                    // Leave the irrigation cell empty from the outset. Replacing
                    // farmland beneath mature wheat drops unowned setup cargo.
                    if (x == 508 && z == 541) continue;
                    BlockPos soil = new BlockPos(x, 79, z);
                    level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
                    level.setBlockAndUpdate(soil.above(), Blocks.WHEAT.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
                }
                level.setBlockAndUpdate(new BlockPos(508, 79, 541), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(508, 80, 541), Blocks.AIR.defaultBlockState());
                worker.bag.addItem(new ItemStack(Items.WHEAT_SEEDS, 32));
            } else if (roles[i] == Profession.GUARD) {
                // Only this newly created QA actor receives the demo loadout.
                // Fail rather than strip any unexpected physical equipment.
                require(worker.getMainHandItem().isEmpty() && worker.getOffhandItem().isEmpty(),
                    "guard_seed_requires_empty_hands");
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
            } else if (roles[i] == Profession.ARCHER) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                require(worker.storeCarriedArrows(building.id, SettlerEntity.ARCHER_QUIVER_CAPACITY)
                    == SettlerEntity.ARCHER_QUIVER_CAPACITY, "arrow_seed_refused");
            }
        }
        for (BlockPos home : List.of(new BlockPos(482, 79, 500), new BlockPos(490, 79, 500),
                new BlockPos(482, 79, 508))) {
            RaidQaFixtureService.prepareClientRoom(level, village, home, BuildingType.HOUSE);
        }
        // addFreshEntity may defer UUID-index visibility until the command transaction ends.
        // afterTick performs the same strict 9/9 verification before observation begins.
    }

    private static void verifySix(ServerLevel level, Settlement village) {
        var members = com.hearthstead.settlement.SettlementManager.loadedMembers(level, village);
        if (members.size() != 9 || !members.stream().allMatch(SettlerEntity::isAlive)) {
            throw new IllegalStateException("expected_six_workers_two_patrons_and_dedicated_mayor"
                + " records=" + village.settlers.size() + " loaded=" + members.size()
                + " resolved=" + village.settlers.stream().map(record -> {
                    var entity = level.getEntity(record.entityId);
                    return record.entityId + ":" + (entity == null ? "missing"
                        : entity.getType() + ":alive=" + entity.isAlive()
                            + ":pos=" + entity.blockPosition().toShortString());
                }).toList());
        }
        require(village.mayorId != null && members.stream().filter(e ->
            e.getUUID().equals(village.mayorId) && e.getProfession() == Profession.MAYOR
                && Employment.employerOf(village, e.getUUID()) == null).count() == 1,
            "dedicated_unemployed_mayor_missing");
        require(members.stream().filter(e -> !e.getUUID().equals(village.mayorId)
            && e.getProfession() == Profession.NONE
            && Employment.employerOf(village, e.getUUID()) == null).count() == 2,
            "exact_two_unemployed_patrons_missing");
        for (Building building : village.buildings) {
            require(building.valid && level.getBlockEntity(building.plaquePos) instanceof PlaqueBlockEntity,
                "missing_physical_workplace");
            PlaqueBlockEntity plaque = (PlaqueBlockEntity) level.getBlockEntity(building.plaquePos);
            require(plaque.state() == PlaqueState.LINKED_VALID && plaque.type() == building.type
                && building.id.equals(plaque.buildingId()) && plaque.building(level) == building,
                "physical_registry_mismatch");
        }
        for (Profession role : new Profession[] {Profession.COURIER, Profession.LUMBERER, Profession.FARMER,
                Profession.GUARD, Profession.ARCHER, Profession.INNKEEPER}) {
            require(members.stream().filter(e -> e.getProfession() == role
                && Employment.employerOf(village, e.getUUID()) != null).count() == 1,
                "exact_role_missing_" + role);
        }
        Hearthstead.LOGGER.info("HSQA_SIX_ROLE verified=true settlement={} members=9 employed=6 patrons=2 mayors=1 progression=seeded_qa", village.id);
    }

    private static void commitZone(ServerLevel level, Settlement village, Building building,
                                   WorkZone.Type type, BlockPos min, BlockPos max) {
        require(building.commitWorkZone(0, WorkZone.between(village.id, building.id, type,
            level.dimension().location(), min, max, 1)), "seeded_work_zone_refused");
    }

    private static SettlerEntity resident(ServerLevel level, Settlement s, String name, Vec3 p, float hunger) {
        SettlerEntity actor = ModEntities.SETTLER.get().create(level);
        require(actor != null, "resident_creation_failed");
        actor.moveTo(p.x, p.y, p.z, 180, 0);
        actor.setSettlerName(name); actor.setPersistenceRequired(); actor.bindTo(s.id, s.center);
        s.putRecord(actor.getUUID(), name, Profession.NONE);
        actor.setHunger(hunger); actor.setEnergy(100);
        require(level.addFreshEntity(actor), "resident_join_rejected");
        return actor; // No NoAI, mounting, target assignment, meal injection or service calls.
    }

    @SubscribeEvent public static void afterTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (GROUNDED_OBSERVERS.containsKey(level) && level.getGameTime() % 100 == 0) {
            try { groundedVillageStatus(level); }
            catch (Exception failure) {
                GROUNDED_OBSERVERS.remove(level);
                Hearthstead.LOGGER.error("HSQA_GROUNDED_VILLAGE_FAILED reason={}", failure.getMessage());
            }
        }
        Session s = SESSIONS.get(level);
        if (s == null || s.failed) return;
        try {
            if (s.sixRoles && !s.sixVerified) {
                int visible = com.hearthstead.settlement.SettlementManager.loadedMembers(level, s.village).size();
                if (visible != 9) {
                    if (++s.sixActivationWaitTicks > 40) verifySix(level, s.village);
                    return;
                }
                verifySix(level, s.village);
                s.sixVerified = true;
            }
            if (s.aleStage != 0) { observeAle(level, s, false); return; }
            observe(level, s, false);
            long age = level.getGameTime() - s.start;
            if (!s.completed && (age == 800 || age == 2400 || age == 3400)) {
                diagnoseService(level, s, age);
            }
        }
        catch (Exception failure) {
            s.failed = true;
            Hearthstead.LOGGER.error("HSQA_TAVERN session={} result=FAIL reason={}", s.id, failure.getMessage());
        }
    }
    /** Three fixed-age observations only; never probe a path or mutate actors/claims. */
    private static void diagnoseService(ServerLevel level, Session s, long age) {
        Hearthstead.LOGGER.info("HSQA_TAVERN_DIAG session={} age={} tavern={} bounds={} staffed={} "
                + "seatedWitness={} mealStartedWitness={} mealFinishedWitness={} pendingRaid={} alertUntil={} gameTime={}",
            s.id, age, s.tavern.id, s.tavern.bounds,
            TavernSeating.staffed(level, s.village, s.tavern), s.seated, s.began, s.finished,
            s.village.pendingRaid != null, s.village.alertUntilGameTime, level.getGameTime());
        for (SettlerEntity guest : s.guests) {
            Hearthstead.LOGGER.info("HSQA_TAVERN_DIAG session={} age={} guest={}",
                s.id, age, actorDiagnostic(guest));
        }
        Building employer = Employment.employerOf(s.village, s.host.getUUID());
        Hearthstead.LOGGER.info("HSQA_TAVERN_DIAG session={} age={} host={} employer={} "
                + "sessionActive={} authorized={}",
            s.id, age, actorDiagnostic(s.host), employer == null ? null : employer.id,
            TavernHostService.hasSession(s.host), TavernHostService.authorizedHost(s.host, s.tavern.id));
        for (int x : new int[] {525, 528}) {
            BlockPos chair = new BlockPos(x, 80, 526);
            for (Direction side : new Direction[] {Direction.EAST, Direction.WEST}) {
                var site = TavernSeating.site(level, s.tavern, chair, chair.relative(side));
                StringBuilder clearance = new StringBuilder();
                if (site != null) {
                    for (SettlerEntity guest : s.guests) {
                        clearance.append(guest.getUUID()).append(":aisle=")
                            .append(TavernSeating.clearStand(level, guest, site.aisle()))
                            .append(",hostApproachForGuest=")
                            .append(TavernSeating.clearStand(level, guest, site.hostApproach())).append(';');
                    }
                    clearance.append("hostApproachForHost=")
                        .append(TavernSeating.clearStand(level, s.host, site.hostApproach()));
                }
                Hearthstead.LOGGER.info("HSQA_TAVERN_DIAG session={} age={} chair={} chairState={} "
                        + "side={} site={} occupied={} tableClaimed={} clearance={}",
                    s.id, age, chair, level.getBlockState(chair), side, site,
                    TavernSeating.occupied(level, chair),
                    site != null && s.tavern.tavernServingClaims.occupied(site.table()), clearance);
            }
        }
    }

    private static String actorDiagnostic(SettlerEntity actor) {
        StringBuilder bag = new StringBuilder();
        for (int slot = 0; slot < actor.bag.getContainerSize(); slot++) {
            ItemStack stack = actor.bag.getItem(slot);
            if (!stack.isEmpty()) bag.append(slot).append(':').append(stack).append(';');
        }
        var target = actor.getTarget();
        var path = actor.getNavigation().getPath();
        return "id=" + actor.getUUID() + ",pos=" + actor.position()
            + ",activity=" + actor.getActivity() + ",dayPhase=" + actor.dayPhase()
            + ",hunger=" + actor.getHunger() + ",energy=" + actor.getEnergy()
            + ",bag=[" + bag + "],meal=" + actor.mealDisplayCopy()
            + ",mealTicks=" + actor.mealRemainingTicks() + ",hasSeat=" + actor.hasTavernSeat()
            + ",seatSite=" + TavernSeating.currentSite(actor)
            + ",target=" + (target == null ? null : target.getUUID())
            // mayVisit invokes self-expiring Summons.active; do not mutate it for logging.
            + ",alive=" + actor.isAlive() + ",sleeping=" + actor.isSleeping()
            + ",traveler=" + actor.isTraveler() + ",passenger=" + actor.isPassenger()
            + ",hurtTime=" + actor.hurtTime + ",onFire=" + actor.isOnFire()
            + ",navDone=" + actor.getNavigation().isDone()
            + ",navTarget=" + actor.getNavigation().getTargetPos()
            + ",pathCanReach=" + (path == null ? "no_path" : path.canReach())
            + ",tavernVisit=" + actor.tavernVisitDiagnostic()
            + ",mainHand=" + actor.getMainHandItem() + ",offHand=" + actor.getOffhandItem();
    }

    private static void observe(ServerLevel level, Session s, boolean report) {
        long age = level.getGameTime() - s.start;
        require(age >= 0 && age < 6000, "observation_deadline_6000_ticks");
        require(s.host.isAlive() && level.getEntity(s.host.getUUID()) == s.host
            && Employment.employerOf(s.village, s.host.getUUID()) == s.tavern, "host_identity_or_employer_changed");
        require(level.players().size() == 1, "expected_exactly_one_fixture_viewer");
        boolean inside = s.tavern.contains(level.players().getFirst().blockPosition());
        s.outside |= !inside;
        s.entered |= inside && s.outside;
        s.wave |= s.host.innkeeperSocialMode() == InnkeeperAtmosphere.WELCOME;
        int seatedNow = 0, activeBread = 0;
        for (SettlerEntity guest : s.guests) {
            require(guest.isAlive() && level.getEntity(guest.getUUID()) == guest
                && s.village.id.equals(guest.getSettlementId()), "guest_identity_changed");
            require(guest.bag.isEmpty() && s.host.bag.isEmpty(), "unexpected_bag_cargo");
            if (guest.hasTavernSeat()) { s.seated.add(guest.getUUID()); seatedNow++; }
            if (guest.hasMeal()) {
                require(guest.hasTavernSeat(), "fixture_meal_not_received_while_seated");
                if (s.began.add(guest.getUUID())) s.mealHunger.put(guest.getUUID(), guest.getHunger());
                activeBread += count(guest.mealDisplayCopy(), Items.BREAD);
            } else if (s.began.contains(guest.getUUID()) && !s.finished.contains(guest.getUUID())) {
                require(guest.getHunger() >= s.mealHunger.get(guest.getUUID()) + 35, "meal_ended_without_bread_nutrition");
                s.finished.add(guest.getUUID());
            }
        }
        Container chest = (Container) level.getBlockEntity(STOCK);
        require(chest != null, "stock_missing");
        int bread = count(chest, Items.BREAD), glass = count(chest, Items.GLASS_BOTTLE);
        int ownedBread = activeBread + bread, ownedGlass = glass;
        var servings = level.getEntitiesOfClass(TavernServingEntity.class, AREA,
            e -> s.village.id.equals(e.settlementId()));
        for (TavernServingEntity serving : servings) {
            require(s.host.getUUID().equals(serving.hostId()) && s.guests.stream().anyMatch(g -> g.getUUID().equals(serving.guestId()))
                && STOCK.equals(serving.source()) && s.tavern.id.equals(serving.site().tavernId()), "serving_identity_changed");
            var phases = s.phases.computeIfAbsent(serving.getUUID(), id -> EnumSet.noneOf(TavernServingEntity.Phase.class));
            if (phases.add(serving.phase())) Hearthstead.LOGGER.info("HSQA_TAVERN session={} serving={} guest={} phase={} tick={}",
                s.id, serving.getUUID(), serving.guestId(), serving.phase(), level.getGameTime());
            ownedBread += count(serving.displayFood(), Items.BREAD);
            ownedGlass += count(serving.displayGlass(), Items.GLASS_BOTTLE);
        }
        int drops = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, AREA)) {
            int food = count(item.getItem(), Items.BREAD), bottle = count(item.getItem(), Items.GLASS_BOTTLE);
            drops += food + bottle; ownedBread += food; ownedGlass += bottle;
        }
        // A completed service is an immutable proof point. After that point a
        // live Courier is allowed to collect the returned reusable glass from
        // Tavern storage; it must not retroactively invalidate the service.
        if (!s.completed) require(ownedBread + s.finished.size() == 2 && ownedGlass == 1,
            "physical_food_or_glass_conservation_failed");
        boolean allPhases = s.phases.size() == 2 && s.phases.values().stream().allMatch(p -> p.containsAll(BREAD_SERVICE_PHASES));
        boolean claimsClear = !s.tavern.tavernServingClaims.occupied(new BlockPos(525, 80, 525))
            && !s.tavern.tavernServingClaims.occupied(new BlockPos(528, 80, 525));
        boolean completeNow = s.finished.size() == 2 && s.seated.size() == 2 && allPhases
            && bread == 0 && glass == 1 && drops == 0 && claimsClear
            && servings.isEmpty() && !TavernHostService.hasSession(s.host);
        s.completed |= completeNow;
        boolean interrupted = s.alarm && seatedNow == 0 && s.host.innkeeperSocialMode() == InnkeeperAtmosphere.NONE
            && bread == 0 && glass == 1 && servings.isEmpty() && !TavernHostService.hasSession(s.host);
        if (report || age % 20 == 0) Hearthstead.LOGGER.info(
            "HSQA_TAVERN session={} failed=false age={} entered={} wave={} seated={} meals={} phases={} completed={} alarmClear={} bread={} glass={}",
            s.id, age, s.entered, s.wave, seatedNow, s.finished.size(), allPhases, s.completed, interrupted, bread, glass);
    }
    /** Explicit new owned-fixture inputs after the immutable original two-bread gate. */
    private static void stageAle(ServerLevel level, Session s) {
        require(s.completed && !s.alarm && s.aleStage == 0 && !TavernHostService.hasSession(s.host),
            "ale_requires_completed_bread_proof");
        observe(level, s, true);
        Container barrel = com.hearthstead.settlement.work.AleTapService.barrel(level, ALE_TAP);
        Container stock = (Container) level.getBlockEntity(STOCK);
        require(barrel != null && ALE_TAP.equals(com.hearthstead.settlement.work.AleTapService
            .resolveTapForBarrel(level, s.tavern, ALE_BARREL)) && barrel.isEmpty(), "exact_empty_tap_barrel_required");
        require(stock != null && count(stock, Items.BREAD) == 0 && count(stock, Items.GLASS_BOTTLE) == 1
            && s.guests.stream().allMatch(g -> g.bag.isEmpty() && !g.hasMeal()) && s.host.bag.isEmpty(),
            "clean_prior_custody_required");
        level.setDayTime(11500); // Explicit separate evening fixture setup, never a service-clock advance.
        s.aleStage = 1; s.aleStart = level.getGameTime();
        barrel.setItem(0, new ItemStack(Items.WHEAT, 6)); barrel.setChanged();
        for (SettlerEntity guest : s.guests) {
            guest.bag.setItem(0, new ItemStack(ModItems.GOLD_COIN.get())); guest.bag.setChanged();
            guest.setHunger(100); // Keep new meal ordering closed until the refill proof is ready.
        }
        Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} prepared=true wheat=6 coins=2 directAle=0 guestA={} guestB={}",
            s.id, s.guests.get(0).getUUID(), s.guests.get(1).getUUID());
    }

    /** Read-only every-tick witnesses. Never moves an actor, transfers cargo or calls serviceTick. */
    private static void observeAle(ServerLevel level, Session s, boolean report) {
        require(s.aleStage > 0 && level.getGameTime() - s.aleStart < 6000, "ale_deadline_6000_ticks");
        require(s.host.isAlive() && level.getEntity(s.host.getUUID()) == s.host
            && Employment.employerOf(s.village, s.host.getUUID()) == s.tavern, "ale_host_identity_changed");
        Container barrel = com.hearthstead.settlement.work.AleTapService.barrel(level, ALE_TAP);
        Container stock = (Container) level.getBlockEntity(STOCK);
        require(barrel != null && stock != null, "ale_original_containers_missing");
        int wheat = count(barrel, Items.WHEAT), ale = count(barrel, ModItems.ALE.get());
        int income = count(barrel, ModItems.GOLD_COIN.get()), coins = income;
        int bread = count(stock, Items.BREAD), glass = count(stock, Items.GLASS_BOTTLE);
        if (wheat != s.lastWheat) {
            require(s.aleStage == 1 && wheat == s.lastWheat - 3
                && com.hearthstead.settlement.work.ContainerApproach.inspect(level, s.host, ALE_BARREL).canInteract(),
                "brew_requires_exact_three_wheat_and_actual_barrel_contact");
            s.brewed++; s.lastWheat = wheat;
            Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} brew={} wheat={} barrelAle={} contact=true", s.id,s.brewed,wheat,ale);
        }
        for (SettlerEntity guest : s.guests) {
            require(guest.isAlive() && level.getEntity(guest.getUUID()) == guest
                && s.village.id.equals(guest.getSettlementId()), "ale_guest_identity_changed");
            for (int i=0;i<guest.bag.getContainerSize();i++) require(guest.bag.getItem(i).isEmpty()
                || guest.bag.getItem(i).is(ModItems.GOLD_COIN.get()), "ale_unexpected_guest_cargo");
            coins += count(guest.bag, ModItems.GOLD_COIN.get());
            if (guest.hasMeal()) {
                require(s.aleStage == 2 && guest.hasTavernSeat(), "ale_meal_requires_real_seat");
                if (s.aleMealBegan.add(guest.getUUID())) s.aleHunger.put(guest.getUUID(), guest.getHunger());
                bread += count(guest.mealDisplayCopy(), Items.BREAD);
            } else if (s.aleMealBegan.contains(guest.getUUID()) && !s.aleMealFinished.contains(guest.getUUID())) {
                require(guest.getHunger() >= s.aleHunger.get(guest.getUUID()) + 35, "ale_bread_nutrition_missing");
                s.aleMealFinished.add(guest.getUUID());
            }
        }
        require(s.host.bag.isEmpty(), "ale_unexpected_host_cargo");
        Set<UUID> paymentsNow = new HashSet<>();
        var servings = level.getEntitiesOfClass(TavernServingEntity.class, AREA,
            e -> s.village.id.equals(e.settlementId()));
        for (TavernServingEntity serving : servings) {
            UUID id=serving.getUUID(), guestId=serving.guestId();
            require(s.host.getUUID().equals(serving.hostId()) && STOCK.equals(serving.source())
                && s.guests.stream().anyMatch(g -> g.getUUID().equals(guestId)), "ale_serving_identity_changed");
            if (!serving.displayAle().isEmpty() && s.alePoured.add(id)) {
                require(s.aleStage == 2 && ALE_TAP.equals(serving.tapSource()) && ALE_BARREL.equals(serving.tapBarrel())
                    && serving.phase() == TavernServingEntity.Phase.CARRYING
                    && com.hearthstead.settlement.work.AleTapService.hostContact(s.host,s.tavern,ALE_TAP)
                    && !s.aleGuests.containsValue(guestId), "fresh_tap_contact_or_unique_guest_missing");
                s.aleGuests.put(id,guestId);
                Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} pour={} serving={} guest={} contact=true",s.id,s.alePoured.size(),id,guestId);
            }
            if (!serving.displayPayment().isEmpty()) {
                paymentsNow.add(id);
                if (s.alePaid.add(id)) {
                    SettlerEntity guest = s.guests.stream().filter(g -> g.getUUID().equals(guestId)).findFirst().orElseThrow();
                    require(s.alePoured.contains(id) && serving.phase() == TavernServingEntity.Phase.DRINKING
                        && serving.drinkingTicks() == 8 && serving.displayAle().isEmpty()
                        && serving.displayPayment().getCount() == 1 && count(guest.bag,ModItems.GOLD_COIN.get()) == 0
                        && guest.hasTavernSeat(), "exact_one_coin_at_actual_sip8_required");
                    Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} sip={} serving={} guest={} drinkTicks=8 coin=1",s.id,s.alePaid.size(),id,guestId);
                }
            }
            if (s.previousPayment.contains(id) && serving.displayPayment().isEmpty()) {
                require(serving.phase() == TavernServingEntity.Phase.RETURNING
                    && com.hearthstead.settlement.work.AleTapService.hostContact(s.host,s.tavern,ALE_TAP)
                    && income == s.lastIncome + 1 && s.aleReturned.add(id), "paid_return_requires_exact_original_tap_contact");
                Hearthstead.LOGGER.info("HSQA_TAVERN_ALE session={} paidReturn={} serving={} barrelCoins={} contact=true",s.id,s.aleReturned.size(),id,income);
            }
            ale += count(serving.displayAle(),ModItems.ALE.get());
            coins += count(serving.displayPayment(),ModItems.GOLD_COIN.get());
            bread += count(serving.displayFood(),Items.BREAD); glass += count(serving.displayGlass(),Items.GLASS_BOTTLE);
        }
        require(income == s.aleReturned.size(), "unobserved_barrel_income");
        s.lastIncome=income; s.previousPayment.clear(); s.previousPayment.addAll(paymentsNow);
        require(count(stock,ModItems.ALE.get()) == 0 && count(stock,ModItems.GOLD_COIN.get()) == 0
            && count(stock,Items.WHEAT) == 0, "tap_cargo_redirected_to_food_store");
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, AREA)) require(
            count(drop.getItem(),Items.BREAD)+count(drop.getItem(),Items.GLASS_BOTTLE)+count(drop.getItem(),Items.WHEAT)
                +count(drop.getItem(),ModItems.ALE.get())+count(drop.getItem(),ModItems.GOLD_COIN.get()) == 0,
            "ale_proof_cargo_dropped");
        require(wheat + 3 * (ale + s.alePaid.size()) == 6 && coins == 2 && glass == 1,
            "ale_wheat_coin_glass_conservation_failed");
        require(bread + s.aleMealFinished.size() == (s.aleStage == 1 ? 0 : 2), "ale_bread_conservation_failed");
        if (s.aleStage == 2 && s.brewed == 2 && s.alePoured.size() == 2 && s.alePaid.size() == 2
            && s.aleReturned.size() == 2 && s.aleMealFinished.size() == 2 && servings.isEmpty()
            && !TavernHostService.hasSession(s.host) && count(stock,Items.GLASS_BOTTLE) == 1) s.aleStage=3;
        if (report || level.getGameTime()%20==0) Hearthstead.LOGGER.info(
            "HSQA_TAVERN_ALE session={} failed=false brewed={} poured={} sips={} paidReturns={} completed={} wheat={} ale={} coins={} bread={} glass={}",
            s.id,s.brewed,s.alePoured.size(),s.alePaid.size(),s.aleReturned.size(),s.aleStage==3,wheat,ale,coins,bread,glass);
    }
    private static int count(Container c, Item item) {
        int sum = 0;
        for (int i = 0; i < c.getContainerSize(); i++) sum += count(c.getItem(i), item);
        return sum;
    }
    private static int count(ItemStack stack, Item item) { return stack.is(item) ? stack.getCount() : 0; }
    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
