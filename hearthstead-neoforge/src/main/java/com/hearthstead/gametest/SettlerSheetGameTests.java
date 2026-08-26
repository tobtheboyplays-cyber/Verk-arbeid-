package com.hearthstead.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-DATA-1 — the settler sheet, for every profession, without eyes.
 *
 * <p>{@code SettlerScreen} draws from exactly two sources: synced fields on
 * {@link SettlerEntity} and one {@link SettlerSnapshotPayload}. Nothing on
 * that screen is drawn from anything else. So everything about it except the
 * pixel layout is decidable headlessly: whether every field the screen reads
 * is populated, whether every translation key it asks for exists in both
 * shipped languages, and whether the appearance layer each profession needs
 * is actually on the classpath.
 *
 * <p>This file proves that for all {@value #EXPECTED_PROFESSIONS}
 * professions by <b>building the real payload through the real production
 * path</b> ({@code SettlerNetwork#snapshot}, reached reflectively because it
 * is private and its only public callers send it down a connection a
 * GameTest's mock player does not have) and round-tripping it through the
 * real {@link SettlerSnapshotPayload#CODEC}. What is left for a human is the
 * layout: whether the rows sit where they should and nothing overlaps.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SettlerSheetGameTests {

    private static final int EXPECTED_PROFESSIONS = 26;

    // ------------------------------------------------------------ fixtures --

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Sheetholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** The building whose trade is this profession, or null if none hires it. */
    private static BuildingType buildingFor(Profession profession) {
        for (BuildingType type : BuildingType.values()) {
            if (Employment.tradeOf(type) == profession) {
                return type;
            }
        }
        return null;
    }

    /** The exact payload the server would send for this settler and player. */
    private static SettlerSnapshotPayload snapshotOf(ServerPlayer player, SettlerEntity settler) {
        try {
            Method m = com.hearthstead.network.SettlerNetwork.class.getDeclaredMethod(
                "snapshot", ServerPlayer.class, SettlerEntity.class, Optional.class);
            m.setAccessible(true);
            return (SettlerSnapshotPayload) m.invoke(null, player, settler, Optional.empty());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SettlerNetwork#snapshot is not callable: " + e, e);
        }
    }

    private static SettlerSnapshotPayload throughTheWire(GameTestHelper helper,
                                                         SettlerSnapshotPayload snapshot) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        SettlerSnapshotPayload.CODEC.encode(buf, snapshot);
        return SettlerSnapshotPayload.CODEC.decode(buf);
    }

    private static JsonObject lang(String code) {
        String path = "/assets/hearthstead/lang/" + code + ".json";
        try (var in = SettlerSheetGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("shipped language file missing: " + path);
            }
            return JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path + ": " + e, e);
        }
    }

    // --------------------------------------------------------- the payload --

    /**
     * Every profession, employed at its own trade's building: the sheet's
     * server half is complete and sane, and survives the wire.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 400)
    public void everyProfessionFillsTheSettlerSheet(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(player.serverLevel() == helper.getLevel(),
            "setup: the mock player must be in the arena's own level");
        SettlerEntity settler = settler(helper, s, "Astrid", 8, 8);
        player.setPos(settler.getX(), settler.getY(), settler.getZ());

        helper.assertTrue(Profession.values().length == EXPECTED_PROFESSIONS,
            "this test is written to be exhaustive over " + EXPECTED_PROFESSIONS
                + " professions; the enum now has " + Profession.values().length
                + " -- extend the test, do not relax it");

        List<String> covered = new ArrayList<>();
        for (Profession profession : Profession.values()) {
            // NONE is the unemployed case: no building, no hire.
            Building employer = null;
            if (profession.employed()) {
                BuildingType type = buildingFor(profession);
                helper.assertTrue(type != null,
                    profession + " has no building that hires it, so a player can never "
                        + "see this profession's sheet at all");
                employer = GameTestFixtures.register(helper, s, type, 2, 2);
                Employment.Hired hired = Employment.hire(helper.getLevel(), s, employer, settler);
                helper.assertTrue(hired.ok(),
                    "setup: hiring a " + profession + " at " + type + " was refused: "
                        + (hired.refusal() == null ? "?" : hired.refusal().getString()));
            }

            SettlerSnapshotPayload snapshot = snapshotOf(player, settler);
            SettlerSnapshotPayload wire = throughTheWire(helper, snapshot);
            helper.assertTrue(snapshot.equals(wire),
                profession + ": the snapshot does not survive its own codec");

            // -- the header --
            helper.assertTrue(settler.getProfession() == profession,
                profession + ": the synced profession the header draws is "
                    + settler.getProfession());
            helper.assertTrue(!settler.getSettlerName().isBlank(),
                profession + ": the sheet title would be blank");
            helper.assertTrue(settler.getActivity() != null,
                profession + ": the activity line has no value");

            // -- needs --
            helper.assertTrue(settler.getHunger() >= 0.0F && settler.getHunger() <= 100.0F,
                profession + ": hunger out of the bar's range: " + settler.getHunger());
            helper.assertTrue(settler.getEnergy() >= 0.0F && settler.getEnergy() <= 100.0F,
                profession + ": energy out of the bar's range: " + settler.getEnergy());
            helper.assertTrue(settler.getMorale() >= 0.0F && settler.getMorale() <= 100.0F,
                profession + ": morale out of the bar's range: " + settler.getMorale());

            // -- attributes --
            helper.assertTrue(wire.attributeValues().size() == Attribute.COUNT,
                profession + ": the attribute block needs " + Attribute.COUNT
                    + " values, got " + wire.attributeValues().size());
            for (Attribute a : Attribute.ALL) {
                int v = wire.attributeValues().get(a.ordinal());
                helper.assertTrue(v >= 1 && v <= SettlerAttributes.CEILING,
                    profession + ": " + a + " is " + v + ", outside 1.."
                        + SettlerAttributes.CEILING);
            }
            helper.assertTrue(wire.knackOrdinal() >= 0 && wire.knackOrdinal() < Attribute.COUNT,
                profession + ": knack ordinal " + wire.knackOrdinal() + " names no attribute");

            // -- traits --
            helper.assertTrue(!wire.traitOrdinals().isEmpty(),
                profession + ": the trait row would be empty -- every settler has at least one");
            for (int ordinal : wire.traitOrdinals()) {
                helper.assertTrue(ordinal >= 0 && ordinal < Trait.ALL.length,
                    profession + ": trait ordinal " + ordinal + " names no trait");
            }

            // -- the mayoral boon (drawn from boonKey with no fallback) --
            helper.assertTrue(!wire.boonKey().isBlank(),
                profession + ": boonKey is empty, so the mayor badge would read "
                    + "'hearthstead.mayor.boon.'");
            boolean known = false;
            for (Mayor.Boon boon : Mayor.Boon.values()) {
                known |= boon.key().equals(wire.boonKey());
            }
            helper.assertTrue(known,
                profession + ": boonKey '" + wire.boonKey() + "' is not a Boon");

            // -- employment --
            if (profession.employed()) {
                helper.assertTrue(!wire.employerBuildingId().isEmpty(),
                    profession + ": employed, but the sheet would say unemployed");
                helper.assertTrue(
                    BuildingType.byId(wire.employerBuildingId()).id()
                        .equals(wire.employerBuildingId()),
                    profession + ": employer id '" + wire.employerBuildingId()
                        + "' resolves to no BuildingType, so the sheet names the wrong "
                        + "building");
                helper.assertTrue(wire.canManage(),
                    profession + ": canManage is false, so both buttons are dead");
            } else {
                helper.assertTrue(wire.employerBuildingId().isEmpty(),
                    "NONE: an unemployed settler must carry no employer");
            }

            if (employer != null) {
                Employment.dismiss(helper.getLevel(), s, settler);
                s.buildings.remove(employer);
            }
            covered.add(profession.key());
        }

        helper.assertTrue(covered.size() == EXPECTED_PROFESSIONS,
            "covered " + covered.size() + " professions, expected " + EXPECTED_PROFESSIONS);
        Hearthstead.LOGGER.info("UI-DATA-1 settler sheet payload verified for {} professions: {}",
            covered.size(), String.join(", ", covered));
        helper.succeed();
    }

    /**
     * A settler bound to no settlement — a traveler, or one summoned outside
     * any settlement — still fills every row the sheet draws. This is the
     * branch {@code SettlerNetwork#snapshot} takes when it cannot see a
     * settlement, and it is the one that sends empty strings on purpose.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 200)
    public void anUnboundSettlerStillFillsTheWholeSheet(GameTestHelper helper) {
        floor(helper, 16);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        SettlerEntity stray = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 8));
        stray.setSettlerName("Wanderer");
        player.setPos(stray.getX(), stray.getY(), stray.getZ());

        SettlerSnapshotPayload wire = throughTheWire(helper, snapshotOf(player, stray));
        helper.assertTrue(!wire.canManage(),
            "an unbound settler must not offer management");
        helper.assertTrue(wire.attributeValues().size() == Attribute.COUNT,
            "an unbound settler's attribute block is short: " + wire.attributeValues().size());
        for (int v : wire.attributeValues()) {
            helper.assertTrue(v >= 1, "an unbound settler has a zero attribute: " + v);
        }
        helper.assertTrue(!wire.traitOrdinals().isEmpty(),
            "an unbound settler's trait row would be empty");
        helper.assertTrue(!wire.boonKey().isBlank(),
            "an unbound settler carries no boon key, so a mayor badge would read "
                + "'hearthstead.mayor.boon.'");
        helper.assertTrue(wire.employerBuildingId().isEmpty(),
            "an unbound settler must carry no employer");
        helper.assertTrue(!stray.getSettlerName().isBlank(),
            "an unbound settler's sheet title would be blank");
        helper.succeed();
    }

    /**
     * The watch shift is real for archers, not only for guards.
     *
     * <p>{@code Employment#watchOf} is deliberately trade-agnostic — a
     * two-archer watchtower splits into a day and a night archer by exactly
     * the rule a barracks garrison uses — and the snapshot carries
     * {@code guardWatchNight} for them. This records that data existing, so
     * that {@code SettlerScreen#drawEmployment}'s
     * {@code getProfession() == Profession.GUARD} gate can be judged against
     * the facts rather than against intent.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 200)
    public void archersCarryARealWatchShiftLikeGuardsDo(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        SettlerEntity first = settler(helper, s, "Dag", 6, 6);
        SettlerEntity second = settler(helper, s, "Natt", 7, 6);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, first).ok(),
            "setup: the first archer was not hired");
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, second).ok(),
            "setup: the second archer was not hired");

        helper.assertTrue(first.getProfession() == Profession.ARCHER
                && second.getProfession() == Profession.ARCHER,
            "setup: a watchtower hires archers, got " + first.getProfession());
        helper.assertTrue(first.getProfession().martial(),
            "an archer is a martial trade");
        helper.assertTrue(first.getProfession() != Profession.GUARD,
            "an archer is not a GUARD, which is what SettlerScreen#drawEmployment tests");

        Employment.Watch a = Employment.watchOf(s, first);
        Employment.Watch b = Employment.watchOf(s, second);
        helper.assertTrue(a != b,
            "two archers in one tower must split the clock, got " + a + " and " + b);

        player.setPos(second.getX(), second.getY(), second.getZ());
        SettlerSnapshotPayload wire = throughTheWire(helper, snapshotOf(player, second));
        helper.assertTrue(wire.guardWatchNight() == (b == Employment.Watch.NIGHT),
            "the snapshot's guardWatchNight disagrees with Employment.watchOf for an archer");
        Hearthstead.LOGGER.info(
            "UI-DATA-1 archer watch: {} stands {}, {} stands {}; snapshot.guardWatchNight={} "
                + "-- SettlerScreen#drawEmployment only draws this row when profession==GUARD",
            first.getSettlerName(), a, second.getSettlerName(), b, wire.guardWatchNight());
        helper.succeed();
    }

    /**
     * Every translation key {@code SettlerScreen} can ask for exists, in both
     * shipped languages, and is not blank. Read out of the shipped resources
     * themselves, so this measures what ships rather than what the code hoped
     * for.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 200)
    public void everySettlerSheetStringIsTranslatedInBothLanguages(GameTestHelper helper) {
        JsonObject en = lang("en_us");
        JsonObject nb = lang("nb_no");
        List<String> keys = new ArrayList<>();

        for (Profession p : Profession.values()) {
            keys.add("hearthstead.profession." + p.key());
        }
        for (SettlerActivity a : SettlerActivity.values()) {
            keys.add("hearthstead.activity." + a.key());
        }
        for (Attribute a : Attribute.ALL) {
            keys.add("hearthstead.attribute." + a.key());
            keys.add("hearthstead.attribute." + a.key() + ".trained_by");
        }
        for (Trait t : Trait.ALL) {
            keys.add("hearthstead.trait." + t.key());
            keys.add("hearthstead.trait." + t.key() + ".desc");
        }
        for (Mayor.Boon b : Mayor.Boon.values()) {
            keys.add("hearthstead.mayor.boon." + b.key());
            keys.add("hearthstead.mayor.boon." + b.key() + ".desc");
        }
        for (BuildingType t : BuildingType.values()) {
            keys.add("hearthstead.building." + t.id());
        }
        // The screen's own fixed strings, in the order it draws them.
        keys.add("hearthstead.gui.doing");
        keys.add("hearthstead.gui.hunger");
        keys.add("hearthstead.gui.energy");
        keys.add("hearthstead.gui.morale");
        keys.add("hearthstead.settler.attribute_knack");
        keys.add("hearthstead.settler.loading");
        keys.add("hearthstead.settler.mayor_badge");
        keys.add("hearthstead.settler.mayor_settling");
        keys.add("hearthstead.settler.employed_at");
        keys.add("hearthstead.settler.employed_watch");
        keys.add("hearthstead.settler.watch_day");
        keys.add("hearthstead.settler.watch_night");
        keys.add("hearthstead.employ.unemployed");
        keys.add("hearthstead.employ.dismiss");
        keys.add("hearthstead.settler.dismiss.tip");
        keys.add("hearthstead.settler.close");
        keys.add("hearthstead.settler.appoint");
        keys.add("hearthstead.settler.appoint.tip");
        keys.add("hearthstead.settler.appoint.tip.no_settlement");
        keys.add("hearthstead.mayor.refused.already");
        keys.add("hearthstead.mayor.refused.mourning");
        // The four refusal sentences that can land in the sheet's banner.
        keys.add("hearthstead.settler.too_far");
        keys.add("hearthstead.settler.refused.no_settlement");
        keys.add("hearthstead.settler.stale");
        keys.add("hearthstead.settler.refused.no_job");

        List<String> broken = new ArrayList<>();
        for (String key : keys) {
            if (!en.has(key) || en.get(key).getAsString().isBlank()) {
                broken.add(key + " (en_us)");
            }
            if (!nb.has(key) || nb.get(key).getAsString().isBlank()) {
                broken.add(key + " (nb_no)");
            }
        }
        helper.assertTrue(broken.isEmpty(),
            "the settler sheet asks for strings that do not ship: " + broken);
        Hearthstead.LOGGER.info(
            "UI-DATA-1 settler sheet strings: {} keys present and non-blank in en_us and nb_no",
            keys.size());
        helper.succeed();
    }

    /**
     * The portrait. Every profession's outfit layer is on the classpath, so
     * {@code SettlerTextureCache} composes a real texture for all of them
     * rather than throwing and dropping to
     * {@code SettlerRenderer}'s three-case fallback.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 200)
    public void everyProfessionHasThePortraitLayersItNeeds(GameTestHelper helper) {
        String dir = "/assets/hearthstead/textures/entity/settler/";
        List<String> missing = new ArrayList<>();
        for (Profession p : Profession.values()) {
            if (SettlerSheetGameTests.class.getResource(dir + "layers/outfit_" + p.key() + ".png")
                == null) {
                missing.add("layers/outfit_" + p.key() + ".png");
            }
        }
        String[] skins = {"skin", "skin_tan", "skin_deep", "skin_pale"};
        String[] hairColors = {"hair_brn", "hair_blnd", "hair_blk", "hair_red"};
        for (String skin : skins) {
            if (SettlerSheetGameTests.class.getResource(dir + "layers/base_" + skin + ".png")
                == null) {
                missing.add("layers/base_" + skin + ".png");
            }
        }
        for (int style = 0; style < com.hearthstead.entity.SettlerAppearance.HAIR_STYLE_COUNT;
             style++) {
            for (String color : hairColors) {
                if (SettlerSheetGameTests.class.getResource(
                    dir + "layers/hair_" + style + "_" + color + ".png") == null) {
                    missing.add("layers/hair_" + style + "_" + color + ".png");
                }
            }
        }
        for (int face = 0; face < com.hearthstead.entity.SettlerAppearance.FACE_COUNT; face++) {
            if (SettlerSheetGameTests.class.getResource(dir + "layers/face_" + face + ".png")
                == null) {
                missing.add("layers/face_" + face + ".png");
            }
        }
        for (int c = 0; c < com.hearthstead.entity.SettlerAppearance.CLOTHING_COUNT; c++) {
            if (SettlerSheetGameTests.class.getResource(dir + "layers/clothing_" + c + ".png")
                == null) {
                missing.add("layers/clothing_" + c + ".png");
            }
        }
        helper.assertTrue(missing.isEmpty(),
            "the settler portrait cannot be composed; missing layers: " + missing);
        Hearthstead.LOGGER.info(
            "UI-DATA-1 portrait layers: every profession outfit plus all "
                + "{} appearance layers present",
            skins.length + com.hearthstead.entity.SettlerAppearance.HAIR_STYLE_COUNT
                * hairColors.length + com.hearthstead.entity.SettlerAppearance.FACE_COUNT
                + com.hearthstead.entity.SettlerAppearance.CLOTHING_COUNT);
        helper.succeed();
    }

    /**
     * How much the attribute block actually SAYS about a newcomer.
     *
     * <p>The sheet draws attributes only as five-of-five pips, with no number
     * anywhere, and {@code SettlerScreen} rounds {@code value / 20}. A
     * newcomer is capped at {@link SettlerAttributes#START_CAP} out of 100 by
     * design, so a value under 10 lights nothing at all. This does not assert
     * a threshold — it measures the roll and writes the number into the run
     * log, so how blank a fresh settler's sheet reads is a fact on the record
     * rather than a guess.
     */
    @GameTest(batch = "settlersheet", template = "empty16", timeoutTicks = 200)
    public void aFreshSettlersAttributePipsAreMeasured(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260826L);
        int sample = 2000;
        int allBlank = 0;
        int litTotal = 0;
        for (int i = 0; i < sample; i++) {
            SettlerAttributes a = SettlerAttributes.roll(random);
            int lit = 0;
            for (Attribute attribute : Attribute.ALL) {
                lit += a.pips(attribute) > 0 ? 1 : 0;
                helper.assertTrue(a.get(attribute) >= 1,
                    "a rolled attribute must never be zero, got " + a.get(attribute));
            }
            litTotal += lit;
            if (lit == 0) {
                allBlank++;
            }
        }
        Hearthstead.LOGGER.info(
            "UI-DATA-1 attribute pips over {} fresh rolls: {}% of settlers show ZERO lit pips "
                + "on all five rows; mean lit rows {}/5 (START_CAP={} of 100, one pip per 10+)",
            sample, Math.round(allBlank * 1000.0 / sample) / 10.0,
            Math.round(litTotal * 100.0 / sample) / 100.0, SettlerAttributes.START_CAP);
        helper.succeed();
    }
}
