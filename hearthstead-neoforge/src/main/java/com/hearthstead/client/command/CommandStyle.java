package com.hearthstead.client.command;

import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Per-role colour, icon and short label for dots, chips and soldier icons. */
public final class CommandStyle {
    public static final int INVALID_RGB = 0xE0503C;

    /** Dot/chip colour (RGB) per role: steel, ash, amber, green, violet, white. */
    public static int rgb(Group role) {
        return switch (role) {
            case KNIGHTS, ALL -> 0xB9C6D6;
            case SPEARMEN -> 0xD6C28E;
            case LONGSWORDSMEN -> 0xE3A25C;
            case ARCHERS -> 0x9BD17A;
            case MAGES -> 0xB896EA;
            case HEALERS -> 0xF4F1E4;
        };
    }

    public static int argb(Group role) {
        return 0xFF000000 | rgb(role);
    }

    private static Item roleItem(Group role) {
        return switch (role) {
            case KNIGHTS, ALL -> Items.SHIELD;
            case SPEARMEN -> Items.TRIDENT;
            case LONGSWORDSMEN -> Items.DIAMOND_SWORD;
            case ARCHERS -> Items.BOW;
            case MAGES -> Items.AMETHYST_SHARD;
            case HEALERS -> Items.GOLDEN_APPLE;
        };
    }

    /** Icon for a role carrying out an order (order icon over the head, chip icon). */
    public static ItemStack icon(Group role, Kind kind) {
        Item item = switch (kind) {
            case ATTACK -> role.ranged() ? Items.SPECTRAL_ARROW : Items.IRON_SWORD;
            case HIGH_GROUND -> Items.LADDER;
            case FOLLOW -> Items.WHITE_BANNER;
            case RETURN -> Items.OAK_DOOR;
            case HOLD_FIRE -> Items.BARRIER;
            default -> roleItem(role);
        };
        return new ItemStack(item);
    }

    /** Role icon regardless of order (HUD chip lead icon). */
    public static ItemStack roleIcon(Group role) {
        return new ItemStack(roleItem(role));
    }

    public static Component groupName(Group group) {
        return Component.translatable("hearthstead.command.group." + group.id());
    }

    /** Short order label, e.g. "Hold the line", "Focus", "Hold fire". */
    public static Component shortLabel(Group group, Kind kind, boolean holdFire) {
        if (holdFire && (group == Group.ALL || group.ranged())) {
            return Component.translatable("hearthstead.command.short.hold_fire");
        }
        return Component.translatable("hearthstead.command.short." + FieldOrders.styleKey(group) + "." + kind.id());
    }

    private CommandStyle() {
    }
}
