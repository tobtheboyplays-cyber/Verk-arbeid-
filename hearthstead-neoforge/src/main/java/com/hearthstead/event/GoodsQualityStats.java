package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.economy.QualityConfig;
import com.hearthstead.settlement.work.CraftedQuality;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/**
 * Quality lane: a graded weapon hits a little harder, graded armour is a
 * little tougher. Added live on top of the item's own modifiers (never
 * replacing them), under our own modifier ids, so vanilla's attack speed,
 * armour and base damage are untouched and the vanilla tooltip lists the
 * bonus as its own blue "+1 Attack Damage" line. Durability and food are
 * baked at craft time instead ({@link CraftedQuality#stamp}): there is no
 * per-stack hook for them.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GoodsQualityStats {
    static final ResourceLocation ATTACK_ID = Hearthstead.id("quality_attack");
    static final ResourceLocation TOUGHNESS_ID = Hearthstead.id("quality_toughness");

    private GoodsQualityStats() {
    }

    @SubscribeEvent
    public static void onAttributes(ItemAttributeModifierEvent event) {
        var stack = event.getItemStack();
        Integer raw = stack.get(ModComponents.GOODS_QUALITY.get());
        if (raw == null || !QualityConfig.statBonuses()) {
            return;
        }
        int quality = GoodsQuality.of(stack);
        if (quality <= GoodsQuality.BASIC) {
            return;
        }
        Item item = stack.getItem();
        if (isWeapon(item)) {
            double bonus = CraftedQuality.attackBonus(quality);
            if (bonus > 0.0D) {
                event.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(ATTACK_ID, bonus,
                    AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
            }
        } else if (item instanceof ArmorItem armor) {
            double bonus = CraftedQuality.toughnessBonus(quality);
            if (bonus > 0.0D) {
                EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(armor.getEquipmentSlot());
                event.addModifier(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(
                    TOUGHNESS_ID.withSuffix("_" + group.getSerializedName()), bonus,
                    AttributeModifier.Operation.ADD_VALUE), group);
            }
        }
    }

    /** Swords, axes, maces and tridents: the things a settler or player fights with. */
    public static boolean isWeapon(Item item) {
        return item instanceof SwordItem || item instanceof AxeItem
            || item instanceof MaceItem || item instanceof TridentItem;
    }
}
