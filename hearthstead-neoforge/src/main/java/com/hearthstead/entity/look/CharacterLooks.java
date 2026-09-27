package com.hearthstead.entity.look;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * Common-side entry point for character looks and the voices that match
 * them. Everything here is pure and deterministic from state every client
 * already has (synced appearance seed, costume, UUID, variant, captain flag,
 * custom name), so each player sees -- and hears -- the same character, and
 * nothing here touches client classes (safe on the dedicated server).
 */
public final class CharacterLooks {
    public static final int COSTUME_NONE = 0;
    public static final int COSTUME_TRAVELLER = 1;
    public static final int COSTUME_REFUGEE = 2;
    public static final int COSTUME_MINSTREL = 3;
    public static final int COSTUME_ENVOY = 4;
    public static final int COSTUME_ATTENDANT = 5;
    /** Texture key per costume id (index 0 unused). */
    public static final String[] COSTUME_KEYS = {"", "traveller", "refugee", "minstrel", "envoy", "attendant",
        // Story lane (26 Sep): the named recurring visitors, ids 6..20, one variant each.
        "neighbour_wife", "neighbour_elder", "neighbour_child", "herald", "merchant", "bard",
        "newcomer_man", "newcomer_woman", "newcomer_child", "pilgrim", "healer", "veteran",
        "storyteller", "crow_herald", "bailiff"};
    /** Variants per costume id (mirrors tools/skins look_visitors.COSTUMES). */
    public static final int[] COSTUME_VARIANTS = {0, 3, 3, 3, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    /** Story costumes that wear a brimmed hat (the straw hat, the merchant's hat). */
    public static boolean costumeBrim(int costume) {
        return costume == 7 || costume == 10;
    }

    /** Story costumes that walk with the traveller's staff (the pilgrim, the storyteller). */
    public static boolean costumeStaff(int costume) {
        return costume == 15 || costume == 18;
    }

    public static final int SKIRMISHER_VARIANTS = 6;
    public static final int BRUTE_VARIANTS = 5;
    public static final int BANDIT_VARIANTS = 3;
    public static final int GOBLIN_VARIANTS = 5;
    public static final int PEDDLER_VARIANTS = 3;
    public static final int CARAVAN_VARIANTS = 2;
    public static final int MERCHANT_VARIANTS = 2;

    /** Captain colour schemes (index = CaptainLook.scheme); mirrors look_raiders.SCHEME_ORDER. */
    public static final String[] SCHEMES = {"plain", "ashen", "red", "torch", "grain", "chain", "reaper", "woad"};
    private static final String[][] EPITHET_WORDS = {
        {"ash", "ashen"}, {"grey", "ashen"}, {"gray", "ashen"}, {"soot", "ashen"},
        {"red", "red"}, {"blood", "red"}, {"crimson", "red"},
        {"torch", "torch"}, {"ember", "torch"}, {"burn", "torch"}, {"fire", "torch"}, {"flame", "torch"},
        {"grain", "grain"}, {"larder", "grain"}, {"harvest", "grain"},
        {"chain", "chain"}, {"ransom", "chain"}, {"iron", "chain"},
        {"reaper", "reaper"}, {"black", "reaper"}, {"crow", "reaper"},
        {"blue", "woad"}, {"woad", "woad"},
    };

    static final int RAIDER_SALT = 0x0BAD5EED;
    static final int COSTUME_SALT = 0x0C057E;
    static final int TRADER_SALT = 0x7EAD3E;
    static final int CAPTAIN_SALT = 0xCA97A1;
    static final int VOICE_SALT = 0x50CE;

    /**
     * Hero-captain check. The hero flag is client state owned by the battle
     * roles lane (CaptainClient); the client installs it here at startup so
     * common code can ask without touching client classes.
     */
    public static volatile Predicate<SettlerEntity> heroHook = e -> false;

    private CharacterLooks() {
    }

    /** [features] characterSkins; safe before the config loads (defaults on). */
    public static boolean enabled() {
        try {
            return com.hearthstead.HearthsteadServerConfig.characterSkinsEnabled();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    // -------------------------------------------------------------- settlers

    public static CharacterGenome genomeOf(SettlerEntity settler) {
        return CharacterGenome.decode(settler.getAppearanceSeed(),
            CharacterGenome.presentationOf(settler.getSettlerName()));
    }

    /** World-event role (WorldEventDirector tag) to costume id. */
    public static int costumeForRole(String role) {
        if (role == null) {
            return COSTUME_NONE;
        }
        return switch (role) {
            case "refugee", "refugee_leader" -> COSTUME_REFUGEE;
            case "minstrel", "minstrel_lead" -> COSTUME_MINSTREL;
            case "rival_envoy" -> COSTUME_ENVOY;
            case "envoy_attendant" -> COSTUME_ATTENDANT;
            default -> COSTUME_NONE;
        };
    }

    /** The costume actually worn: the synced event costume, else the traveller kit. */
    public static int costumeOf(SettlerEntity settler) {
        int c = settler.getLookCostume();
        if (c > 0 && c < COSTUME_KEYS.length) {
            return c;
        }
        return settler.hasTravelerAppearance() ? COSTUME_TRAVELLER : COSTUME_NONE;
    }

    public static int costumeVariant(UUID id, int costume) {
        if (costume <= 0 || costume >= COSTUME_VARIANTS.length) {
            return 0;
        }
        return new CharacterGenome.Stream(id.hashCode(), COSTUME_SALT + costume).pick(COSTUME_VARIANTS[costume]);
    }

    // --------------------------------------------------------------- raiders

    public static int raiderVariant(UUID id, int count) {
        return new CharacterGenome.Stream(id.hashCode(), RAIDER_SALT).pick(count);
    }

    /** Translation keys anywhere in a component tree (custom names are synced). */
    public static List<String> translationKeys(Component c) {
        List<String> out = new ArrayList<>();
        collectKeys(c, out, 0);
        return out;
    }

    private static void collectKeys(Component c, List<String> out, int depth) {
        if (c == null || depth > 6) {
            return;
        }
        if (c.getContents() instanceof TranslatableContents t) {
            out.add(t.getKey());
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a) {
                    collectKeys(a, out, depth + 1);
                }
            }
        }
        for (Component sib : c.getSiblings()) {
            collectKeys(sib, out, depth + 1);
        }
    }

    private static boolean hasKey(Entity e, String fragment) {
        Component name = e.getCustomName();
        if (name == null) {
            return false;
        }
        for (String k : translationKeys(name)) {
            if (k.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTollChief(RaiderEntity r) {
        return hasKey(r, "brute_toll");
    }

    public static boolean isRoadBandit(RaiderEntity r) {
        // Raid bandits (Variant.BANDIT, owner request 26 Sep) share the road-bandit look.
        return r.variant() == RaiderEntity.Variant.BANDIT || hasKey(r, "caravan.bandit");
    }

    /** A saga-named captain carries a literal display name ("Grimr the Torch, sworn to Kettil"). */
    public static String sagaName(RaiderEntity r) {
        Component name = r.getCustomName();
        if (name == null || !r.isCaptain()) {
            return null;
        }
        if (!translationKeys(name).isEmpty()) {
            return null;
        }
        String s = name.getString().strip();
        return s.isEmpty() ? null : s;
    }

    public static String firstName(String displayName) {
        String s = displayName.strip();
        int end = 0;
        while (end < s.length() && (Character.isLetter(s.charAt(end)) || s.charAt(end) == '\'')) {
            end++;
        }
        return s.substring(0, end);
    }

    public static String epithetOf(String displayName) {
        String s = displayName.strip();
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(0, comma);
        }
        String first = firstName(s);
        return s.substring(first.length()).strip();
    }

    public static int schemeFor(String epithet) {
        String e = epithet == null ? "" : epithet.toLowerCase(Locale.ROOT);
        for (String[] pair : EPITHET_WORDS) {
            if (e.contains(pair[0])) {
                return indexOf(SCHEMES, pair[1]);
            }
        }
        return 0;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    public static int crc32(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    /** Pure core, pinned against tools/skins by CharacterGenomeTest. */
    public static CaptainLook captainLook(int identitySeed, String epithet) {
        CharacterGenome.Stream s = new CharacterGenome.Stream(identitySeed, CAPTAIN_SALT);
        int helm = s.pick(CaptainLook.HELMS);
        int paint = 1 + s.pick(CaptainLook.PAINTS - 1);
        int scar = s.pick(5);
        int skin = s.pick(4);
        int hair = s.pick(9);
        return new CaptainLook(schemeFor(epithet), helm, paint, scar, skin, hair);
    }

    /** A captain's look: saga identity by first name, nameless captains by UUID. */
    public static CaptainLook captainLook(RaiderEntity r) {
        String saga = sagaName(r);
        if (saga != null) {
            String first = firstName(saga);
            if (!first.isEmpty()) {
                return captainLook(crc32(first), epithetOf(saga));
            }
        }
        return captainLook(r.getUUID().hashCode(), "");
    }

    // --------------------------------------------------------------- traders

    /** 0 peddler, 1 caravan master, 2 merchant (coin buyer / plain wandering trader). */
    public static int traderKind(Entity trader) {
        if (hasKey(trader, "event.caravan")) {
            return 1;
        }
        if (hasKey(trader, "event.peddler")) {
            return 0;
        }
        int pick = new CharacterGenome.Stream(trader.getUUID().hashCode(), TRADER_SALT)
            .pick(PEDDLER_VARIANTS + MERCHANT_VARIANTS);
        return pick < PEDDLER_VARIANTS ? 0 : 2;
    }

    public static int traderVariant(Entity trader, int kind) {
        int count = kind == 1 ? CARAVAN_VARIANTS : kind == 2 ? MERCHANT_VARIANTS : PEDDLER_VARIANTS;
        return new CharacterGenome.Stream(trader.getUUID().hashCode(), TRADER_SALT + 1 + kind).pick(count);
    }

    // ------------------------------------------------------------ archetypes

    public static Archetype archetypeOf(Entity e) {
        if (e instanceof SettlerEntity s) {
            if (heroHook.test(s)) {
                return Archetype.HERO_CAPTAIN;
            }
            return switch (costumeOf(s)) {
                case COSTUME_TRAVELLER -> Archetype.TRAVELLER;
                case COSTUME_REFUGEE -> Archetype.REFUGEE;
                case COSTUME_MINSTREL -> Archetype.MINSTREL;
                case COSTUME_ENVOY -> Archetype.ENVOY;
                case COSTUME_ATTENDANT -> Archetype.ENVOY_ATTENDANT;
                // Story lane: existing voice banks only (no new recordings).
                case 10 -> Archetype.MERCHANT;
                case 11 -> Archetype.MINSTREL;
                case 9, 20 -> Archetype.ENVOY;
                case 19 -> Archetype.ROAD_BANDIT;
                case 15 -> Archetype.TRAVELLER;
                default -> Archetype.SETTLER;
            };
        }
        if (e instanceof RaiderEntity r) {
            if (r.isGoblinThiefDemo()) {
                return Archetype.GOBLIN;
            }
            if (r.isCaptain()) {
                return sagaName(r) != null ? Archetype.SAGA_CAPTAIN : Archetype.RAID_CAPTAIN;
            }
            if (isTollChief(r)) {
                return Archetype.TOLL_CHIEF;
            }
            if (isRoadBandit(r)) {
                return Archetype.ROAD_BANDIT;
            }
            return r.variant() == RaiderEntity.Variant.BRUTE ? Archetype.BRUTE : Archetype.SKIRMISHER;
        }
        if (e instanceof WanderingTrader t) {
            return switch (traderKind(t)) {
                case 1 -> Archetype.CARAVAN_MASTER;
                case 2 -> Archetype.MERCHANT;
                default -> Archetype.PEDDLER;
            };
        }
        return Archetype.SETTLER;
    }

    /** The voice traits for any character; never null. */
    public static VoiceProfile voiceProfile(Entity e) {
        Archetype a = archetypeOf(e);
        if (e instanceof SettlerEntity s) {
            CharacterGenome g = genomeOf(s);
            return new VoiceProfile(a, g.sex(), g.age(), g.build(), s.getAppearanceSeed(), "");
        }
        int uuidSeed = e.getUUID().hashCode();
        CharacterGenome g = CharacterGenome.decode(uuidSeed, -1);
        return switch (a) {
            case GOBLIN -> new VoiceProfile(a, g.sex(), g.age(), 0, uuidSeed, "");
            case BRUTE, TOLL_BRUTE, TOLL_CHIEF -> new VoiceProfile(a, 0, Math.max(1, g.age()), 2, uuidSeed, "");
            case SAGA_CAPTAIN, RAID_CAPTAIN -> {
                RaiderEntity r = (RaiderEntity) e;
                CaptainLook look = captainLook(r);
                String saga = sagaName(r);
                int pitch = saga != null ? crc32(firstName(saga)) : uuidSeed;
                int build = r.variant() == RaiderEntity.Variant.BRUTE ? 2 : 1;
                yield new VoiceProfile(a, 0, 1, build, pitch, SCHEMES[look.scheme()]);
            }
            case SKIRMISHER, ROAD_BANDIT -> new VoiceProfile(a, g.sex(), Math.min(1, g.age()), g.build(), uuidSeed, "");
            default -> new VoiceProfile(a, g.sex(), g.age(), g.build(), uuidSeed, "");
        };
    }
}
