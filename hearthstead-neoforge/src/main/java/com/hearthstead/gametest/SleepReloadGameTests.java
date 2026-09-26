package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Each case has its own named batch because it owns the server fixture clock. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SleepReloadGameTests {
    @GameTest(batch = "sleep_reload_energy", template = "empty16", timeoutTicks = 160)
    public void genuineSleeperReloadRestoresActivityAndEnergyWithoutMovingBedClaim(GameTestHelper h) {
        exerciseReload(h, ReloadCase.NIGHT);
    }

    @GameTest(batch = "sleep_reload_dawn", template = "empty16", timeoutTicks = 160)
    public void restedSleeperLoadedAfterDawnWakesWithoutLosingClaim(GameTestHelper h) {
        exerciseReload(h, ReloadCase.DAWN);
    }

    @GameTest(batch = "sleep_reload_combat", template = "empty16", timeoutTicks = 160)
    public void sleepingGuardLoadedIntoCombatWakesAndLandsRealHit(GameTestHelper h) {
        exerciseReload(h, ReloadCase.COMBAT);
    }

    private enum ReloadCase { NIGHT, DAWN, COMBAT }

    private void exerciseReload(GameTestHelper h, ReloadCase scenario) {
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            h.setBlock(new BlockPos(x,0,z), Blocks.STONE_BRICKS);
            for (int y=1;y<=3;y++) h.setBlock(new BlockPos(x,y,z), Blocks.AIR);
        }
        h.getLevel().setDayTime(18000);
        BlockPos headRel=new BlockPos(6,1,6), footRel=headRel.south();
        var bed=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH);
        h.setBlock(footRel,bed.setValue(BedBlock.PART,BedPart.FOOT));
        h.setBlock(headRel,bed.setValue(BedBlock.PART,BedPart.HEAD));
        BlockPos head=h.absolutePos(headRel), hearth=h.absolutePos(new BlockPos(2,1,2));
        h.setBlock(new BlockPos(2,1,2),com.hearthstead.registry.ModBlocks.HEARTH.get());
        Settlement village=new Settlement(UUID.randomUUID(), "Sleep reload fixture",hearth);
        SettlementSavedData.get(h.getLevel()).settlements.put(village.id,village);
        SettlementSavedData.get(h.getLevel()).setDirty();
        ((com.hearthstead.block.HearthBlockEntity)h.getLevel().getBlockEntity(hearth)).bindSettlement(village.id);
        SettlerEntity initial=h.spawn(ModEntities.SETTLER.get(),new BlockPos(7,1,6));
        initial.bindTo(village.id,hearth); village.putRecord(initial.getUUID(),"Reload sleeper",Profession.NONE);
        if (scenario == ReloadCase.COMBAT) {
            initial.assignProfession(Profession.GUARD);
            initial.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }
        initial.claimBed(head); initial.setHunger(100);
        initial.setEnergy(scenario == ReloadCase.DAWN ? 100 : scenario == ReloadCase.COMBAT ? 0 : 20);
        // Only the initial setup chooses a real nearby bed. Production goals
        // perform the actual approach and startSleeping; no NoAI or pose writes.
        SettlerEntity[] actor={initial};
        boolean[] reloaded={false}; float[] savedEnergy={0}; long[] loadTick={0};
        RaiderEntity[] threat={null}; float[] threatHealth={0};
        h.onEachTick(() -> {
            SettlerEntity current=actor[0];
            if (!reloaded[0]) {
                if (!current.isSleeping() || current.getActivity()!=SettlerActivity.SLEEPING
                    || current.getEnergy() <= (scenario == ReloadCase.COMBAT ? 1 : 21)) return;
                h.assertTrue(current.getSleepingPos().filter(head::equals).isPresent(),
                    "production rest goal must have entered the actual claimed bed");
                CompoundTag saved=new CompoundTag();
                h.assertTrue(current.save(saved),"genuine sleeper must serialize through Entity.save");
                UUID id=current.getUUID(); Vec3 position=current.position(); savedEnergy[0]=current.getEnergy();
                if (scenario == ReloadCase.DAWN) {
                    h.assertTrue(savedEnergy[0] >= 60,
                        "dawn fixture must save a fully rested sleeper, not trigger critical rest");
                    h.getLevel().setDayTime(1000);
                }
                current.discard();
                var loaded=EntityType.loadEntityRecursive(saved,h.getLevel(),entity -> entity);
                h.assertTrue(loaded instanceof SettlerEntity,"registered factory must restore a real settler");
                actor[0]=(SettlerEntity)loaded;
                h.assertTrue(actor[0].getUUID().equals(id) && actor[0].position().distanceToSqr(position)<1e-10
                    && head.equals(actor[0].getClaimedBed())
                    && actor[0].getSleepingPos().filter(head::equals).isPresent(),
                    "reload preserves the real UUID, saved position, bed claim and vanilla sleeping position");
                h.assertTrue(h.getLevel().addFreshEntity(actor[0]),"saved actor must rejoin the actual server level");
                if (scenario == ReloadCase.DAWN) {
                    h.assertTrue(new RestAtNightGoal(actor[0]).canUse(),
                        "rest must adopt saved vanilla sleep even when daylight and energy reject fresh rest");
                }
                if (scenario == ReloadCase.COMBAT) {
                    threat[0] = h.spawn(ModEntities.RAIDER.get(), new BlockPos(7,1,6));
                    threat[0].assign(UUID.randomUUID(), village.id, RaidObjective.BLOD, 1.0F, false);
                    // Keep the hostile in contact for a deterministic real Guard hit.
                    // The restored guard retains its full production goal selector.
                    threat[0].setNoAi(true);
                    threatHealth[0] = threat[0].getHealth();
                    actor[0].setTarget(threat[0]);
                }
                loadTick[0]=h.getLevel().getGameTime(); reloaded[0]=true;
                return; // Never set activity, energy, sleep state or position after reload.
            }
            long elapsed=h.getLevel().getGameTime()-loadTick[0];
            if (scenario != ReloadCase.NIGHT) {
                h.assertTrue(head.equals(current.getClaimedBed()),
                    "waking after reload must preserve the original bed claim");
                if (scenario == ReloadCase.DAWN && elapsed >= 40) {
                    h.assertTrue(!current.isSleeping() && current.getSleepingPos().isEmpty()
                            && current.getActivity() != SettlerActivity.SLEEPING,
                        "fully rested daytime reload must leave vanilla sleeping pose through ordinary goals");
                    h.assertTrue(!h.getLevel().getBlockState(head).getValue(BedBlock.OCCUPIED),
                        "real wake must release vanilla bed occupancy without releasing ownership");
                    h.succeed();
                } else if (scenario == ReloadCase.COMBAT && threat[0].getHealth() < threatHealth[0]) {
                    h.assertTrue(!current.isSleeping() && current.getSleepingPos().isEmpty()
                            && current.getActivity() == SettlerActivity.COMBAT,
                        "production Guard melee must wake the saved sleeper before its real hit");
                    h.assertTrue(!h.getLevel().getBlockState(head).getValue(BedBlock.OCCUPIED),
                        "combat wake must release vanilla bed occupancy");
                    h.succeed();
                }
                return;
            }
            h.assertTrue(current.isSleeping() && head.equals(current.getClaimedBed())
                && current.getSleepingPos().filter(head::equals).isPresent(),
                "reloaded resident must remain in the same actual bed while recovering");
            if (elapsed>=40) {
                h.assertTrue(current.getActivity()==SettlerActivity.SLEEPING
                    && current.getNavigation().isDone() && current.getEnergy()>savedEnergy[0]+1,
                    "ordinary post-load rest ticks must restore SLEEPING activity and actual energy, not idle drain");
                h.succeed();
            }
        });
    }
}
