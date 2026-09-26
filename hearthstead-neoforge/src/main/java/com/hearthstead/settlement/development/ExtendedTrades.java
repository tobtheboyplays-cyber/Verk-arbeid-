package com.hearthstead.settlement.development;

import com.hearthstead.HearthsteadServerConfig;

import javax.annotation.Nullable;

/**
 * The {@code [features] extendedTrades} switch (trades-unlock lane, 26 Sep).
 *
 * <p>On (the default): the four specialization nodes (Fortification, Land
 * and Harvest, Craft and Industry, Hall and Learning) are learnable and the
 * Mayor sells their 15 emblems. Off: exactly the old behaviour -- those nodes
 * report FUTURE ("PLANNED") and {@link JobEmblemCatalog#forProfession} hides
 * their emblems, so nothing can buy them. Saved knowledge and settlers already
 * working those trades are never touched.
 */
public final class ExtendedTrades {
    private static volatile Boolean testOverride;

    public static boolean enabled() {
        Boolean override = testOverride;
        return override != null ? override
            : HearthsteadServerConfig.extendedTradesEnabled();
    }

    /** GameTest/JUnit hook; {@code null} restores the config value. */
    public static void overrideForTests(@Nullable Boolean enabled) {
        testOverride = enabled;
    }

    private ExtendedTrades() {
    }
}
