package com.hearthstead.client.ui2.map;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.render.SettlerTextureCache;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Employment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Icons the Banner screen reuses: one item per profession (the trade's own
 * tool, else the emblem of the building that teaches it), building emblems,
 * and settler face portraits cut from each settler's real composed skin.
 *
 * <p>Item icons are drawn at an integer number of physical pixels per
 * texel so they stay crisp at every GUI scale; the stacks are created once.
 */
public final class RealmIcons {
    private static final Map<Profession, ItemStack> PROFESSION = new EnumMap<>(Profession.class);
    private static final Map<String, ItemStack> BUILDING = new HashMap<>();
    private static final ItemStack FALLBACK_HEAD = new ItemStack(Items.PLAYER_HEAD);

    private RealmIcons() {
    }

    public static ItemStack profession(Profession profession) {
        return PROFESSION.computeIfAbsent(profession, RealmIcons::professionIcon);
    }

    public static ItemStack profession(int professionId) {
        return profession(Profession.byId(professionId));
    }

    /**
     * One recognisable item per trade, after the job icon style sheet: the
     * trade's product or signature tool (wheat, axe, pickaxe, fish, bread,
     * anvil...). Every stack is a vanilla item, so it renders crisp at the
     * same pixel density as the rest of Minecraft's UI.
     */
    private static ItemStack professionIcon(Profession profession) {
        return switch (profession) {
            case NONE -> ItemStack.EMPTY;
            case FARMER -> new ItemStack(Items.WHEAT);
            case LUMBERER -> new ItemStack(Items.IRON_AXE);
            case GUARD -> new ItemStack(Items.SHIELD);
            case COURIER -> new ItemStack(Items.CHEST);
            case BAKER -> new ItemStack(Items.BREAD);
            case COOK -> new ItemStack(Items.CAULDRON);
            case BUTCHER -> new ItemStack(Items.PORKCHOP);
            case SMELTER -> new ItemStack(Items.FURNACE);
            case SMITH -> new ItemStack(Items.ANVIL);
            case SAWYER -> new ItemStack(Items.OAK_PLANKS);
            case CARPENTER -> new ItemStack(Items.CRAFTING_TABLE);
            case MASON -> new ItemStack(Items.STONE_BRICKS);
            case FLETCHER -> new ItemStack(Items.ARROW);
            case WEAVER -> new ItemStack(Items.LOOM);
            case TANNER -> new ItemStack(Items.LEATHER);
            case MINER -> new ItemStack(Items.IRON_PICKAXE);
            case INNKEEPER -> new ItemStack(Items.HONEY_BOTTLE);
            case SCHOLAR -> new ItemStack(Items.BOOK);
            case MILLER -> new ItemStack(Items.HAY_BLOCK);
            case BREWER -> new ItemStack(Items.BARREL);
            case ARCHER -> new ItemStack(Items.BOW);
            case ARMOURER -> new ItemStack(Items.IRON_CHESTPLATE);
            case HERDER -> new ItemStack(Items.SHEARS);
            case FISHER -> new ItemStack(Items.COD);
            case HUNTER -> new ItemStack(Items.RABBIT_HIDE);
            case MAYOR -> new ItemStack(Items.GOLDEN_HELMET);
            case TRADER -> new ItemStack(Items.EMERALD);
            // Battle roles (plan/BATTLE-ROLES.md); vanilla stand-ins until the
            // role items are on every client.
            case SPEARMAN -> new ItemStack(Items.TRIDENT);
            case LONGSWORDSMAN -> new ItemStack(Items.IRON_SWORD);
            case HEALER -> new ItemStack(Items.GLISTERING_MELON_SLICE);
            case RUNE_MAGE -> new ItemStack(Items.AMETHYST_SHARD);
            // Builder lane: the scaffold pole reads as "construction" at map size.
            case BUILDER -> new ItemStack(Items.SCAFFOLDING);
            // New trades fall back to their requested tool or the emblem of the building that teaches them.
            default -> fallbackIcon(profession);
        };
    }

    private static ItemStack fallbackIcon(Profession profession) {
        ItemStack tool = profession.requestedTool();
        if (!tool.isEmpty()) return tool;
        for (BuildingType type : BuildingType.values()) {
            if (Employment.tradeOf(type) == profession) return new ItemStack(type.emblem());
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack building(String typeId) {
        return BUILDING.computeIfAbsent(typeId == null ? "" : typeId, id -> {
            BuildingType type = BuildingType.byId(id);
            return type == null ? new ItemStack(Items.OAK_DOOR) : new ItemStack(type.emblem());
        });
    }

    /** Physical pixels per item texel for a target GUI size (at least 1). */
    public static int itemScaleFor(float targetGuiPx, double guiScale) {
        return Math.max(1, (int) Math.floor(targetGuiPx * guiScale / 16.0D + 0.25D));
    }

    /** GUI size of an item drawn at {@code texelPx} physical pixels per texel. */
    public static float itemGuiSize(int texelPx, double guiScale) {
        return 16.0F * texelPx / (float) guiScale;
    }

    /**
     * Draws an item centred on (cx, cy) at an exact physical texel size.
     * The pose matrix is scaled in place and restored, so nothing allocates
     * beyond what vanilla item rendering does itself.
     */
    public static void itemCentered(GuiGraphics g, ItemStack stack, float cx, float cy, int texelPx,
                                    double guiScale, float z) {
        if (stack.isEmpty()) return;
        float size = itemGuiSize(texelPx, guiScale);
        float s = size / 16.0F;
        float x = snap(cx - size / 2.0F, guiScale);
        float y = snap(cy - size / 2.0F, guiScale);
        Matrix4f m = g.pose().last().pose();
        m.translate(x, y, z).scale(s, s, 1.0F);
        g.renderItem(stack, 0, 0);
        m.scale(1.0F / s, 1.0F / s, 1.0F).translate(-x, -y, -z);
    }

    public static float snap(float v, double guiScale) {
        return (float) (Math.round(v * guiScale) / guiScale);
    }

    /**
     * A settler's face (with hat/hair overlay) from their composed skin:
     * the live entity's texture when this client tracks them, otherwise the
     * same composition from the roster's appearance seed, otherwise a head.
     */
    public static void face(GuiGraphics g, UUID id, int entityId, int appearanceSeed, int professionId,
                            int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation texture = null;
        if (mc.level != null && entityId >= 0) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity instanceof SettlerEntity settler && settler.getUUID().equals(id)) {
                texture = mc.getEntityRenderDispatcher().getRenderer(settler).getTextureLocation(settler);
            }
        }
        if (texture == null && appearanceSeed >= 0) {
            // The skins lane's genome texture; the roster name keeps the presentation identical to the world.
            com.hearthstead.network.RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(id);
            texture = SettlerTextureCache.getOrCreateForSeed(appearanceSeed, Profession.byId(professionId),
                entry == null ? null : entry.name());
        }
        if (texture == null) {
            float scale = size / 16.0F;
            Matrix4f m = g.pose().last().pose();
            m.translate(x, y, 0).scale(scale, scale, 1.0F);
            g.renderItem(FALLBACK_HEAD, 0, 0);
            m.scale(1.0F / scale, 1.0F / scale, 1.0F).translate(-x, -y, 0);
            return;
        }
        g.blit(texture, x, y, size, size, 8.0F, 8.0F, 8, 8, 128, 64);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(texture, x, y, size, size, 40.0F, 8.0F, 8, 8, 128, 64);
    }
}
