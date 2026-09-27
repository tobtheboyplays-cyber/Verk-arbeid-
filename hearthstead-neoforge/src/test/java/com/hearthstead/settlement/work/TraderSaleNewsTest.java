package com.hearthstead.settlement.work;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TRADER lane: the sale chat line's text (one line per round; singular "1 Coin"). */
final class TraderSaleNewsTest {

    @Test
    void amountsReadLikeSpeech() {
        assertEquals("12 Oak Logs", TraderSaleNews.amount(12, "Oak Log"));
        assertEquals("1 Oak Log", TraderSaleNews.amount(1, "Oak Log"));
        assertEquals("4 Wheat", TraderSaleNews.amount(4, "Wheat"));
        assertEquals("16 White Wool", TraderSaleNews.amount(16, "White Wool"));
        assertEquals("8 Cobblestone", TraderSaleNews.amount(8, "Cobblestone"));
        assertEquals("6 Sweet Berries", TraderSaleNews.amount(6, "Sweet Berries"));
        assertEquals("3 Pumpkin Pies", TraderSaleNews.amount(3, "Pumpkin Pie"));
        assertEquals("5 Potatoes", TraderSaleNews.amount(5, "Potato"));
        assertEquals("2 Torches", TraderSaleNews.amount(2, "Torch"));
        assertEquals("9 Iron Ingots", TraderSaleNews.amount(9, "Iron Ingot"));
        assertEquals("20 Raw Iron", TraderSaleNews.amount(20, "Raw Iron"));
    }

    @Test
    void wishListJoinsWithAnd() {
        assertEquals("", TraderSaleNews.list(List.of()));
        assertEquals("Oak Log", TraderSaleNews.list(List.of("Oak Log")));
        assertEquals("Oak Log and Wheat", TraderSaleNews.list(List.of("Oak Log", "Wheat")));
        assertEquals("Oak Log, Wheat and Cobblestone", TraderSaleNews.list(List.of("Oak Log", "Wheat", "Cobblestone")));
    }

    @Test
    void soldLineCarriesNameGoodsAndCoinsWithSingular() {
        MutableComponent many = TraderSaleNews.soldBody("Aldric", "12 Oak Logs", 3);
        TranslatableContents a = (TranslatableContents) many.getContents();
        assertEquals("hearthstead.chat.trade.sold", a.getKey());
        assertArrayEquals(new Object[] {"Aldric", "12 Oak Logs", "3 Coins"}, a.getArgs());
        TranslatableContents one = (TranslatableContents) TraderSaleNews.soldBody("Aldric", "1 Oak Log", 1).getContents();
        assertEquals("1 Coin", one.getArgs()[2]);
    }

    @Test
    void everyNoSaleReasonHasItsOwnLine() {
        for (TraderSaleNews.NoSale why : TraderSaleNews.NoSale.values()) {
            TranslatableContents c = (TranslatableContents) TraderSaleNews.noSaleBody("Aldric", why, "Oak Log").getContents();
            assertEquals("hearthstead.chat.trade.no_sale." + why.key, c.getKey());
            assertEquals("Aldric", c.getArgs()[0]);
        }
        assertEquals(4, TraderSaleNews.NoSale.values().length);
    }

    @Test
    void chatLinesStayShortAndEnglishInTheLangFile() throws Exception {
        var lang = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(langFile())).getAsJsonObject();
        assertTrue(lang.has("hearthstead.chat.trade.sold"));
        for (TraderSaleNews.NoSale why : TraderSaleNews.NoSale.values()) {
            String key = "hearthstead.chat.trade.no_sale." + why.key;
            assertTrue(lang.has(key), key);
            assertTrue(lang.get(key).getAsString().length() <= 110, key + " is short");
        }
        assertTrue(lang.get("hearthstead.chat.trade.sold").getAsString().contains("%3$s"));
    }

    private static java.nio.file.Path langFile() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            for (java.nio.file.Path p : List.of(dir.resolve("src/main/resources/assets/hearthstead/lang/en_us.json"),
                dir.resolve("hearthstead-neoforge/src/main/resources/assets/hearthstead/lang/en_us.json"))) {
                if (java.nio.file.Files.isRegularFile(p)) return p;
            }
        }
        throw new IllegalStateException("en_us.json not found");
    }
}
