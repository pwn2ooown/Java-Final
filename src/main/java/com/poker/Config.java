package com.poker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal configuration loader. Reads a {@code .env} file in the working
 * directory (if present) and falls back to real environment variables.
 * Environment variables take precedence over the {@code .env} file.
 */
public class Config {

    private final Map<String, String> dotenv = new HashMap<>();

    public Config() {
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            try {
                for (String raw : Files.readAllLines(env)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    dotenv.put(key, value);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read .env file", e);
            }
        }
    }

    public String get(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) {
            v = dotenv.get(key);
        }
        return v;
    }

    public String getOrDefault(String key, String def) {
        String v = get(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    public String require(String key) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Missing required config '" + key + "'. Set it in .env or as an environment variable.");
        }
        return v;
    }
}
