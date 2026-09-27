package com.hearthstead.settlement.work;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.util.CoinText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * TRADER lane (owner, 26 Sep: "the chat should say whether the Trader made a
 * sale"). One short line per trade round, never per item, through the shared
 * town chat ({@link com.hearthstead.settlement.TownChat}, kind TRADE: the
 * settlement's members only, the {@code [chat] trade} switch):
 * <pre>
 *   [Stonebridge] Aldric the Trader sold 12 Oak Logs for 3 Coins.
 *   [Stonebridge] Aldric the Trader found no buyer: the merchant moved on before the deal was done.
 * </pre>
 * A "no buyer" line goes out at most once per merchant visit per Trader, and
 * never after that visit already produced a sale.
 */
public final class TraderSaleNews {
    public static final String NO_SALE_KEY = "HearthsteadTraderNoSaleMerchant";

    /** Why a round ended without a sale. Each has its own lang line. */
    public enum NoSale {
        MERCHANT_LEFT("merchant_left"),
        DEAL_FELL_THROUGH("deal_fell_through"),
        NO_ROOM("no_room"),
        NOTHING_WANTED("nothing_wanted");

        public final String key;

        NoSale(String key) {
            this.key = key;
        }
    }

    /** GameTests may silence the lines; null = on (the [chat] trade switch belongs to TownChat). */
    @Nullable
    public static volatile Boolean testOverride;

    private TraderSaleNews() {
    }

    public static boolean enabled() {
        Boolean forced = testOverride;
        return forced == null || forced;
    }

    // ------------------------------------------------------------------ text (pure, JUnit)

    /** Words that take no plural "s" when they END an item name ("12 Wheat", "4 White Wool"). */
    private static final Set<String> MASS = Set.of("wheat", "wool", "glass", "leather", "gunpowder", "sugar",
        "bread", "coal", "charcoal", "clay", "dirt", "sand", "gravel", "beef", "mutton", "pork", "salmon",
        "cod", "kelp", "bamboo", "string", "paper", "snow", "stone", "cobblestone", "deepslate",
        "netherrack", "obsidian", "flint", "honey", "meal", "dye", "ice", "moss", "hay", "stew", "soup",
        "wax", "silk", "fish", "venison", "tallow", "flax", "linen", "cloth", "ale", "mead", "cheese",
        "butter", "dough", "flour", "grain", "barley", "rye", "oats", "hops", "straw", "timber", "iron",
        "gold", "copper", "bronze", "steel", "tin", "lead", "silver", "coin", "coins", "seeds");

    /** "Oak Log" x12 -> "12 Oak Logs"; "Wheat" x4 -> "4 Wheat"; one of anything stays singular. */
    public static String amount(int count, String name) {
        return count + " " + (count == 1 ? name : plural(name));
    }

    static String plural(String name) {
        if (name.isEmpty()) return name;
        int space = name.lastIndexOf(' ');
        String last = name.substring(space + 1);
        String lower = last.toLowerCase(Locale.ROOT);
        if (MASS.contains(lower) || lower.endsWith("s")) return name;
        String head = name.substring(0, space + 1);
        if (lower.endsWith("y") && last.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return head + last.substring(0, last.length() - 1) + "ies";
        }
        if (lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("x") || lower.endsWith("z")
            || lower.endsWith("ato")) {
            return head + last + "es";
        }
        return head + last + "s";
    }

    /** "a, b and c" (no Oxford comma), for the merchant's wish list. */
    public static String list(List<String> parts) {
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** Body of the sale line (TownChat adds the "[Town] " prefix). */
    public static MutableComponent soldBody(String trader, String goods, int coins) {
        return Component.translatable("hearthstead.chat.trade.sold", trader, goods, CoinText.coins(coins));
    }

    /** Body of a no-sale line; {@code detail} fills the reason's second argument (may be empty). */
    public static MutableComponent noSaleBody(String trader, NoSale why, String detail) {
        return Component.translatable("hearthstead.chat.trade.no_sale." + why.key, trader, detail);
    }

    // ------------------------------------------------------------------ delivery

    public static String traderName(Settlement settlement, SettlerEntity worker) {
        var record = settlement == null ? null : settlement.record(worker.getUUID());
        if (record != null && record.name != null && !record.name.isBlank()) return record.name;
        return worker.getName().getString();
    }

    /** One line for a committed sale. {@code sold} is the cost stack, {@code coinsReceived} what reached the bag. */
    public static void sold(ServerLevel level, Settlement settlement, SettlerEntity worker, @Nullable UUID merchant,
                            ItemStack sold, int coinsReceived) {
        // A visit that already produced a sale never gets a "no buyer" line afterwards.
        if (merchant != null) worker.getPersistentData().putUUID(NO_SALE_KEY, merchant);
        if (!enabled()) return;
        String goods = amount(sold.getCount(), sold.getHoverName().getString());
        send(level, settlement, soldBody(traderName(settlement, worker), goods, coinsReceived));
    }

    /** One line for a round that ended without a sale; at most once per merchant visit. */
    public static void noSale(ServerLevel level, Settlement settlement, SettlerEntity worker,
                              @Nullable UUID merchant, NoSale why, String detail) {
        var data = worker.getPersistentData();
        if (merchant != null) {
            if (data.hasUUID(NO_SALE_KEY) && merchant.equals(data.getUUID(NO_SALE_KEY))) return;
            data.putUUID(NO_SALE_KEY, merchant);
        }
        if (!enabled()) return;
        send(level, settlement, noSaleBody(traderName(settlement, worker), why, detail));
    }

    private static void send(ServerLevel level, Settlement settlement, MutableComponent body) {
        // One shared town-chat format (settler UI lane): "[Town] ..." in the TRADE colour, to the
        // settlement's members only, [chat] trade switch, identical same-tick lines deduped.
        com.hearthstead.settlement.TownChat.send(level, settlement, com.hearthstead.settlement.TownChat.Kind.TRADE, body);
    }
}
