package com.hearthstead.client.render;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueSheet;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bounded client-side cache for the derived parchment shown by a plaque.
 *
 * <p>The block entity remains the only source of truth. Each entry is bound to
 * its synchronized revision plus type, state, occupancy, capacity and locale;
 * the revision owns the immutable survey. A changed packet rebuilds the sheet
 * and its derived render geometry synchronously in the same render call, so
 * there is no stale-frame or asynchronous blanking window.
 *
 * <p>Keys are the live block-entity instances supplied by the renderer. The
 * access-ordered hard limit prevents unloaded entities from being retained
 * without bound, while a steady visible plaque performs no key, list, line or
 * component allocation per frame.
 */
final class PlaqueSheetCache<K, D> {

    static final int MAX_ENTRIES = 256;

    private final int maximumSize;
    private final Function<PlaqueSheet, D> derive;
    private final Map<K, Entry<D>> entries;

    PlaqueSheetCache(Function<PlaqueSheet, D> derive) {
        this(MAX_ENTRIES, derive);
    }

    PlaqueSheetCache(int maximumSize, Function<PlaqueSheet, D> derive) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.derive = Objects.requireNonNull(derive, "derive");
        this.maximumSize = maximumSize;
        this.entries = new LinkedHashMap<>(32, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<D>> eldest) {
                return size() > PlaqueSheetCache.this.maximumSize;
            }
        };
    }

    CachedSheet<D> getOrCreate(K identity, int revision, String language,
                               BuildingType type, PlaqueState state,
                               List<Requirement.Status> survey,
                               int occupants, int capacity) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(survey, "survey");

        Entry<D> cached = entries.get(identity);
        if (cached != null
            && cached.matches(revision, language, type, state,
                occupants, capacity)) {
            return cached.value;
        }

        // PlaqueBlockEntity owns the revision and publishes an immutable
        // survey. A revision hit is therefore enough to avoid an O(rows)
        // List.equals on every visible-plaque frame. copyOf still prevents a
        // future mutable caller from changing the just-derived sheet during
        // this synchronous miss.
        List<Requirement.Status> stableSurvey = List.copyOf(survey);
        PlaqueSheet rebuilt = PlaqueSheet.of(type, state, stableSurvey,
            occupants, capacity);
        CachedSheet<D> value = new CachedSheet<>(rebuilt,
            Objects.requireNonNull(derive.apply(rebuilt), "derived sheet"));
        entries.put(identity, new Entry<>(revision, language, type, state,
            occupants, capacity, value));
        return value;
    }

    int sizeForTest() {
        return entries.size();
    }

    record CachedSheet<D>(PlaqueSheet sheet, D derived) {
        CachedSheet {
            Objects.requireNonNull(sheet, "sheet");
            Objects.requireNonNull(derived, "derived");
        }
    }

    private static final class Entry<D> {
        private final int revision;
        private final String language;
        private final BuildingType type;
        private final PlaqueState state;
        private final int occupants;
        private final int capacity;
        private final CachedSheet<D> value;

        private Entry(int revision, String language, BuildingType type,
                      PlaqueState state, int occupants, int capacity,
                      CachedSheet<D> value) {
            this.revision = revision;
            this.language = language;
            this.type = type;
            this.state = state;
            this.occupants = occupants;
            this.capacity = capacity;
            this.value = value;
        }

        private boolean matches(int candidateRevision,
                                String candidateLanguage,
                                BuildingType candidateType,
                                PlaqueState candidateState,
                                int candidateOccupants,
                                int candidateCapacity) {
            return revision == candidateRevision
                && language.equals(candidateLanguage)
                && type == candidateType
                && state == candidateState
                && occupants == candidateOccupants
                && capacity == candidateCapacity;
        }
    }
}
