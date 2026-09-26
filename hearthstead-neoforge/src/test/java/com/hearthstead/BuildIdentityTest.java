package com.hearthstead;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BuildIdentityTest {
    @Test
    void generatedIdentityNamesTheExactSourceCommit() {
        assertTrue(BuildIdentity.gitCommit().matches("[0-9a-f]{40}(?:[0-9a-f]{24})?"),
            "the packaged identity must contain a complete Git commit");
        assertFalse(BuildIdentity.version().isBlank());
        assertTrue(BuildIdentity.display().contains(BuildIdentity.shortCommit()),
            "the player-visible build identity must contain the short commit");
        assertTrue(BuildIdentity.inputHash().matches("[0-9a-f]{64}"),
            "the packaged identity must contain a complete module input hash");
        assertTrue(BuildIdentity.display().contains(BuildIdentity.inputHash().substring(0, 20)),
            "the player-visible identity must name its collision-resistant input hash");
        assertTrue(BuildIdentity.artifactFileName().matches(
            "hearthstead-[0-9A-Za-z._-]+-g[0-9a-f]{12}-i[0-9a-f]{20}\\.jar"),
            "the packaged identity must name the exact collision-resistant JAR filename");
        assertTrue(BuildIdentity.artifactFileName().equals(
            "hearthstead-" + BuildIdentity.version()
                + "-g" + BuildIdentity.gitCommit().substring(0, 12)
                + "-i" + BuildIdentity.inputHash().substring(0, 20) + ".jar"),
            "the packaged filename components must agree with the complete identity fields");
        assertTrue(BuildIdentity.display().equals(
            BuildIdentity.version()
                + "+g" + BuildIdentity.gitCommit().substring(0, 12)
                + ".i" + BuildIdentity.inputHash().substring(0, 20)),
            "the player-visible identity components must agree with the complete identity fields");
    }
}
