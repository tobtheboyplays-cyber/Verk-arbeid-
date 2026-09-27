package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.InnkeeperAtmosphere;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Real registered host/player presence edges; fixture directly calls the live atmosphere API. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class InnkeeperAtmosphereGameTests {
    private record Fixture(Settlement settlement, Building tavern, SettlerEntity host,
                           ServerPlayer player, InnkeeperAtmosphere atmosphere) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Hospitality fixture",
            h.absolutePos(new BlockPos(2, 1, 2)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(12, 4, 12))));
        SettlerEntity host = h.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 6));
        host.bindTo(s.id, s.center); s.putRecord(host.getUUID(), "Host", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), s, tavern, host).ok(), "registered Innkeeper employment must succeed");
        host.setNoAi(true); // The live atmosphere API is driven below; no policy substitute.
        host.setHunger(100); host.setEnergy(100);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        place(h, player, false);
        h.assertTrue(h.getLevel().players().contains(player), "mock must be an actual registered level player");
        Fixture f = new Fixture(s, tavern, host, player, host.innkeeperAtmosphere());
        h.testInfo.addListener(new GameTestListener() {
            private boolean cleaned;
            private void cleanup() {
                if (cleaned) return;
                cleaned = true;
                f.atmosphere().stop(host);
                h.getLevel().getServer().getPlayerList().remove(player);
                host.discard();
                var data = SettlementSavedData.get(h.getLevel());
                if (data.settlements.get(s.id) == s) { data.settlements.remove(s.id); data.setDirty(); }
            }
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) { cleanup(); }
        });
        return f;
    }
    private static void place(GameTestHelper h, ServerPlayer player, boolean inside) {
        player.setPos(Vec3.atBottomCenterOf(h.absolutePos(inside
            ? new BlockPos(7, 1, 6) : new BlockPos(0, 1, 6))));
    }
    private static boolean tick(Fixture f, boolean busy) {
        return f.atmosphere().tick(f.host(), f.tavern(), busy);
    }

    @GameTest(batch = "innkeeper_atmosphere", template = "empty16", timeoutTicks = 50)
    public void moraleSmileRequiresRealGainAndDoesNotReplayPassiveDrift(GameTestHelper h) {
        // Use a freshly spawned actor so setup has no active happiness cue.
        SettlerEntity guest = h.spawn(ModEntities.SETTLER.get(), new BlockPos(9, 1, 9));
        guest.setNoAi(true);
        guest.addMorale(-100);
        h.assertTrue(guest.moraleJoyTicksRemaining() == 0, "loss must not show a happy cue");
        guest.addMorale(.5F);
        h.assertTrue(guest.moraleJoyTicksRemaining() == 0, "small passive drift must not spam happiness");
        float before = guest.getMorale();
        guest.addMorale(2);
        h.assertTrue(guest.getMorale() > before && guest.moraleJoyTicksRemaining() == 40,
            "actual meal-sized gain must synchronize one brief happy cue");
        h.runAfterDelay(10, () -> {
            int remaining = guest.moraleJoyTicksRemaining();
            guest.addMorale(2);
            h.assertTrue(remaining > 0 && remaining < 40 && guest.moraleJoyTicksRemaining() == remaining,
                "another gain during cooldown must not continuously reset the smile");
            guest.discard();
            h.succeed();
        });
    }

    @GameTest(batch = "innkeeper_atmosphere", template = "empty16", timeoutTicks = 120)
    public void realArrivalGreetsOnceAndSameGuestReentryRespectsCooldown(GameTestHelper h) {
        Fixture f = fixture(h);
        // First observation of an already-present player is not an arrival.
        place(h, f.player(), true);
        h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
            "first scan must not fabricate a welcome for a player already present");
        h.assertTrue(f.host().tavernMusicActive(), "real inside audience enables Tavern music without a fake greeting");
        place(h, f.player(), false);
        h.runAfterDelay(11, () -> {
            tick(f, false); // Sample the real exit before its next entry.
            place(h, f.player(), true);
            h.runAfterDelay(11, () -> {
                h.assertTrue(tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.WELCOME,
                    "new in-range visible entry must reserve hands and start the actual synced welcome");
                long started = f.host().innkeeperSocialStart();
                h.runAfterDelay(10, () -> {
                    h.assertTrue(tick(f, false) && f.host().innkeeperSocialStart() == started,
                        "continued presence cannot restart the welcome clock");
                });
                h.runAfterDelay(40, () -> {
                    h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                        "actual elapsed ticks must end welcome and release hands");
                    place(h, f.player(), false);
                    h.runAfterDelay(11, () -> {
                        tick(f, false);
                        place(h, f.player(), true);
                        h.runAfterDelay(11, () -> {
                            h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                                "same guest reentry inside the 1200-tick cooldown cannot wave again");
                            h.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(batch = "innkeeper_atmosphere", template = "empty16", timeoutTicks = 80)
    public void physicalHeldItemAndBusyServiceDeferPendingWelcome(GameTestHelper h) {
        Fixture f = fixture(h); tick(f, false);
        ItemStack carried = new ItemStack(Items.IRON_AXE);
        f.host().setItemInHand(InteractionHand.MAIN_HAND, carried);
        place(h, f.player(), true);
        h.runAfterDelay(11, () -> {
            h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE
                && f.host().getMainHandItem() == carried && carried.getCount() == 1,
                "physical occupied hand must defer gesture without moving or deleting its item");
            f.host().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            h.runAfterDelay(12, () -> {
                h.assertTrue(!tick(f, true) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                    "service ownership must defer gesture even with empty equipment slots");
                h.runAfterDelay(1, () -> {
                    h.assertTrue(tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.WELCOME
                        && f.host().getMainHandItem().isEmpty() && f.host().getOffhandItem().isEmpty()
                        && carried.getCount() == 1,
                        "free hands within the pending window may welcome without any inventory side effects");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(batch = "innkeeper_atmosphere", template = "empty16", timeoutTicks = 100)
    public void alarmClearsPendingAndActiveWelcomeWithoutPhantomArrival(GameTestHelper h) {
        Fixture f = fixture(h); tick(f, false);
        place(h, f.player(), true);
        h.runAfterDelay(11, () -> {
            h.assertTrue(!tick(f, true), "entry during service must remain pending");
            h.assertTrue(f.host().tavernMusicActive(), "occupied service hands must not silence the Tavern tune");
            f.settlement().alertUntilGameTime = h.getLevel().getGameTime() + 100;
            h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                "alarm cancels pending hospitality immediately");
            h.assertTrue(!f.host().tavernMusicActive(), "actual alarm must cancel music immediately too");
            f.settlement().alertUntilGameTime = 0;
            h.runAfterDelay(11, () -> {
                h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                    "resuming with unchanged presence cannot revive cancelled pending greeting");
                place(h, f.player(), false);
                h.runAfterDelay(11, () -> {
                    tick(f, false);
                    place(h, f.player(), true);
                    h.runAfterDelay(11, () -> {
                        h.assertTrue(tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.WELCOME,
                            "a later genuine arrival can start after the cancelled pending welcome");
                        f.settlement().alertUntilGameTime = h.getLevel().getGameTime() + 100;
                        h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                            "alarm also cancels an already active synced welcome");
                        h.succeed();
                    });
                });
            });
        });
    }
    @GameTest(batch = "innkeeper_atmosphere", template = "empty16", timeoutTicks = 80)
    public void doorwayOcclusionRetainsArrivalUntilVisibleContact(GameTestHelper h) {
        Fixture f = fixture(h); tick(f, false);
        h.setBlock(new BlockPos(7, 1, 6), Blocks.STONE_BRICKS);
        h.setBlock(new BlockPos(7, 2, 6), Blocks.STONE_BRICKS);
        f.player().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 1, 6))));
        h.assertTrue(f.tavern().contains(f.player().blockPosition())
            && !f.host().hasLineOfSight(f.player()), "real doorway wall must occlude this inside guest");
        h.runAfterDelay(11, () -> {
            h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                "actual entry behind wall must not wave through it");
            h.runAfterDelay(11, () -> {
                h.assertTrue(!tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.NONE,
                    "continued occlusion must retain intent without starting a gesture");
                h.setBlock(new BlockPos(7, 1, 6), Blocks.AIR);
                h.setBlock(new BlockPos(7, 2, 6), Blocks.AIR);
                h.assertTrue(f.host().hasLineOfSight(f.player()), "removing the actual wall must restore contact");
                h.assertTrue(tick(f, false) && f.host().innkeeperSocialMode() == InnkeeperAtmosphere.WELCOME,
                    "same already-inside guest must greet from retained arrival after contact clears, without another entry");
                h.succeed();
            });
        });
    }
}
