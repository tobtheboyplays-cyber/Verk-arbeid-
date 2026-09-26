package com.hearthstead.conversation;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;

/**
 * Who is speaking. {@code identity} is what relations remember: keep it
 * stable across visits (the returning peddler, a Saga captain's id).
 * {@code kind} is the reputation group ("peddler", "refugee", "minstrel",
 * "brute", "raider"). {@code name} is shown literally (a person's name);
 * {@code titleKey} is a lang key ("Brute Chieftain").
 */
public record SpeakerProfile(UUID identity, String name, String titleKey, String kind) {
    public static final int MAX_NAME = 48;

    public SpeakerProfile {
        if (identity == null) throw new IllegalArgumentException("speaker identity");
        name = name == null ? "" : name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        titleKey = titleKey == null ? "" : titleKey;
        kind = kind == null || kind.isBlank() ? "visitor" : kind;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", identity);
        tag.putString("Name", name);
        tag.putString("Title", titleKey);
        tag.putString("Kind", kind);
        return tag;
    }

    @Nullable
    public static SpeakerProfile read(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Id")) return null;
        return new SpeakerProfile(tag.getUUID("Id"), tag.getString("Name"), tag.getString("Title"),
            tag.getString("Kind"));
    }
}
