package com.hearthstead.settlement.request;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Exact immutable description of one requested physical stack.
 *
 * <p>The prototype contains the item's complete serialized component patch at
 * count one. The digest is a compact index/telemetry token, while equality
 * and live matching still compare the full prototype. This object never owns
 * inventory and cannot materialize an item into the world.
 */
public final class RequestItemFingerprint {
    public static final int MAX_SERIALIZED_COMPONENT_CHARS = 8_192;
    public static final int MAX_COUNT = 64;

    private final ResourceLocation itemId;
    private final CompoundTag prototype;
    private final String digest;
    private final int count;

    private RequestItemFingerprint(ResourceLocation itemId,
                                   CompoundTag prototype,
                                   String digest, int count) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.prototype = Objects.requireNonNull(prototype, "prototype").copy();
        this.digest = Objects.requireNonNull(digest, "digest");
        this.count = count;
        String canonical = canonical(itemId, prototype);
        if (canonical.length() > MAX_SERIALIZED_COMPONENT_CHARS
            || count <= 0 || count > MAX_COUNT
            || digest.length() != 64 || !digest.equals(sha256(canonical))) {
            throw new IllegalArgumentException("invalid request item fingerprint");
        }
    }

    public static RequestItemFingerprint capture(
            HolderLookup.Provider registries, ItemStack stack, int count) {
        if (registries == null || stack == null || stack.isEmpty()
            || count <= 0 || count > stack.getCount()
            || count > Math.min(MAX_COUNT, stack.getMaxStackSize())) {
            throw new IllegalArgumentException("request fingerprint needs a real bounded stack");
        }
        ItemStack prototypeStack = stack.copyWithCount(1);
        Tag saved = prototypeStack.saveOptional(registries);
        if (!(saved instanceof CompoundTag prototype)) {
            throw new IllegalArgumentException("request fingerprint did not serialize as a compound");
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String canonical = canonical(itemId, prototype);
        return new RequestItemFingerprint(itemId, prototype,
            sha256(canonical), count);
    }

    /** Package-visible constructor for pure state-machine tests. */
    static RequestItemFingerprint synthetic(ResourceLocation itemId,
                                            String componentToken, int count) {
        CompoundTag prototype = new CompoundTag();
        prototype.putString("id", itemId.toString());
        prototype.putString("test_components", componentToken == null
            ? "none" : componentToken);
        String canonical = canonical(itemId, prototype);
        return new RequestItemFingerprint(itemId, prototype,
            sha256(canonical), count);
    }

    public ResourceLocation itemId() {
        return itemId;
    }

    public CompoundTag prototypeTag() {
        return prototype.copy();
    }

    public String digest() {
        return digest;
    }

    public int count() {
        return count;
    }

    public String stableKey() {
        return itemId + "@" + digest + "x" + count;
    }

    public ItemStack prototype(HolderLookup.Provider registries) {
        if (registries == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parseOptional(registries, prototype.copy());
    }

    public boolean matches(HolderLookup.Provider registries, ItemStack stack) {
        if (registries == null || stack == null || stack.isEmpty()
            || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
            return false;
        }
        ItemStack expected = prototype(registries);
        return !expected.isEmpty()
            && ItemStack.isSameItemSameComponents(expected, stack);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", itemId.toString());
        tag.put("Prototype", prototype.copy());
        tag.putString("Digest", digest);
        tag.putInt("Count", count);
        return tag;
    }

    @Nullable
    public static RequestItemFingerprint readNbt(CompoundTag tag,
                                                  HolderLookup.Provider registries) {
        if (tag == null || registries == null
            || !tag.contains("Item", Tag.TAG_STRING)
            || !(tag.get("Prototype") instanceof CompoundTag prototype)
            || !tag.contains("Digest", Tag.TAG_STRING)
            || !tag.contains("Count", Tag.TAG_INT)) {
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(tag.getString("Item"));
        if (itemId == null) {
            return null;
        }
        try {
            RequestItemFingerprint decoded = new RequestItemFingerprint(itemId,
                prototype, tag.getString("Digest"), tag.getInt("Count"));
            ItemStack parsed = decoded.prototype(registries);
            if (parsed.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(parsed.getItem()).equals(itemId)
                || decoded.count > parsed.getMaxStackSize()) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String canonical(ResourceLocation itemId, CompoundTag prototype) {
        // CompoundTag's backing map is not an ordering contract. The SNBT
        // printer sorts every compound key recursively when indentation is
        // empty, while retaining list order and scalar type suffixes. This
        // makes an equal component patch produce the same digest after a
        // save/restart instead of depending on hash-map iteration order.
        String canonicalNbt = new SnbtPrinterTagVisitor("", 0,
            new java.util.ArrayList<>()).visit(prototype);
        return itemId + "|" + canonicalNbt;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestItemFingerprint fingerprint
            && itemId.equals(fingerprint.itemId)
            && prototype.equals(fingerprint.prototype)
            && digest.equals(fingerprint.digest)
            && count == fingerprint.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, prototype, digest, count);
    }
}
