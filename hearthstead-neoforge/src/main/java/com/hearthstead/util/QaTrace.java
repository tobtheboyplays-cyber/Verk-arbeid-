package com.hearthstead.util;

import com.hearthstead.entity.SettlerEntity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * QA decision-trace recorder. Active only with
 * {@code -Dhearthstead.qa.trace=true} (set by tools/hearthstead-qa behavior);
 * zero overhead otherwise. One JSONL line per settler per sample tick,
 * consumed by qa/scripts/analyze_trace.py.
 */
public final class QaTrace {
    public static final boolean ENABLED = Boolean.getBoolean("hearthstead.qa.trace");

    private static BufferedWriter writer;

    public static void record(SettlerEntity settler) {
        if (!ENABLED) {
            return;
        }
        String line = String.format(java.util.Locale.ROOT,
            "{\"tick\":%d,\"uuid\":\"%s\",\"name\":\"%s\",\"activity\":\"%s\","
                + "\"profession\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,"
                + "\"navDone\":%b,\"hunger\":%.1f,\"energy\":%.1f,\"morale\":%.1f,"
                + "\"sleeping\":%b,\"bag\":%d}%n",
            settler.level().getGameTime(), settler.getUUID(),
            settler.getSettlerName().replace("\"", ""),
            settler.getActivity().name(), settler.getProfession().name(),
            settler.getX(), settler.getY(), settler.getZ(),
            settler.getNavigation().isDone(),
            settler.getHunger(), settler.getEnergy(), settler.getMorale(),
            settler.isSleeping(), bagCount(settler));
        write(line);
    }

    private static int bagCount(SettlerEntity settler) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private static synchronized void write(String line) {
        try {
            if (writer == null) {
                // The QA controller passes an absolute path so it never has
                // to guess which directory the game forked into.
                Path path = Path.of(System.getProperty(
                    "hearthstead.qa.traceFile", "hearthstead-trace.jsonl"))
                    .toAbsolutePath();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        writer.flush();
                        writer.close();
                    } catch (IOException ignored) {
                        // shutdown; nothing to recover
                    }
                }));
            }
            writer.write(line);
            writer.flush();
        } catch (IOException e) {
            // QA-only path; never let tracing break the game.
        }
    }

    private QaTrace() {
    }
}
