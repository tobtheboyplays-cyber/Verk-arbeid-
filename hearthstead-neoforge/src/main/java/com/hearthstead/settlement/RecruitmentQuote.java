package com.hearthstead.settlement;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerAttributes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Immutable trip-bound effective price and actual starting aptitude basis. */
public record RecruitmentQuote(int version, UUID transactionId, UUID travelerId,
        int firstAttribute, int firstValue, int secondAttribute, int secondValue,
        int premium, int discountPercent, int bread, int planks, boolean legacyPending, int coins) {
    public RecruitmentQuote(int version, UUID transactionId, UUID travelerId, int firstAttribute, int firstValue,
            int secondAttribute, int secondValue, int premium, int discountPercent, int bread, int planks, boolean legacyPending) {
        this(version, transactionId, travelerId, firstAttribute, firstValue, secondAttribute, secondValue, premium,
            discountPercent, bread, planks, legacyPending, 0);
    }
    public RecruitmentQuote {
        if (transactionId == null || travelerId == null
                || transactionId.equals(new UUID(0, 0)) || travelerId.equals(new UUID(0, 0))
                || version < 0 || version > 2 || discountPercent < 0 || discountPercent > 50
                || premium < 0 || premium > 2) {
            throw new IllegalArgumentException("invalid recruitment quote identity/version");
        }
        if (version == 0) {
            if (firstAttribute != -1 || secondAttribute != -1 || firstValue != 0
                    || secondValue != 0 || premium != 0
                    || legacyPending && (discountPercent != 0 || bread != 4 || planks != 8)) {
                throw new IllegalArgumentException("invalid legacy recruitment quote");
            }
        } else if (legacyPending || firstAttribute < 0 || firstAttribute >= Attribute.COUNT
                || secondAttribute < 0 || secondAttribute >= Attribute.COUNT
                || firstAttribute == secondAttribute || firstValue < 1 || firstValue > 15
                || secondValue < 1 || secondValue > firstValue
                || premium != premiumFor(firstValue + secondValue)) {
            throw new IllegalArgumentException("invalid starting aptitude quote basis");
        }
        if (version == 2 ? (bread != 0 || planks != 0 || coins != Costs.discounted(4 + 2 * premium, discountPercent))
                : (coins != 0 || bread != Costs.discounted(4 + premium, discountPercent)
                || planks != Costs.discounted(8 + premium * 2, discountPercent))) {
            throw new IllegalArgumentException("quote effective cost contradicts basis");
        }
    }

    public static int premiumFor(int sum) { return sum >= 28 ? 2 : sum >= 24 ? 1 : 0; }

    public static RecruitmentQuote fromStartingAttributes(UUID transaction, UUID traveler,
            SettlerAttributes attributes, List<Costs.Discount> discounts) {
        int[] values = Arrays.stream(Attribute.ALL).mapToInt(attributes::get).toArray();
        return fromValues(transaction, traveler, values, Costs.discountPercent(discounts));
    }

    static RecruitmentQuote fromValues(UUID transaction, UUID traveler, int[] values, int discount) {
        if (values == null || values.length != Attribute.COUNT
                || Arrays.stream(values).anyMatch(value -> value < 1 || value > SettlerAttributes.START_CAP)) {
            throw new IllegalArgumentException("starting attributes outside actual newcomer bounds");
        }
        Integer[] order = new Integer[Attribute.COUNT];
        for (int index = 0; index < order.length; index++) order[index] = index;
        Arrays.sort(order, (a, b) -> values[a] == values[b]
            ? Integer.compare(a, b) : Integer.compare(values[b], values[a]));
        int premium = premiumFor(values[order[0]] + values[order[1]]);
        return new RecruitmentQuote(2, transaction, traveler, order[0], values[order[0]],
            order[1], values[order[1]], premium, discount,
            0, 0, false, Costs.discounted(4 + 2 * premium, discount));
    }

    static RecruitmentQuote legacyPending(UUID transaction, UUID traveler) {
        return new RecruitmentQuote(0, transaction, traveler, -1, 0, -1, 0, 0, 0, 4, 8, true);
    }

    RecruitmentQuote freezeLegacy(List<Costs.Discount> discounts) {
        if (!legacyPending) return this;
        int percent = Costs.discountPercent(discounts);
        return new RecruitmentQuote(0, transactionId, travelerId, -1, 0, -1, 0, 0, percent,
            Costs.discounted(4, percent), Costs.discounted(8, percent), false);
    }

    public Costs.Price price() {
        if (legacyPending) throw new IllegalStateException("legacy quote not yet frozen");
        if (version == 2) return Costs.coins(Costs.PriceKey.RECRUIT, coins);
        return new Costs.Price(Costs.PriceKey.RECRUIT, List.of(Costs.Line.of(Items.BREAD, bread),
            Costs.Line.ofTag(ItemTags.PLANKS, planks)));
    }

    boolean matches(UUID transaction, UUID traveler) {
        return transactionId.equals(transaction) && travelerId.equals(traveler);
    }

    CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", version); tag.putUUID("Transaction", transactionId);
        tag.putUUID("Traveler", travelerId); tag.putInt("FirstAttribute", firstAttribute);
        tag.putInt("FirstValue", firstValue); tag.putInt("SecondAttribute", secondAttribute);
        tag.putInt("SecondValue", secondValue); tag.putInt("Premium", premium);
        tag.putInt("DiscountPercent", discountPercent); tag.putInt("Bread", bread);
        tag.putInt("Planks", planks); if (version == 2) tag.putInt("Coins", coins); tag.putBoolean("LegacyPending", legacyPending);
        return tag;
    }

    static RecruitmentQuote readStrict(CompoundTag tag) {
        for (String key : List.of("Version", "FirstAttribute", "FirstValue", "SecondAttribute",
                "SecondValue", "Premium", "DiscountPercent", "Bread", "Planks")) {
            if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("missing quote " + key);
        }
        if (!tag.hasUUID("Transaction") || !tag.hasUUID("Traveler")
                || !tag.contains("LegacyPending", Tag.TAG_BYTE)
                || tag.getByte("LegacyPending") < 0 || tag.getByte("LegacyPending") > 1) {
            throw new IllegalArgumentException("invalid quote identity/pending flag");
        }
        if (tag.getInt("Version") == 2 && !tag.contains("Coins", Tag.TAG_INT)) throw new IllegalArgumentException("missing coin quote");
        if (tag.getInt("Version") < 2 && tag.contains("Coins")) throw new IllegalArgumentException("coin payload in legacy quote");
        return new RecruitmentQuote(tag.getInt("Version"), tag.getUUID("Transaction"),
            tag.getUUID("Traveler"), tag.getInt("FirstAttribute"), tag.getInt("FirstValue"),
            tag.getInt("SecondAttribute"), tag.getInt("SecondValue"), tag.getInt("Premium"),
            tag.getInt("DiscountPercent"), tag.getInt("Bread"), tag.getInt("Planks"),
            tag.getBoolean("LegacyPending"), tag.getInt("Version") == 2 ? tag.getInt("Coins") : 0);
    }
}
