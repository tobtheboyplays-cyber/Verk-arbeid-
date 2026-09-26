package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.PoopStickItem;
import com.hearthstead.item.TrollToenailItem;
import com.hearthstead.event.GoblinThiefDemo;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GoblinThiefDemoGameTests {
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=110, batch="goblin_loot_retaliation")
    public void defensiveGoblinPokeUsesRealRetaliationAndPlayerMelee(GameTestHelper helper) {
        for (int x = 0; x < 14; x++) for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos)).setItem(0,
            new ItemStack(ModItems.GOLD_COIN.get()));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.onUpdateAbilities();
        // ServerPlayer begins with sixty ticks of vanilla spawn immunity.
        // The player is registered in the server tick; wait that receipt out
        // before the actual player attack and Goblin contact are created.
        player.teleportTo(chestPos.getX() + 1.5D, chestPos.getY(), chestPos.getZ() + .5D);
        helper.runAfterDelay(65, () -> {
            player.teleportTo(chestPos.getX() + 1.5D, chestPos.getY(), chestPos.getZ() + .5D);
            var goblin = GoblinThiefDemo.spawn(helper.getLevel(), chestPos,
                helper.absolutePos(new BlockPos(12, 1, 3)));
            helper.assertTrue(goblin != null && goblin.getMainHandItem().is(ModItems.POOP_STICK.get()),
                "a tagged Goblin carries the stick before the real retaliation route begins");
            goblin.moveTo(player.getX() + 1.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
            BlockPos backstop = player.blockPosition().offset(2, 0, 0);
            helper.getLevel().setBlockAndUpdate(backstop, Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(backstop.above(), Blocks.STONE.defaultBlockState());
            player.attack(goblin);
            helper.assertTrue(!player.hasEffect(MobEffects.POISON),
                "being struck only starts a visible windup; poison is not applied immediately");
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(player.hasEffect(MobEffects.POISON)
                        && goblin.getPersistentData().getCompound("HearthsteadGoblinThiefDemo")
                            .getLong("DefensivePokeHandledHit") == 1L,
                    "a player-origin hit triggers one stationary, delayed landed Goblin poke; "
                        + pokeDiagnostic(goblin, player));
                var cow = helper.spawn(EntityType.COW, new BlockPos(5, 1, 3));
                player.teleportTo(cow.getX() - 1.0D, cow.getY(), cow.getZ());
                player.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(ModItems.POOP_STICK.get()));
                player.attack(cow);
                helper.assertTrue(cow.hasEffect(MobEffects.POISON),
                    "the recovered stick applies the same Poison through a real player melee hit");
                goblin.discard();
                helper.succeed();
            });
        });
    }

    // This fixture creates its own tagged thief only after the production 64-block singleton
    // radius is physically clear. Long neighboring Goblin routes share the GameTest world but
    // retain their own actors until their real route completes; neither actor is discarded here.
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=1100, batch="goblin_loot_line_of_sight")
    public void defensiveGoblinPokeRequiresLineOfSightAtContact(GameTestHelper helper) {
        for (int x = 0; x < 14; x++) for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos start = helper.absolutePos(new BlockPos(12, 1, 3));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos)).setItem(0,
            new ItemStack(ModItems.GOLD_COIN.get()));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.onUpdateAbilities();
        player.teleportTo(chestPos.getX() + 1.5D, chestPos.getY(), chestPos.getZ() + .5D);
        long eligibleAt = helper.getLevel().getGameTime() + 65L;
        boolean[] started = {false};
        com.hearthstead.entity.RaiderEntity[] goblin = {null};
        boolean[] barsPlaced = {false};
        long[] barsPlacedAt = {-1L};
        helper.onEachTick(() -> {
            if (started[0] || helper.getLevel().getGameTime() < eligibleAt
                    || hasLiveTaggedGoblin(helper, chestPos)) {
                if (!started[0]) return;
            } else {
                started[0] = true;
                player.teleportTo(chestPos.getX() + 3.9D, chestPos.getY(), chestPos.getZ() + .5D);
                goblin[0] = GoblinThiefDemo.spawn(helper.getLevel(), chestPos, start);
                helper.assertTrue(goblin[0] != null, "fixture creates the tagged Goblin after its exact singleton radius clears; "
                    + goblinSpawnDiagnostic(helper, chestPos, start));
                goblin[0].moveTo(player.getX() + .85D, player.getY(), player.getZ(), 0.0F, 0.0F);
                BlockPos backstop = player.blockPosition().offset(2, 0, 0);
                helper.getLevel().setBlockAndUpdate(backstop, Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(backstop.above(), Blocks.STONE.defaultBlockState());
                player.attack(goblin[0]);
            }
            if (goblin[0] == null) return;
            if (!barsPlaced[0] && goblin[0].getPersistentData().getCompound("HearthsteadGoblinThiefDemo")
                    .getLong("DefensivePokeHandledHit") == 1L) {
                BlockPos bars = player.blockPosition().offset(1, 0, 0);
                helper.getLevel().setBlockAndUpdate(bars, Blocks.IRON_BARS.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(bars.above(), Blocks.IRON_BARS.defaultBlockState());
                helper.assertTrue(goblin[0].isWithinMeleeAttackRange(player)
                        && !goblin[0].hasLineOfSight(player),
                    "the barrier removes sight without removing contact range; " + pokeDiagnostic(goblin[0], player));
                barsPlaced[0] = true;
                barsPlacedAt[0] = helper.getLevel().getGameTime();
            }
            if (barsPlaced[0] && helper.getLevel().getGameTime() - barsPlacedAt[0] >= 20L) {
                helper.assertTrue(barsPlaced[0] && !player.hasEffect(MobEffects.POISON)
                        && goblin[0].getPersistentData().getCompound("HearthsteadGoblinThiefDemo")
                            .getLong("DefensivePokeHandledHit") == 1L,
                    "thin bars block sight at an otherwise legal contact; " + pokeDiagnostic(goblin[0], player));
                goblin[0].discard();
                helper.succeed();
            }
        });
        helper.runAfterDelay(1080, () -> {
            if (!started[0]) helper.fail("fixture waited for the production singleton radius to clear; "
                + goblinSpawnDiagnostic(helper, chestPos, start));
        });
    }

    private static boolean hasLiveTaggedGoblin(GameTestHelper helper, BlockPos center) {
        return !helper.getLevel().getEntitiesOfClass(com.hearthstead.entity.RaiderEntity.class,
            new net.minecraft.world.phys.AABB(center).inflate(64), actor -> actor.isAlive()
                && actor.getPersistentData().contains("HearthsteadGoblinThiefDemo")).isEmpty();
    }

    @GameTest(template="empty64", skyAccess=true, timeoutTicks=110, batch="goblin_loot_invulnerable")
    public void defensiveGoblinPokeRequiresALandedHit(GameTestHelper helper) {
        for (int x = 0; x < 14; x++) for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos)).setItem(0,
            new ItemStack(ModItems.GOLD_COIN.get()));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();
        player.teleportTo(chestPos.getX() + 1.5D, chestPos.getY(), chestPos.getZ() + .5D);
        helper.runAfterDelay(65, () -> {
            player.teleportTo(chestPos.getX() + 1.5D, chestPos.getY(), chestPos.getZ() + .5D);
            var goblin = GoblinThiefDemo.spawn(helper.getLevel(), chestPos,
                helper.absolutePos(new BlockPos(12, 1, 3)));
            helper.assertTrue(goblin != null, "fixture creates the tagged Goblin");
            goblin.moveTo(player.getX() + 1.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
            player.attack(goblin);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(!player.hasEffect(MobEffects.POISON)
                        && goblin.getPersistentData().getCompound("HearthsteadGoblinThiefDemo")
                            .getLong("DefensivePokeHandledHit") == 1L,
                    "a real retaliation attempt with an explicitly invulnerable target does not apply poison");
                goblin.discard();
                helper.succeed();
            });
        });
    }

    @GameTest(template="empty16", timeoutTicks=20, batch="goblin_loot_items")
    public void trollToenailFinishingFoodAtFullHungerLeavesPlayerAtOneHealth(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.onUpdateAbilities();
        player.getFoodData().setFoodLevel(20);
        player.setHealth(player.getMaxHealth());
        TrollToenailItem toenail = (TrollToenailItem) ModItems.TROLL_TOENAIL.get();
        helper.assertTrue(new ItemStack(toenail).getFoodProperties(player).canAlwaysEat(),
            "the toenail remains usable even while the player is fully fed");
        ItemStack result = toenail.finishUsingItem(new ItemStack(toenail),
            helper.getLevel(), player);
        helper.assertTrue(result.isEmpty() && player.getHealth() == 1.0F
                && player.hasEffect(MobEffects.CONFUSION),
            "the always-edible toenail completes its normal food use, then leaves a living player "
                + "at exactly half a heart with Nausea");
        helper.succeed();
    }

    private static String pokeDiagnostic(com.hearthstead.entity.RaiderEntity goblin,
                                         ServerPlayer player) {
        CompoundTag state = goblin.getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
        return "handled=" + state.getLong("DefensivePokeHandledHit")
            + " hitSequence=" + state.getLong("DefensivePokeHitSequence")
            + " distanceSqr=" + goblin.distanceToSqr(player)
            + " melee=" + goblin.isWithinMeleeAttackRange(player)
            + " lineOfSight=" + goblin.hasLineOfSight(player)
            + " health=" + player.getHealth()
            + " poison=" + player.hasEffect(MobEffects.POISON);
    }

    /** Mirrors GoblinThiefDemo.spawn's fail-closed preconditions without
     * publishing a second actor. Kept in the fixture so a null spawn tells us
     * which production guard rejected its arena rather than guessing. */
    private static String goblinSpawnDiagnostic(GameTestHelper helper,
                                                BlockPos target,
                                                BlockPos start) {
        var level = helper.getLevel();
        var block = level.getBlockEntity(target);
        int coins = 0;
        StringBuilder contents = new StringBuilder("[");
        if (block instanceof net.minecraft.world.Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    if (contents.length() > 1) contents.append(',');
                    contents.append(slot).append(':')
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()))
                        .append('x').append(stack.getCount());
                }
                if (container.getItem(slot).is(ModItems.GOLD_COIN.get())) {
                    coins += container.getItem(slot).getCount();
                }
            }
        }
        contents.append(']');
        var taggedActors = level.getEntitiesOfClass(com.hearthstead.entity.RaiderEntity.class,
                new net.minecraft.world.phys.AABB(target).inflate(64),
                actor -> actor.isAlive()
                    && actor.getPersistentData().contains("HearthsteadGoblinThiefDemo"));
        StringBuilder tagged = new StringBuilder("[");
        for (var actor : taggedActors) {
            if (tagged.length() > 1) tagged.append(',');
            tagged.append(actor.getUUID()).append('@').append(actor.position());
        }
        tagged.append(']');
        var probe = ModEntities.RAIDER.get().create(level);
        boolean factory = probe != null;
        boolean collisionFree = false;
        if (probe != null) {
            var scale = probe.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
            if (scale != null) scale.setBaseValue(.65D);
            probe.moveTo(start.getX() + .5D, start.getY(), start.getZ() + .5D,
                0.0F, 0.0F);
            collisionFree = level.noCollision(probe);
        }
        return "sameThread=" + level.getServer().isSameThread()
            + " targetLoaded=" + level.hasChunkAt(target)
            + " startLoaded=" + level.hasChunkAt(start)
            + " distanceSqr=" + target.distSqr(start)
            + " border=" + level.getWorldBorder().isWithinBounds(start)
            + " container=" + (block instanceof net.minecraft.world.Container)
            + " coins=" + coins
            + " contents=" + contents
            + " taggedWithin64=" + taggedActors.size() + tagged
            + " factory=" + factory
            + " collisionFree=" + collisionFree
            + " fluidEmpty=" + level.getFluidState(start).isEmpty()
            + " sturdyFloor=" + level.getBlockState(start.below()).isFaceSturdy(
                level, start.below(), net.minecraft.core.Direction.UP)
            + " target=" + target + " start=" + start;
    }

    @GameTest(template="empty64", skyAccess=true, timeoutTicks=40, batch="goblin_loot_death")
    public void deathLootIsOptionalAndNeverDuplicatesTheEquippedPoopStick(GameTestHelper helper) {
        for (int x = 0; x < 14; x++) for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos)).setItem(0,
            new ItemStack(ModItems.GOLD_COIN.get()));
        var goblin = GoblinThiefDemo.spawn(helper.getLevel(), chestPos,
            helper.absolutePos(new BlockPos(12, 1, 3)));
        helper.assertTrue(goblin != null, "fixture creates one tagged Goblin");
        long noTrophySeed = 1L;
        while (true) {
            RandomSource rolls = RandomSource.create(noTrophySeed);
            if (rolls.nextFloat() >= .15F && rolls.nextFloat() >= .20F) break;
            noTrophySeed++;
        }
        // Custom trophies consume these first two values before the vanilla
        // equipment hook, so this fixes the rare branch to false without
        // weakening the production chance.
        goblin.getRandom().setSeed(noTrophySeed);
        goblin.hurt(helper.getLevel().damageSources().genericKill(), 10000.0F);
        helper.runAfterDelay(5, () -> {
            int sticks = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(chestPos).inflate(16.0D),
                item -> item.getItem().is(ModItems.POOP_STICK.get())).stream()
                .mapToInt(item -> item.getItem().getCount()).sum();
            int nails = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(chestPos).inflate(16.0D),
                item -> item.getItem().is(ModItems.TROLL_TOENAIL.get())).stream()
                .mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(sticks == 0 && nails == 0,
                "when both rare rolls are false, the equipped stick has no vanilla death-drop path");
            helper.succeed();
        });
    }

    @GameTest(template="empty64", skyAccess=true, timeoutTicks=60, batch="goblin_thief_demo")
    public void naturalRouteNeedsRegisteredCoinStoreAndKeepsOnePhysicalActor(GameTestHelper helper) {
        var level=helper.getLevel();
        for(int x=0;x<24;x++) for(int z=0;z<10;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(3,1,3));
        BlockPos start=helper.absolutePos(new BlockPos(15,1,3));
        level.setBlockAndUpdate(target,Blocks.CHEST.defaultBlockState());
        var chest=(ChestBlockEntity)level.getBlockEntity(target);
        chest.setItem(0,new ItemStack(ModItems.GOLD_COIN.get(),1));
        var village=new com.hearthstead.settlement.Settlement(java.util.UUID.randomUUID(),"Thief route fixture",target);
        var saved=com.hearthstead.settlement.SettlementSavedData.get(level);
        saved.settlements.put(village.id,village);
        helper.assertTrue(GoblinThiefDemo.spawnNatural(level,target,start,village.id)==null,
            "loose wilderness chest is not settlement theft authority");
        BlockPos plaquePos=target.offset(0,0,3);
        level.setBlockAndUpdate(plaquePos,com.hearthstead.registry.ModBlocks.PLAQUE.get().defaultBlockState());
        var building=new com.hearthstead.settlement.Building(java.util.UUID.randomUUID(),
            com.hearthstead.building.BuildingType.WAREHOUSE,plaquePos,target,
            new net.minecraft.world.level.levelgen.structure.BoundingBox(target.getX(),target.getY(),target.getZ(),
                plaquePos.getX()+1,plaquePos.getY()+2,plaquePos.getZ()));
        building.valid=true; village.buildings.add(building);
        var plaque=(com.hearthstead.block.PlaqueBlockEntity)level.getBlockEntity(plaquePos);
        CompoundTag tag=new CompoundTag(); tag.putString("Type",building.type.id());
        tag.putUUID("Building",building.id); tag.putUUID("Settlement",village.id);
        plaque.loadWithComponents(tag,level.registryAccess());
        var thief=GoblinThiefDemo.spawnNatural(level,target,start,village.id);
        helper.assertTrue(thief!=null,"one physical Coin in registered store enables a validated natural route");
        helper.assertTrue(thief.getNavigation().getPath()!=null && thief.getNavigation().getPath().canReach(),
            "natural visitor has full route before publication");
        helper.assertTrue(GoblinThiefDemo.spawnNatural(level,target,start,village.id)==null,
            "nearby live thief cannot be duplicated");
        helper.assertTrue(chest.getItem(0).getCount()==1 && thief.lootCount()==0,
            "route preparation never charges or invents currency");
        thief.discard(); saved.settlements.remove(village.id); saved.setDirty(); helper.succeed();
    }

    @GameTest(template="empty64", skyAccess=true, timeoutTicks=180, batch="goblin_thief_demo")
    public void telegraphedTheftConservesCoinsAndDeathReturnsLootOnce(GameTestHelper helper) {
        for (int x=0;x<20;x++) for (int z=0;z<8;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(3,1,3));
        helper.getLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity)helper.getLevel().getBlockEntity(target);
        chest.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 30));
        var thief = GoblinThiefDemo.spawn(helper.getLevel(), target,
            helper.absolutePos(new BlockPos(12,1,3)));
        helper.assertTrue(thief != null, "explicit demo spawns one thief");
        helper.assertTrue(thief.isGoblinThiefDemo() && thief.goblinThiefStage() == 0,
            "spawn synchronizes the goblin identity and sneaking presentation");
        helper.assertTrue(GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 0
            && chest.getItem(0).getCount() == 30, "no remote or instant theft");
        thief.moveTo(target.getX()+1.5,target.getY(),target.getZ()+.5,0,0);
        helper.runAfterDelay(85, () -> {
            helper.assertTrue(thief.isGoblinThiefDemo() && thief.goblinThiefStage() == 2,
                "theft synchronizes the fleeing presentation");
            helper.assertTrue(thief.lootCount() == 2 && chest.getItem(0).getCount() == 28,
                "three-second theft moves at most two real Coins into sole loot authority");
            helper.assertTrue(GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 0
                && chest.getItem(0).getCount() == 28, "same thief cannot repeat its transaction");
            var copy = ModEntities.RAIDER.get().create(helper.getLevel());
            copy.load(thief.saveWithoutId(new CompoundTag()));
            helper.assertTrue(copy.isGoblinThiefDemo() && copy.goblinThiefStage() == 2,
                "load restores presentation before the first server tick");
            helper.assertTrue(copy.lootCount() == 2
                && copy.getPersistentData().getCompound("HearthsteadGoblinThiefDemo").getInt("Stage") == 2,
                "saved cargo and flee stage survive reload");
            thief.hurt(helper.getLevel().damageSources().genericKill(), 10000);
            helper.runAfterDelay(5, () -> {
                int recovered = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(target).inflate(20),
                    item -> item.getItem().is(ModItems.GOLD_COIN.get())).stream()
                    .mapToInt(item -> item.getItem().getCount()).sum();
                helper.assertTrue(recovered == 2 && thief.lootCount() == 0
                    && chest.getItem(0).getCount() == 28, "death returns exact loot once, total Coins remains thirty");
                helper.succeed();
            });
        });
    }

    // The real spawn rule permits only one tagged thief in 64 blocks. Keep this
    // custody fixture out of the concurrent thief batch so it tests that rule,
    // rather than another fixture's actor.
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=120, batch="goblin_hit_spill_custody")
    public void hitSpillUsesPhysicalCustodyAndDeathReleasesOnlyRemainder(GameTestHelper helper) {
        for (int x=0;x<20;x++) for (int z=0;z<8;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(3,1,3));
        helper.getLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(target);
        chest.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 30));
        var thief = GoblinThiefDemo.spawn(helper.getLevel(), target,
            helper.absolutePos(new BlockPos(12,1,3)));
        helper.assertTrue(thief != null, "fixture creates one physical thief");
        thief.moveTo(target.getX()+1.5,target.getY(),target.getZ()+.5,0,0);
        helper.runAfterDelay(85, () -> {
            helper.assertTrue(thief.lootCount() == 2 && chest.getItem(0).getCount() == 28,
                "the completed telegraph leaves two physical Coins in thief custody");
            helper.assertTrue(GoblinThiefDemo.releaseOneCarriedCoinOnHit(helper.getLevel(), thief, false) == 0
                    && thief.lootCount() == 2,
                "a failed deterministic 50-percent roll cannot move or fabricate cargo");
            helper.assertTrue(GoblinThiefDemo.releaseOneCarriedCoinOnHit(helper.getLevel(), thief, true) == 1
                    && thief.lootCount() == 1,
                "a successful hit releases exactly one Coin after reducing carried custody");
            var saved = ModEntities.RAIDER.get().create(helper.getLevel());
            saved.load(thief.saveWithoutId(new CompoundTag()));
            helper.assertTrue(saved.lootCount() == 1 && saved.isGoblinThiefDemo()
                    && saved.goblinThiefStage() == 2,
                "save/reload retains only the unspilled cargo and fleeing presentation");
            thief.hurt(helper.getLevel().damageSources().genericKill(), 10000);
            helper.runAfterDelay(5, () -> {
                int dropped = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(target).inflate(20),
                    item -> item.getItem().is(ModItems.GOLD_COIN.get())).stream()
                    .mapToInt(item -> item.getItem().getCount()).sum();
                helper.assertTrue(dropped == 2 && thief.lootCount() == 0
                        && chest.getItem(0).getCount() == 28,
                    "one hit-spilled Coin plus the one death remainder conserves all thirty Coins");
                helper.succeed();
            });
        });
    }

    // A genuine unwatched departure cannot be asserted beside mock players from
    // the player-theft fixtures: their live presence may keep a thief visible.
    // Give this physical boundary test its own batch to establish that condition.
    // The deliberate low-speed escape must cover the real 24-block boundary;
    // the theft telegraph plus that physical route exceeds the ordinary 420 ticks.
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=900, batch="goblin_escape_custody")
    public void escapedThiefConsumesStolenCoinsRatherThanDroppingThemAtBoundary(GameTestHelper helper) {
        for (int x=0;x<48;x++) for (int z=0;z<12;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(3,1,5));
        // GameTest's shared mock player can outlive another fixture even when this
        // batch has one test. Park every existing mock beyond the live 48-block
        // watcher radius so this fixture proves the genuinely unwatched branch.
        for (var player : helper.getLevel().players()) {
            player.teleportTo(target.getX() + 96.5D, target.getY(), target.getZ() + .5D);
        }
        helper.getLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(target);
        chest.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 30));
        var thief = GoblinThiefDemo.spawn(helper.getLevel(), target,
            helper.absolutePos(new BlockPos(16,1,5)));
        helper.assertTrue(thief != null, "fixture creates one escaping thief");
        thief.moveTo(target.getX()+1.5,target.getY(),target.getZ()+.5,0,0);
        // If this ever times out again, preserve the real terminal state rather
        // than replacing a custody failure with an opaque timeout assertion.
        helper.runAfterDelay(898, () -> {
            if (!thief.isRemoved()) {
                CompoundTag state = thief.getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
                helper.fail("escape terminal stage=" + state.getInt("Stage")
                    + " age=" + (helper.getLevel().getGameTime() - state.getLong("StageStartedAt"))
                    + " loot=" + thief.lootCount()
                    + " targetDistance=" + thief.blockPosition().distSqr(target)
                    + " nearbyPlayers=" + helper.getLevel().players().stream()
                        .filter(player -> player.isAlive() && !player.isSpectator()
                            && player.distanceToSqr(thief) < 48 * 48).count());
            }
        });
        helper.succeedWhen(() -> {
            int visibleCoins = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(target).inflate(44),
                item -> item.getItem().is(ModItems.GOLD_COIN.get())).stream()
                .mapToInt(item -> item.getItem().getCount()).sum();
            int specialLoot = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(target).inflate(44),
                item -> item.getItem().is(ModItems.POOP_STICK.get())
                    || item.getItem().is(ModItems.TROLL_TOENAIL.get())).stream()
                .mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(thief.isRemoved() && chest.getItem(0).getCount() == 28
                    && visibleCoins == 0 && specialLoot == 0,
                "an unwatched escape consumes carried Coins and never invokes death-only trophy loot");
        });
    }

    // The manual spawn intentionally enforces one thief in a 64-block radius.
    // Run this custody fixture separately from the neighboring natural-thief test.
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=80, batch="goblin_player_custody")
    public void playerCoinTheftRequiresContactWindupAndReturnsLootOnce(GameTestHelper helper) {
        for (int x=0;x<20;x++) for (int z=0;z<8;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos chestPos = helper.absolutePos(new BlockPos(3,1,3));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 1));
        var player = helper.makeMockServerPlayerInLevel();
        // Keep the default-creative mock away while the explicit thief is
        // established. Only afterwards does this fixture make it survival
        // and bring it to the intended theft-contact point.
        BlockPos safePlayerPos = helper.absolutePos(new BlockPos(32,5,3));
        player.teleportTo(safePlayerPos.getX()+.5D, safePlayerPos.getY(), safePlayerPos.getZ()+.5D);
        var thief = GoblinThiefDemo.spawn(helper.getLevel(), chestPos,
            helper.absolutePos(new BlockPos(14,1,3)));
        helper.assertTrue(thief != null, "fixture creates one tagged thief");
        // GameTest mock players default to creative instabuild. The production
        // thief correctly refuses creative inventories, so make this a real
        // survival source before asking it to carry physical Coins.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.onUpdateAbilities();
        BlockPos playerPos = helper.absolutePos(new BlockPos(8,1,3));
        player.teleportTo(playerPos.getX()+.5D, playerPos.getY(), playerPos.getZ()+.5D);
        player.getInventory().setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 30));
        helper.assertTrue(GoblinThiefDemo.eligiblePickpocketVictim(player),
            "fixture must use a live survival Coin carrier; creative mock players are intentionally refused");
        CompoundTag state = thief.getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
        state.putUUID("PlayerTarget", player.getUUID());
        state.putInt("Stage", 1);
        state.putLong("ReadyAt", helper.getLevel().getGameTime());
        thief.moveTo(playerPos.getX()+8.5D, playerPos.getY(), playerPos.getZ()+.5D, 0, 0);
        helper.assertTrue(GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 0
                && player.getInventory().getItem(0).getCount() == 30,
            "player inventory cannot be stolen remotely even after a windup receipt");
        thief.moveTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        state.putLong("ReadyAt", helper.getLevel().getGameTime() + 60);
        helper.assertTrue(GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 0
                && player.getInventory().getItem(0).getCount() == 30 && thief.lootCount() == 0,
            "physical contact alone cannot bypass the pending windup");
        state.putLong("ReadyAt", helper.getLevel().getGameTime());
        helper.assertTrue(GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 2
                && player.getInventory().getItem(0).getCount() == 28
                && thief.lootCount() == 2 && chest.getItem(0).getCount() == 1,
            "physical player Coins move once into the thief's sole recoverable cargo");
        helper.assertTrue(state.getLong("NextPickpocketAt") >= helper.getLevel().getGameTime() + 600
                && GoblinThiefDemo.trySteal(helper.getLevel(), thief) == 0,
            "one thief receives a real cooldown and cannot repeat a player transaction");
        thief.hurt(helper.getLevel().damageSources().genericKill(), 10000);
        helper.runAfterDelay(5, () -> {
            int recovered = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(playerPos).inflate(20),
                item -> item.getItem().is(ModItems.GOLD_COIN.get())).stream()
                .mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(recovered == 2 && thief.lootCount() == 0
                    && player.getInventory().getItem(0).getCount() == 28,
                "death drops the exact player Coins once without duplication");
            helper.succeed();
        });
    }

    @GameTest(template="empty16", timeoutTicks=20, batch="goblin_thief_demo")
    public void creativePlayersAreNeverPickpocketSources(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 3));
        player.getAbilities().instabuild = true;
        helper.assertTrue(!GoblinThiefDemo.eligiblePickpocketVictim(player)
                && player.getInventory().getItem(0).getCount() == 3,
            "creative inventories are never a goblin Coin source");
        helper.succeed();
    }

    // Keep this long-lived actor out of concurrently executed short fixtures.
    // The separate batch ends only after this test's owned actor cleanup.
    @GameTest(template="empty64", skyAccess=true, timeoutTicks=900, batch="goblin_natural_player_route")
    public void naturalPlayerThiefWalksInWindsUpStealsAndFlees(GameTestHelper helper) {
        for (int x=0;x<48;x++) for (int z=0;z<12;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        var player = helper.makeMockServerPlayerInLevel();
        // See playerCoinTheftRequiresContactWindupAndReturnsLootOnce: this
        // GameTest helper is creative by default, while the real theft rule
        // must never select or debit a creative player.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.onUpdateAbilities();
        BlockPos playerPos = helper.absolutePos(new BlockPos(5,1,6));
        player.teleportTo(playerPos.getX()+.5D, playerPos.getY(), playerPos.getZ()+.5D);
        player.getInventory().setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 30));
        helper.assertTrue(GoblinThiefDemo.eligiblePickpocketVictim(player),
            "fixture must use a live survival Coin carrier; creative mock players are intentionally refused");
        BlockPos start = helper.absolutePos(new BlockPos(32,1,6));
        var thief = GoblinThiefDemo.spawnNaturalPlayer(helper.getLevel(), player, start,
            java.util.UUID.randomUUID());
        helper.assertTrue(thief != null && thief.blockPosition().distSqr(player.blockPosition()) >= 24 * 24,
            "natural player thief begins outside the village edge with a real route to the player");
        boolean[] sawWindup = {false};
        boolean[] sawTheft = {false};
        boolean[] sawFlee = {false};
        helper.onEachTick(() -> {
            int stage = thief.goblinThiefStage();
            if (stage == 1) sawWindup[0] = true;
            if (thief.lootCount() == 2) {
                sawTheft[0] = true;
                helper.assertTrue(sawWindup[0] && player.getInventory().getItem(0).getCount() == 28
                        && stage == 2,
                    "real AI must complete its visible windup before taking two physical Coins");
            }
            if (sawTheft[0] && (thief.getNavigation().getTargetPos() != null
                    || thief.distanceToSqr(player) > 4.0D)) {
                sawFlee[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(sawWindup[0] && sawTheft[0] && sawFlee[0],
                "natural player thief must approach, wind up, take the physical Coins, and begin fleeing");
            // This fixture has proved its complete route. Retire its exact
            // actor so the live 64-block singleton rule cannot affect a
            // later, independently-owned Goblin fixture.
            thief.discard();
        });
    }
}
