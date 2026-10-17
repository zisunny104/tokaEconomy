package dev.toka.economy;

import com.google.gson.JsonObject;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Persist;
import dev.toka.economy.core.Settings;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/** Shared helpers: the default config, a movable clock and a store in memory that keeps the batches. */
final class TestSupport {
    private TestSupport() {
    }

    static final class MovableClock extends Clock {
        long millis;

        MovableClock(String instant) {
            millis = Instant.parse(instant).toEpochMilli();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    /** Documents of every batch, merged like the real store does. */
    static final class MemoryPersist implements Persist {
        final Map<String, JsonObject> documents = new TreeMap<>();
        int batches;

        @Override
        public void save(Map<String, JsonObject> batch) {
            batches++;
            documents.putAll(batch);
        }
    }

    static Map<String, Object> defaultConfig() throws IOException {
        try (InputStream in = TestSupport.class.getClassLoader().getResourceAsStream("config.yml")) {
            return config(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> config(String yaml) {
        return (Map<String, Object>) new Yaml().load(new StringReader(yaml));
    }

    static Settings settings() throws IOException {
        return Settings.parse(defaultConfig());
    }

    static EconomyService service(Settings settings, MemoryPersist persist, Clock clock, Set<String> permissions) {
        return new EconomyService(settings, persist, clock, (id, node) -> permissions.contains(node), Map.of());
    }
}
