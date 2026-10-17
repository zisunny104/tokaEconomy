package dev.toka.economy.form;

import dev.toka.i18n.Pager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** The part of a list window that needs no server: filtering, ordering and cutting into pages. */
public final class ListLogic {
    /**
     * @param shown   the items of the page
     * @param page    the page shown, from 1
     * @param pages   the number of pages, at least 1
     * @param matched the items left after the search
     * @param total   the items before the search
     * @param perPage the items on a page after the limits
     */
    public record Result<T>(List<T> shown, int page, int pages, int matched, int total, int perPage) {
    }

    private record Keyed<T>(String key, T item) {
    }

    private ListLogic() {
    }

    public static <T> List<T> filter(List<T> items, Function<T, String> text, String query) {
        List<T> result = new ArrayList<>(items);
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            result.removeIf(item -> !text.apply(item).toLowerCase(Locale.ROOT).contains(needle));
        }
        return result;
    }

    /**
     * @param time how items compare by age, oldest first (null when the list has no time)
     * @param sort a {@code View.SORT_*} value; anything else keeps the given order
     */
    public static <T> List<T> sort(List<T> items, Function<T, String> text, Comparator<T> time, int sort) {
        List<T> result = new ArrayList<>(items);
        if (sort == View.SORT_NAME) {
            List<Keyed<T>> keyed = new ArrayList<>(result.size());
            for (T item : result) {
                keyed.add(new Keyed<>(text.apply(item).toLowerCase(Locale.ROOT), item));
            }
            keyed.sort(Comparator.comparing(Keyed::key));
            result.clear();
            for (Keyed<T> entry : keyed) {
                result.add(entry.item());
            }
        } else if (sort == View.SORT_TIME && time != null) {
            result.sort(time);
        }
        return result;
    }

    /** The items on a page: the choice of the player, or the default when it is 0, kept between the limits. */
    public static int perPage(int chosen, int fallback, int min, int max) {
        int low = Math.max(1, min);
        int high = Math.max(low, max);
        return Math.min(high, Math.max(low, chosen > 0 ? chosen : fallback));
    }

    public static <T> Result<T> compose(List<T> all, Function<T, String> text, Comparator<T> time, View view, int defaultPerPage, int min, int max, int page) {
        List<T> matched = sort(filter(all, text, view.query()), text, time, view.sort());
        int per = perPage(view.perPage(), defaultPerPage, min, max);
        Pager.Page<T> cut = Pager.of(matched, per, page);
        return new Result<>(cut.lines(), cut.page(), cut.pages(), matched.size(), all.size(), per);
    }
}
