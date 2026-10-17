package dev.toka.economy.form;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class ListLogicTest {
    private static final List<String> NAMES = List.of("Cherry", "apple", "Banana", "applesauce", "date");

    @Test
    public void filterIgnoresCaseAndBlankQuery() {
        assertEquals(List.of("apple", "applesauce"), ListLogic.filter(NAMES, s -> s, "APPLE"));
        assertEquals(NAMES, ListLogic.filter(NAMES, s -> s, "  "));
        assertEquals(NAMES, ListLogic.filter(NAMES, s -> s, null));
    }

    @Test
    public void sortByNameIsCaseInsensitiveAndDefaultKeepsOrder() {
        assertEquals(List.of("apple", "applesauce", "Banana", "Cherry", "date"), ListLogic.sort(NAMES, s -> s, null, View.SORT_NAME));
        assertEquals(NAMES, ListLogic.sort(NAMES, s -> s, null, View.SORT_DEFAULT));
        assertEquals(NAMES, ListLogic.sort(NAMES, s -> s, null, View.SORT_TIME));
    }

    @Test
    public void sortByTimeUsesTimeThenNumberNotTheText() {
        // newest first as the ledger gives them; the texts would sort the other way round
        List<ListMenu.Item> items = List.of(
                new ListMenu.Item("a", null, p -> { }, 2000, 5),
                new ListMenu.Item("b", null, p -> { }, 1000, 4),
                new ListMenu.Item("c", null, p -> { }, 1000, 3),
                new ListMenu.Item("d", null, p -> { }, 1000, 2));
        List<String> oldest = ListLogic.sort(items, ListMenu.Item::text, ListMenu.OLDEST_FIRST, View.SORT_TIME).stream().map(ListMenu.Item::text).toList();
        assertEquals(List.of("d", "c", "b", "a"), oldest);
        List<String> byName = ListLogic.sort(items, ListMenu.Item::text, ListMenu.OLDEST_FIRST, View.SORT_NAME).stream().map(ListMenu.Item::text).toList();
        assertEquals(List.of("a", "b", "c", "d"), byName);
    }

    @Test
    public void perPageStaysBetweenLimits() {
        assertEquals(10, ListLogic.perPage(0, 10, 3, 30));
        assertEquals(3, ListLogic.perPage(1, 10, 3, 30));
        assertEquals(30, ListLogic.perPage(99, 10, 3, 30));
        assertEquals(5, ListLogic.perPage(0, 2, 5, 3));
    }

    @Test
    public void composeCutsPagesAndMovesPageIntoRange() {
        ListLogic.Result<String> r = ListLogic.compose(NAMES, s -> s, null, new View("", View.SORT_NAME, 3), 10, 3, 30, 2);
        assertEquals(List.of("Cherry", "date"), r.shown());
        assertEquals(2, r.page());
        assertEquals(2, r.pages());
        assertEquals(5, r.matched());
        assertEquals(2, ListLogic.compose(NAMES, s -> s, null, new View("", View.SORT_NAME, 3), 10, 3, 30, 9).page());
    }

    @Test
    public void composeCountsMatchedAndTotal() {
        ListLogic.Result<String> r = ListLogic.compose(NAMES, s -> s, null, new View("zzz", 0, 0), 10, 3, 30, 1);
        assertEquals(0, r.matched());
        assertEquals(5, r.total());
        assertEquals(1, r.pages());
        assertEquals(List.of(), r.shown());
    }

    @Test
    public void preferencesAreKeptPerListUntilForgotten() {
        UUID id = UUID.randomUUID();
        assertEquals(View.DEFAULT, Preferences.get(id, "top"));
        Preferences.set(id, "top", new View("a", View.SORT_NAME, 7));
        assertEquals(7, Preferences.get(id, "top").perPage());
        // the other list is not touched
        assertEquals(View.DEFAULT, Preferences.get(id, "ledger"));
        Preferences.set(id, "ledger", new View("", View.SORT_TIME, 0));
        assertEquals(View.SORT_NAME, Preferences.get(id, "top").sort());
        assertEquals(View.SORT_TIME, Preferences.get(id, "ledger").sort());
        Preferences.forget(id);
        assertEquals(View.DEFAULT, Preferences.get(id, "top"));
        assertEquals(View.DEFAULT, Preferences.get(id, "ledger"));
    }

    @Test
    public void textsFillValues() {
        FormTexts t = new FormTexts("", "", "", "", "", "n=${0}", "", "", "", "", "", "", "", "q=${0}", "${0} ${1}/${2}");
        assertEquals("n=4", t.optionsHintFor(4));
        assertEquals("q=x", t.searchNoneFor("x"));
        assertEquals("T 2/5", t.pageTitleFor("T", 2, 5));
    }
}
