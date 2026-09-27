package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads the SHIPPED handbook and English text from the test classpath. */
public final class HandbookTestData {
    public static final String ASSETS = "assets/hearthstead/";

    private HandbookTestData() {
    }

    public static JsonObject json(String path) {
        InputStream in = HandbookTestData.class.getClassLoader().getResourceAsStream(path);
        if (in == null) return null;
        try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
    }

    public static HandbookBook book() {
        JsonObject index = json(ASSETS + HandbookBook.INDEX);
        if (index == null) throw new IllegalStateException("missing " + HandbookBook.INDEX);
        return HandbookBook.parse(index, id -> json(ASSETS + HandbookBook.chapterPath(id)));
    }

    public static JsonObject english() {
        return json(ASSETS + "lang/en_us.json");
    }

    /**
     * Approximate vanilla default-font advances (glyph + 1px spacing). Wide
     * punctuation and every non-ASCII glyph are over-estimated, so a page
     * that fits here fits the real font too.
     */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += switch (c) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l', '`' -> 3;
                case 't', 'I', '[', ']', ' ' -> 4;
                case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 5;
                case '@', '~' -> 7;
                default -> c > 0x7F ? 8 : 6;
            };
        }
        return w;
    }

    /** Serif small caps: 11px initials and 9px capitals, over-estimated. */
    public static int serifWidth(String s) {
        int w = 0;
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = Character.isLetterOrDigit(c);
            w += c == ' ' ? 4 : letter && start ? 10 : 8;
            start = !letter && c != '\'';
        }
        return w;
    }

    /** Greedy word wrap like the vanilla splitter, breaking inside a word only when it must. */
    public static List<String> wrap(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (width(candidate) <= max) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                while (width(word) > max && word.length() > 1) {
                    int cut = word.length() - 1;
                    while (cut > 1 && width(word.substring(0, cut)) > max) cut--;
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    public static HandbookPageLayout.Measure measure() {
        return new HandbookPageLayout.Measure() {
            @Override
            public int width(String text) {
                return HandbookTestData.width(text);
            }

            @Override
            public List<String> wrap(String text, int width) {
                return HandbookTestData.wrap(text, width);
            }

            @Override
            public int titleWidth(String text) {
                return serifWidth(text);
            }
        };
    }
}
