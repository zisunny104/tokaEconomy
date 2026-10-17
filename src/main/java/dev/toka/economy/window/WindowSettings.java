package dev.toka.economy.window;

import java.util.Map;

/**
 * The window options of config.yml.
 *
 * @param perPage   items on a page before the player chooses
 * @param min       fewest items on a page the player can choose
 * @param max       most items on a page the player can choose
 * @param threshold the search button shows when a list has more items than this
 * @param icons     whether the buttons have icons
 * @param rowsMax   most rows read for the leaderboard and the ledger
 */
public record WindowSettings(int perPage, int min, int max, int threshold, boolean icons, int rowsMax) {
    public static WindowSettings parse(Map<?, ?> root) {
        int min = Math.max(3, integer(root, "window-items-min", 3));
        int max = Math.max(min, integer(root, "window-items-max", 30));
        int perPage = Math.min(max, Math.max(min, integer(root, "window-items-per-page", 10)));
        int threshold = Math.max(0, integer(root, "window-search-threshold", 8));
        boolean icons = !(root.get("window-icons") instanceof Boolean b) || b;
        return new WindowSettings(perPage, min, max, threshold, icons, Math.max(10, integer(root, "window-rows-max", 500)));
    }

    private static int integer(Map<?, ?> root, String key, int fallback) {
        return root.get(key) instanceof Number n ? n.intValue() : fallback;
    }
}
