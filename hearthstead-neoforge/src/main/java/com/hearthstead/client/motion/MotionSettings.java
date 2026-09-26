package com.hearthstead.client.motion;

import com.hearthstead.HearthsteadClientConfig;

/**
 * Engine switches. The client config ({@code [motion] enabled}) is the
 * persistent default; {@code -Dhearthstead.motion=off} forces the legacy
 * path for a whole run (the "before" build), and {@code /hsmotion on|off}
 * flips it for this session only so old and new can be filmed back to back.
 */
public final class MotionSettings {
    private static final String PROPERTY = System.getProperty("hearthstead.motion", "");
    private static Boolean sessionEngine;
    private static Boolean sessionSecondary;

    private MotionSettings() {
    }

    public static boolean engineEnabled() {
        if (sessionEngine != null) {
            return sessionEngine;
        }
        if ("off".equalsIgnoreCase(PROPERTY) || "false".equalsIgnoreCase(PROPERTY)) {
            return false;
        }
        return HearthsteadClientConfig.motionEngine();
    }

    public static boolean secondaryEnabled() {
        if (!engineEnabled()) {
            return false;
        }
        if (sessionSecondary != null) {
            return sessionSecondary;
        }
        return HearthsteadClientConfig.motionSecondary();
    }

    public static void setSessionEngine(Boolean enabled) {
        sessionEngine = enabled;
    }

    public static void setSessionSecondary(Boolean enabled) {
        sessionSecondary = enabled;
    }
}
