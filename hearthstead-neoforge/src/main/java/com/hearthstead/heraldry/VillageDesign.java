package com.hearthstead.heraldry;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A village's heraldry as the settlement flies it: field colour, pattern
 * layers (vanilla banner patterns by id, each with a dye colour) and the
 * cloth {@link BannerShape}. It is pure data. It is never an item and never
 * turns into one, so designing colours cannot create or destroy a banner
 * item; the physical banner a player hangs stays in the Banner's escrow
 * slot, exactly as before.
 *
 * <p>Guards and archers wear the field as their surcoat and {@link #trim()}
 * as its trim.
 */
public record VillageDesign(DyeColor base, List<Layer> layers, BannerShape shape) {
    /** Most layers a design may carry: the vanilla loom's limit. */
    public static final int MAX_LAYERS = 6;

    public record Layer(ResourceLocation pattern, DyeColor color) {
        public Layer {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(color, "color");
        }
    }

    /** Bannerhold's founding colours: red field, gold bordure and gold flower, plain cloth. */
    public static final VillageDesign FOUNDING = new VillageDesign(DyeColor.RED,
        List.of(new Layer(vanilla("border"), DyeColor.YELLOW), new Layer(vanilla("flower"), DyeColor.YELLOW)),
        BannerShape.STRAIGHT);

    public VillageDesign {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(shape, "shape");
        layers = List.copyOf(layers);
    }

    public static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    public VillageDesign withBase(DyeColor color) {
        return new VillageDesign(color, layers, shape);
    }

    public VillageDesign withShape(BannerShape next) {
        return new VillageDesign(base, layers, next);
    }

    public VillageDesign withLayers(List<Layer> next) {
        return new VillageDesign(base, next, shape);
    }

    /**
     * The surcoat trim: the first layer colour that differs from the field,
     * else gold (white on a gold field) so the trim always reads.
     */
    public DyeColor trim() {
        for (Layer layer : layers) {
            if (layer.color() != base) {
                return layer.color();
            }
        }
        return base == DyeColor.YELLOW ? DyeColor.WHITE : DyeColor.YELLOW;
    }

    // ------------------------------------------------------------ items ---

    /** What a physical banner flies, with {@code shape} kept (a vanilla banner has none). */
    public static VillageDesign fromBanner(ItemStack banner, BannerShape shape) {
        if (banner.isEmpty() || !(banner.getItem() instanceof BannerItem item)) {
            return FOUNDING.withShape(shape);
        }
        BannerPatternLayers patterns = banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        List<Layer> layers = new ArrayList<>();
        for (BannerPatternLayers.Layer layer : patterns.layers()) {
            layer.pattern().unwrapKey().ifPresent(key -> layers.add(new Layer(key.location(), layer.color())));
        }
        return new VillageDesign(item.getColor(), layers, shape);
    }

    /** Vanilla pattern layers for rendering; ids missing from the registry are skipped. */
    public BannerPatternLayers toPatternLayers(@Nullable HolderGetter<BannerPattern> lookup) {
        if (lookup == null || layers.isEmpty()) {
            return BannerPatternLayers.EMPTY;
        }
        List<BannerPatternLayers.Layer> out = new ArrayList<>(layers.size());
        for (Layer layer : layers) {
            lookup.get(ResourceKey.create(Registries.BANNER_PATTERN, layer.pattern()))
                .ifPresent(holder -> out.add(new BannerPatternLayers.Layer(holder, layer.color())));
        }
        return new BannerPatternLayers(out);
    }

    /** One layer's vanilla form, or null when the pattern is not registered. */
    @Nullable
    public static BannerPatternLayers.Layer resolve(HolderGetter<BannerPattern> lookup, Layer layer) {
        return lookup.get(ResourceKey.create(Registries.BANNER_PATTERN, layer.pattern()))
            .map(h -> new BannerPatternLayers.Layer((Holder<BannerPattern>) h, layer.color()))
            .orElse(null);
    }

    // ------------------------------------------------------- validation ---

    /** Why a submitted design was refused; NONE when it is legal. */
    public enum Problem { NONE, TOO_MANY_LAYERS, UNKNOWN_PATTERN, FIELD_PATTERN }

    /**
     * Server legality: at most {@link #MAX_LAYERS} layers, every pattern a
     * registered banner pattern (per {@code knownPattern}) and never the
     * whole-field "base" pattern (the field colour already is the base).
     * Colours and shape are typed, so a decoded design always has valid ones.
     */
    public static Problem validate(VillageDesign design, Predicate<ResourceLocation> knownPattern) {
        if (design.layers().size() > MAX_LAYERS) {
            return Problem.TOO_MANY_LAYERS;
        }
        for (Layer layer : design.layers()) {
            if (vanilla("base").equals(layer.pattern())) {
                return Problem.FIELD_PATTERN;
            }
            if (!knownPattern.test(layer.pattern())) {
                return Problem.UNKNOWN_PATTERN;
            }
        }
        return Problem.NONE;
    }

    // -------------------------------------------------------------- NBT ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Base", base.getSerializedName());
        tag.putString("Shape", shape.id());
        ListTag list = new ListTag();
        for (Layer layer : layers) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Pattern", layer.pattern().toString());
            entry.putString("Color", layer.color().getSerializedName());
            list.add(entry);
        }
        tag.put("Layers", list);
        return tag;
    }

    /**
     * Lenient load: an unknown colour falls back to the founding field (or
     * drops that layer), an unknown shape to the plain cloth, a malformed
     * pattern id drops its layer. Never throws, never returns null.
     */
    public static VillageDesign load(CompoundTag tag) {
        DyeColor base = DyeColor.byName(tag.getString("Base"), FOUNDING.base());
        BannerShape shape = BannerShape.byId(tag.getString("Shape"));
        List<Layer> layers = new ArrayList<>();
        ListTag list = tag.getList("Layers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && layers.size() < 16; i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation pattern = ResourceLocation.tryParse(entry.getString("Pattern"));
            DyeColor color = DyeColor.byName(entry.getString("Color"), null);
            if (pattern != null && color != null) {
                layers.add(new Layer(pattern, color));
            }
        }
        return new VillageDesign(base, layers, shape);
    }

    // ---------------------------------------------------------- network ---

    /**
     * Wire form. Decoding never throws on bad values: an out-of-range colour
     * or shape decodes to null fields that {@link #decodeChecked} refuses,
     * and at most 16 layers are read.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, VillageDesign> STREAM_CODEC = StreamCodec.of(
        (buf, d) -> {
            buf.writeByte(d.base().getId());
            buf.writeByte(d.shape().ordinal());
            buf.writeByte(d.layers().size());
            for (Layer layer : d.layers()) {
                buf.writeResourceLocation(layer.pattern());
                buf.writeByte(layer.color().getId());
            }
        },
        VillageDesign::decodeLenient);

    private static VillageDesign decodeLenient(RegistryFriendlyByteBuf buf) {
        int baseId = buf.readByte();
        int shapeId = buf.readByte();
        int count = buf.readByte();
        if (count < 0 || count > 16) {
            throw new IllegalArgumentException("design has " + count + " layers");
        }
        List<Layer> layers = new ArrayList<>(count);
        boolean bad = false;
        for (int i = 0; i < count; i++) {
            ResourceLocation pattern = buf.readResourceLocation();
            int colorId = buf.readByte();
            DyeColor color = dye(colorId);
            if (color == null) {
                bad = true;
            } else {
                layers.add(new Layer(pattern, color));
            }
        }
        DyeColor base = dye(baseId);
        BannerShape shape = BannerShape.byOrdinal(shapeId);
        if (bad || base == null || shape == null) {
            return INVALID;
        }
        return new VillageDesign(base, layers, shape);
    }

    /** Marker for a design that arrived with an out-of-range colour or shape. */
    public static final VillageDesign INVALID = new VillageDesign(DyeColor.BLACK,
        List.of(new Layer(vanilla("hearthstead_invalid"), DyeColor.BLACK)), BannerShape.STRAIGHT);

    @Nullable
    public static DyeColor dye(int id) {
        return id >= 0 && id < 16 ? DyeColor.byId(id) : null;
    }
}
