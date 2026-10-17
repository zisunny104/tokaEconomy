package dev.toka.economy.form;

/**
 * What one player chose for a list: the words to look for, the order and the items on a page (0 for the default).
 *
 * @param sort {@link #SORT_DEFAULT}, {@link #SORT_NAME} or {@link #SORT_TIME}
 */
public record View(String query, int sort, int perPage) {
    public static final int SORT_DEFAULT = 0;
    /** By the text of the items. */
    public static final int SORT_NAME = 1;
    /** Oldest first, by the time and the number of the items. */
    public static final int SORT_TIME = 2;

    public static final View DEFAULT = new View("", SORT_DEFAULT, 0);

    public View {
        query = query == null ? "" : query;
    }
}
