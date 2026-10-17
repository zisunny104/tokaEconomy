package dev.toka.economy.form;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The choices of the players for each list (by the name of the list), kept in memory until they leave. */
public final class Preferences {
    private static final Map<UUID, Map<String, View>> VIEWS = new ConcurrentHashMap<>();

    private Preferences() {
    }

    public static View get(UUID player, String list) {
        Map<String, View> lists = VIEWS.get(player);
        return lists == null ? View.DEFAULT : lists.getOrDefault(list, View.DEFAULT);
    }

    public static void set(UUID player, String list, View view) {
        VIEWS.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).put(list, view);
    }

    public static void forget(UUID player) {
        VIEWS.remove(player);
    }
}
