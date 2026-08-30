package com.hearthstead;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime identity for a built Hearthstead JAR.
 *
 * <p>The generated resource is intentionally timestamp-free: two builds from
 * the same clean commit can be compared by hash without a clock value making
 * their payload differ. The external release manifest records the final JAR
 * SHA-256.</p>
 */
public final class BuildIdentity {
    private static final String RESOURCE = "/hearthstead-build.properties";
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildIdentity.class);
    private static final Properties VALUES = load();

    private BuildIdentity() {
    }

    public static String version() {
        return VALUES.getProperty("version", "unknown");
    }

    public static String gitCommit() {
        return VALUES.getProperty("gitCommit", "unknown");
    }

    public static boolean gitDirty() {
        return Boolean.parseBoolean(VALUES.getProperty("gitDirty", "true"));
    }

    public static String display() {
        return VALUES.getProperty("buildIdentity",
            version() + "+g" + shortCommit() + ".unknown");
    }

    public static String shortCommit() {
        String commit = gitCommit();
        return commit.length() <= 12 ? commit : commit.substring(0, 12);
    }

    private static Properties load() {
        Properties values = new Properties();
        try (InputStream stream = BuildIdentity.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                LOGGER.error("Missing Hearthstead build identity resource {}", RESOURCE);
                return values;
            }
            values.load(stream);
        } catch (IOException exception) {
            LOGGER.error("Unable to read Hearthstead build identity", exception);
        }
        return values;
    }
}
