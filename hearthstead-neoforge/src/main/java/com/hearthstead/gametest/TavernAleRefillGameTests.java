package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.AleTapBlock;
import com.hearthstead.block.PlaqueBlockEntity;
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
import com.hearthstead.settlement.work.TavernAleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Physical refill authority and conservation; ordinary host walking is a separate acceptance. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernAleRefillGameTests {
    private static final BlockPos BARREL = new BlockPos(3, 1, 3);
    private static final BlockPos TAP = new BlockPos(4, 1, 3);
    private record Fixture(Settlement settlement, Building tavern, SettlerEntity host,
                           Container barrel, BlockPos source) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(6000);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Tap refill fixture",
            h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(settlement.id, settlement);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, settlement, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)),
                h.absolutePos(new BlockPos(12, 4, 12))));
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) h.getLevel().getBlockEntity(tavern.plaquePos);
        h.assertTrue(plaque != null, "fixture requires its actual linked Tavern plaque");
        CompoundTag plaqueTag = new CompoundTag();
        plaqueTag.putString("Type", BuildingType.TAVERN.id());
        plaqueTag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaqueTag.putUUID("Building", tavern.id);
        plaqueTag.putUUID("Settlement", settlement.id);
        plaque.loadCustomOnly(plaqueTag, h.getLevel().registryAccess());
        h.setBlock(BARREL, Blocks.BARREL);
        h.setBlock(TAP, ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(AleTapBlock.FACING, Direction.EAST));
        Container barrel = (Container) h.getBlockEntity(BARREL);
        SettlerEntity host = h.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 4));
        host.bindTo(settlement.id, settlement.center);
        settlement.putRecord(host.getUUID(), "Tap refill host", Profession.NONE);
        host.setNoAi(true);
        host.setHunger(100);
        host.setEnergy(100);
        host.move(MoverType.SELF, new Vec3(0, -.05, 0));
        h.assertTrue(host.onGround(), "host fixture needs physical floor contact");
        h.assertTrue(Employment.hire(h.getLevel(), settlement, tavern, host).ok(),
            "refill host must be employed through the real Tavern hiring path");
        return new Fixture(settlement, tavern, host, barrel, h.absolutePos(BARREL));
    }

    private static boolean brew(Fixture f) {
        return TavernAleService.brewAtContact(f.host(), f.tavern(), f.source(), f.barrel());
    }

    private static int count(Container c, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++)
            if (c.getItem(slot).is(item)) count += c.getItem(slot).getCount();
        return count;
    }

    private static void finish(GameTestHelper h, Fixture f) {
        f.host().discard();
        SettlementSavedData.get(h.getLevel()).settlements.remove(f.settlement().id);
        SettlementSavedData.get(h.getLevel()).setDirty();
        h.succeed();
    }

    @GameTest(batch = "tavern_ale_refill", template = "empty16", timeoutTicks = 240)
    public void connectedBarrelBrewsWithoutGuestsAndHonorsCooldown(GameTestHelper h) {
        Fixture f = fixture(h);
        f.barrel().setItem(0, new ItemStack(Items.WHEAT, 6));
        f.barrel().setItem(1, new ItemStack(Items.GLASS_BOTTLE, 2));
        h.assertTrue(f.source().equals(TavernAleService.findRefillSource(f.host(), f.tavern()))
                && count(f.barrel(), Items.WHEAT) == 6,
            "guest-independent selection must identify but not mutate the connected reserve");
        h.assertTrue(brew(f) && count(f.barrel(), Items.WHEAT) == 3
                && count(f.barrel(), ModItems.ALE.get()) == 1
                && count(f.barrel(), Items.GLASS_BOTTLE) == 2,
            "exactly three wheat become one real ALE without consuming reusable bottles");
        h.assertTrue(!brew(f) && TavernAleService.findRefillSource(f.host(), f.tavern()) == null,
            "the same host must not brew or claim refill movement during cooldown");
        h.runAfterDelay(199, () -> h.assertTrue(!brew(f)
                && count(f.barrel(), Items.WHEAT) == 3,
            "199 ticks cannot spend wheat before the 200-tick brew deadline"));
        h.runAfterDelay(200, () -> {
            h.assertTrue(brew(f) && count(f.barrel(), Items.WHEAT) == 0
                    && count(f.barrel(), ModItems.ALE.get()) == 2
                    && count(f.barrel(), Items.GLASS_BOTTLE) == 2,
                "deadline permits exactly one additional three-wheat brew, still without guests");
            finish(h, f);
        });
    }

    @GameTest(batch = "tavern_ale_refill", template = "empty16", timeoutTicks = 240)
    public void fullBarrelMergesOnlyCompatibleAleAndStopsAtEight(GameTestHelper h) {
        Fixture f = fixture(h);
        for (int slot = 0; slot < f.barrel().getContainerSize(); slot++)
            f.barrel().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        f.barrel().setItem(0, new ItemStack(Items.WHEAT, 6));
        f.barrel().setItem(1, new ItemStack(ModItems.ALE.get(), 7));
        h.assertTrue(brew(f) && f.barrel().getItem(0).getCount() == 3
                && f.barrel().getItem(1).getCount() == 8
                && f.barrel().getItem(2).getCount() == 64,
            "a full barrel may merge plain ALE without requiring an empty slot or touching other goods");
        h.runAfterDelay(200, () -> {
            h.assertTrue(!brew(f) && !TavernAleService.canRefillSource(f.host(), f.tavern(), f.source())
                    && count(f.barrel(), ModItems.ALE.get()) == TavernAleService.ALE_RESERVE
                    && count(f.barrel(), Items.WHEAT) == 3,
                "after cooldown expires the eight-unit reserve still prevents a ninth ALE");
            finish(h, f);
        });
    }

    @GameTest(batch = "tavern_ale_refill", template = "empty16", timeoutTicks = 40)
    public void blockedOutputPreservesWheatAndNamedAleButFreedWheatSlotWorks(GameTestHelper h) {
        Fixture f = fixture(h);
        for (int slot = 0; slot < f.barrel().getContainerSize(); slot++)
            f.barrel().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        f.barrel().setItem(0, new ItemStack(Items.WHEAT, 6));
        ItemStack named = new ItemStack(ModItems.ALE.get());
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep exact vintage"));
        f.barrel().setItem(1, named.copy());
        h.assertTrue(!brew(f) && count(f.barrel(), Items.WHEAT) == 6
                && ItemStack.matches(named, f.barrel().getItem(1)),
            "incompatible named ALE cannot be overwritten and full-output refusal must not debit wheat");
        f.barrel().setItem(0, new ItemStack(Items.WHEAT, 3));
        h.assertTrue(brew(f) && count(f.barrel(), Items.WHEAT) == 0
                && ItemStack.isSameItemSameComponents(f.barrel().getItem(0), new ItemStack(ModItems.ALE.get()))
                && ItemStack.matches(named, f.barrel().getItem(1)),
            "ordered three-wheat debit can free its own output slot; earlier refusal must not start cooldown");
        finish(h, f);
    }

    @GameTest(batch = "tavern_ale_refill", template = "empty16", timeoutTicks = 40)
    public void noTapRemoteHostAndForgedContainerCannotBrew(GameTestHelper h) {
        Fixture f = fixture(h);
        f.barrel().setItem(0, new ItemStack(Items.WHEAT, 3));
        SimpleContainer fake = new SimpleContainer(27);
        fake.setItem(0, new ItemStack(Items.WHEAT, 3));
        h.assertTrue(!TavernAleService.brewAtContact(f.host(), f.tavern(), f.source(), fake)
                && fake.getItem(0).getCount() == 3,
            "only the exact loaded barrel may own the transaction");
        Vec3 contact = f.host().position();
        f.host().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(10, 1, 10))));
        h.assertTrue(TavernAleService.canRefillSource(f.host(), f.tavern(), f.source()) && !brew(f),
            "read-only remote work selection is allowed but a remote host cannot prepare ALE");
        f.host().setPos(contact);
        h.setBlock(TAP, Blocks.AIR);
        h.assertTrue(!brew(f) && TavernAleService.findRefillSource(f.host(), f.tavern()) == null
                && count(f.barrel(), Items.WHEAT) == 3 && count(f.barrel(), ModItems.ALE.get()) == 0,
            "a disconnected barrel must not retain the old implicit brewery behavior");
        finish(h, f);
    }
}
