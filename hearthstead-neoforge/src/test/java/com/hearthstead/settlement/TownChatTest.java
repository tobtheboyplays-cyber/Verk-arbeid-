package com.hearthstead.settlement;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Town chat: prefix and colour, no-spam batching, config switches and member persistence. */
class TownChatTest {

    @AfterEach
    void clearOverrides() {
        for (TownChat.Kind kind : TownChat.Kind.values()) {
            TownChatConfig.overrideForTests(kind, null);
        }
    }

    @Test
    void lineIsTownPrefixThenBodyInTheKindColour() {
        Component line = TownChat.line("Oakvale", TownChat.Kind.RAID, Component.literal("Raiders!"));
        assertEquals(ChatFormatting.RED.getColor(), line.getStyle().getColor().getValue());
        Component prefix = line.getSiblings().get(0);
        TranslatableContents contents = (TranslatableContents) prefix.getContents();
        assertEquals("hearthstead.chat.prefix", contents.getKey());
        assertEquals("Oakvale", contents.getArgs()[0]);
        assertEquals("Raiders!", line.getSiblings().get(2).getString());
    }

    @Test
    void everyKindHasItsOwnColourFamily() {
        assertEquals(ChatFormatting.RED, TownChat.Kind.RAID.color());
        assertEquals(ChatFormatting.DARK_RED, TownChat.Kind.DEATH.color());
        assertEquals(ChatFormatting.GREEN, TownChat.Kind.BUILDING.color());
        assertEquals(ChatFormatting.GREEN, TownChat.Kind.UPGRADE.color());
        assertEquals(ChatFormatting.GOLD, TownChat.Kind.RESEARCH.color());
        assertEquals(ChatFormatting.AQUA, TownChat.Kind.TRADE.color());
    }

    @Test
    void theSameLineTwiceInOneTickGoesOutOnce() {
        TownChat.Batch batch = new TownChat.Batch();
        batch.add(TownChat.Kind.RAID, Component.translatable("hearthstead.message.raid_omen", "Oakvale"));
        batch.add(TownChat.Kind.RAID, Component.translatable("hearthstead.message.raid_omen", "Oakvale"));
        assertEquals(1, batch.render("Oakvale").size());
    }

    @Test
    void buildingsFinishedInTheSameTickBecomeOneLine() {
        TownChat.Batch batch = new TownChat.Batch();
        batch.add(TownChat.Kind.BUILDING, Component.literal("Cottage"));
        batch.add(TownChat.Kind.BUILDING, Component.literal("Well"));
        batch.add(TownChat.Kind.BUILDING, Component.literal("Smithy"));
        List<Component> lines = batch.render("Oakvale");
        assertEquals(1, lines.size());
        TranslatableContents body = (TranslatableContents) lines.get(0).getSiblings().get(2).getContents();
        assertEquals("hearthstead.chat.building.many", body.getKey());
        assertEquals("Cottage, Well, Smithy", ((Component) body.getArgs()[0]).getString());
    }

    @Test
    void aSingleBuildingOrUpgradeUsesTheOneKey() {
        TownChat.Batch batch = new TownChat.Batch();
        batch.add(TownChat.Kind.BUILDING, Component.literal("Cottage"));
        batch.add(TownChat.Kind.UPGRADE, Component.literal("Warehouse level 2"));
        List<Component> lines = batch.render("Oakvale");
        assertEquals(2, lines.size(), "building and upgrade are different kinds");
        assertEquals("hearthstead.chat.building.one",
            ((TranslatableContents) lines.get(0).getSiblings().get(2).getContents()).getKey());
        assertEquals("hearthstead.chat.upgrade.one",
            ((TranslatableContents) lines.get(1).getSiblings().get(2).getContents()).getKey());
    }

    @Test
    void differentTradeLinesAreNeverMerged() {
        TownChat.Batch batch = new TownChat.Batch();
        batch.add(TownChat.Kind.TRADE, Component.literal("Rolf sold 12 Oak Logs for 5 Coins"));
        batch.add(TownChat.Kind.TRADE, Component.literal("Ida found nothing wanted"));
        batch.add(TownChat.Kind.RESEARCH, Component.literal("New research: Cottages is learned."));
        List<Component> lines = batch.render("Oakvale");
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).getString().endsWith("Rolf sold 12 Oak Logs for 5 Coins"));
        assertTrue(lines.get(1).getString().endsWith("Ida found nothing wanted"));
    }

    @Test
    void everySwitchIsOnByDefaultAndCanBeTurnedOff() {
        for (TownChat.Kind kind : TownChat.Kind.values()) {
            assertTrue(TownChatConfig.enabled(kind), kind + " on by default");
        }
        TownChatConfig.overrideForTests(TownChat.Kind.DEATH, false);
        assertFalse(TownChatConfig.enabled(TownChat.Kind.DEATH));
        assertTrue(TownChatConfig.enabled(TownChat.Kind.RAID), "one switch per kind");
    }

    @Test
    void membersSurviveSaveAndLoad() {
        UUID alda = UUID.randomUUID();
        UUID bram = UUID.randomUUID();
        Settlement town = new Settlement(UUID.randomUUID(), "Oakvale", new BlockPos(0, 64, 0));
        assertTrue(town.addMember(alda));
        assertFalse(town.addMember(alda), "joining twice changes nothing");
        town.addMember(bram);
        Settlement loaded = Settlement.readNbt(town.writeNbt());
        assertEquals(List.of(alda, bram), List.copyOf(loaded.members));

        Settlement nobody = Settlement.readNbt(
            new Settlement(UUID.randomUUID(), "Old", new BlockPos(0, 64, 0)).writeNbt());
        assertTrue(nobody.members.isEmpty(), "an old save has no members and falls back to nearby players");
    }
}
