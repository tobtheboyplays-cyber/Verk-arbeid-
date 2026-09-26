package com.hearthstead.client.render;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueSheet;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaqueSheetCacheTest {

    @Test
    void unchangedRevisionReusesSheetAndDerivedGeometry() {
        AtomicInteger derives = new AtomicInteger();
        PlaqueSheetCache<Object, Integer> cache = new PlaqueSheetCache<>(sheet -> {
            derives.incrementAndGet();
            return sheet.lines().size();
        });
        Object plaque = new Object();

        PlaqueSheetCache.CachedSheet<Integer> first = get(cache, plaque, 7,
            "en_us", BuildingType.HOUSE, PlaqueState.LINKED_INCOMPLETE,
            survey(0), 0, 0);
        PlaqueSheetCache.CachedSheet<Integer> next = get(cache, plaque, 7,
            "en_us", BuildingType.HOUSE, PlaqueState.LINKED_INCOMPLETE,
            survey(0), 0, 0);

        assertSame(first, next,
            "a steady render must reuse both sheet and measured geometry");
        assertSame(first.sheet(), next.sheet());
        assertEquals(1, derives.get());
        assertEquals(1, cache.sizeForTest());
    }

    @Test
    void revisionLocaleAndEveryScalarInputInvalidateSynchronously() {
        PlaqueSheetCache<Object, PlaqueSheet> cache = cache();
        Object plaque = new Object();

        PlaqueSheetCache.CachedSheet<PlaqueSheet> sheet = get(cache, plaque, 1,
            "en_us", BuildingType.HOUSE, PlaqueState.LINKED_INCOMPLETE,
            survey(0), 0, 0);
        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedRevision = get(cache,
            plaque, 2, "en_us", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(1), 0, 0);
        assertNotSame(sheet, changedRevision);

        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedLanguage = get(cache,
            plaque, 2, "nb_no", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(1), 0, 0);
        assertNotSame(changedRevision, changedLanguage);

        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedType = get(cache,
            plaque, 2, "nb_no", BuildingType.LUMBER_CAMP,
            PlaqueState.LINKED_INCOMPLETE, survey(1), 0, 0);
        assertNotSame(changedLanguage, changedType);

        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedState = get(cache,
            plaque, 2, "nb_no", BuildingType.LUMBER_CAMP,
            PlaqueState.PLAN_INSERTED_UNLINKED, survey(1), 0, 0);
        assertNotSame(changedType, changedState);

        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedOccupants = get(cache,
            plaque, 2, "nb_no", BuildingType.LUMBER_CAMP,
            PlaqueState.PLAN_INSERTED_UNLINKED, survey(1), 1, 0);
        assertNotSame(changedState, changedOccupants);

        PlaqueSheetCache.CachedSheet<PlaqueSheet> changedCapacity = get(cache,
            plaque, 2, "nb_no", BuildingType.LUMBER_CAMP,
            PlaqueState.PLAN_INSERTED_UNLINKED, survey(1), 1, 2);
        assertNotSame(changedOccupants, changedCapacity);
        assertEquals(1, cache.sizeForTest(),
            "replacement snapshots must not grow the cache");
    }

    @Test
    void accessOrderEvictsTheLeastRecentlyUsedIdentityAtTheHardBound() {
        PlaqueSheetCache<Object, PlaqueSheet> cache =
            new PlaqueSheetCache<>(2, sheet -> sheet);
        Object firstPlaque = new Object();
        Object secondPlaque = new Object();
        Object thirdPlaque = new Object();

        PlaqueSheetCache.CachedSheet<PlaqueSheet> first = get(cache,
            firstPlaque, 1, "en_us", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(0), 0, 0);
        PlaqueSheetCache.CachedSheet<PlaqueSheet> second = get(cache,
            secondPlaque, 1, "en_us", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(0), 0, 0);
        assertSame(first, get(cache, firstPlaque, 1, "en_us",
            BuildingType.HOUSE, PlaqueState.LINKED_INCOMPLETE,
            survey(0), 0, 0));

        get(cache, thirdPlaque, 1, "en_us", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(0), 0, 0);
        assertEquals(2, cache.sizeForTest());
        PlaqueSheetCache.CachedSheet<PlaqueSheet> rebuiltSecond = get(cache,
            secondPlaque, 1, "en_us", BuildingType.HOUSE,
            PlaqueState.LINKED_INCOMPLETE, survey(0), 0, 0);

        assertNotSame(second, rebuiltSecond,
            "the untouched second plaque must have been the LRU eviction");
        assertEquals(2, cache.sizeForTest());
    }

    @Test
    void boundAndDerivedValueCannotBeDisabled() {
        assertEquals(256, PlaqueSheetCache.MAX_ENTRIES);
        assertThrows(IllegalArgumentException.class,
            () -> new PlaqueSheetCache<>(0, sheet -> sheet));
        assertThrows(IllegalArgumentException.class,
            () -> new PlaqueSheetCache<>(-1, sheet -> sheet));
        assertThrows(NullPointerException.class,
            () -> new PlaqueSheetCache<Object, Object>(sheet -> null)
                .getOrCreate(new Object(), 1, "en_us", BuildingType.HOUSE,
                    PlaqueState.LINKED_INCOMPLETE, survey(0), 0, 0));
    }

    private static PlaqueSheetCache<Object, PlaqueSheet> cache() {
        return new PlaqueSheetCache<>(sheet -> sheet);
    }

    private static <D> PlaqueSheetCache.CachedSheet<D> get(
            PlaqueSheetCache<Object, D> cache, Object identity, int revision,
            String language, BuildingType type, PlaqueState state,
            List<Requirement.Status> survey, int occupants, int capacity) {
        return cache.getOrCreate(identity, revision, language, type, state,
            survey, occupants, capacity);
    }

    private static List<Requirement.Status> survey(int have) {
        return List.of(status(have));
    }

    private static Requirement.Status status(int have) {
        Requirement requirement = BuildingType.HOUSE.requirements().get(0);
        return new Requirement.Status(requirement, have, requirement.needed());
    }
}
