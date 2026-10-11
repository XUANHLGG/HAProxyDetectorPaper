package com.tendoarisu.haproxydetector.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Updates only the language scalar in Velocity's config without dropping comments. */
public final class LanguageConfig {
    private LanguageConfig() {
    }

    public static void saveDetectedLanguage(Path path, List<String> lines, int languageLine, String language)
            throws IOException {
        List<String> updated = new ArrayList<>(lines);
        if (languageLine < 0) {
            updated.add("language: " + language);
        } else {
            String original = lines.get(languageLine);
            int colon = original.indexOf(':');
            int comment = original.indexOf('#', colon + 1);
            String suffix = comment < 0 ? "" : " " + original.substring(comment);
            updated.set(languageLine, original.substring(0, colon + 1) + " " + language + suffix);
        }
        Files.write(path, updated, StandardCharsets.UTF_8);
    }
}
