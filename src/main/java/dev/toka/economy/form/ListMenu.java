package dev.toka.economy.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * A list in pages: the back button first, the items of the page, then the buttons for the previous and the next page.
 * A long list also has a button for the words to look for, the order and the items on a page, which the player keeps until leaving.
 * The buttons are the items themselves, so two items with the same text cannot be mixed up.
 * The choices are kept per list name, so two lists do not share their order.
 */
public final class ListMenu {
    /**
     * @param icon   a path inside the game, or null for no icon
     * @param time   when the item happened, for a list sorted by time
     * @param number breaks a tie of {@code time} (such as the id of a ledger row)
     */
    public record Item(String text, String icon, Consumer<Player> action, long time, long number) {
        public Item(String text, String icon, Consumer<Player> action) {
            this(text, icon, action, 0, 0);
        }

        public static Item of(String text, Consumer<Player> action) {
            return new Item(text, null, action);
        }
    }

    /** Oldest first: by time, then by number, so rows of the same second keep their order. */
    public static final Comparator<Item> OLDEST_FIRST = Comparator.comparingLong(Item::time).thenComparingLong(Item::number);

    /**
     * @param defaultPerPage  items on a page when the player has not chosen
     * @param min             fewest items on a page the player can choose
     * @param max             most items on a page the player can choose
     * @param searchThreshold the search button shows when there are more items than this (always while a search is active)
     */
    public record Settings(int defaultPerPage, int min, int max, int searchThreshold) {
    }

    private static final int MAX_QUERY = 64;

    private final String name;
    private final FormTexts texts;
    private final Settings settings;
    private boolean byTime;
    private String title = "";
    private String hint = "";
    private String emptyText = "";
    private Consumer<Player> back;
    private List<Item> items = List.of();

    /** @param name the list this is, such as "ledger"; the choices of a player are kept under it */
    public ListMenu(String name, FormTexts texts, Settings settings) {
        this.name = name;
        this.texts = texts;
        this.settings = settings;
    }

    /** The second order is oldest first by the time of the items (texts.sortTime) instead of by name. */
    public ListMenu sortByTime() {
        this.byTime = true;
        return this;
    }

    public ListMenu title(String title) {
        this.title = title;
        return this;
    }

    /** The text above the buttons when there are items. */
    public ListMenu hint(String hint) {
        this.hint = hint;
        return this;
    }

    /** The text when there is nothing in the list at all. */
    public ListMenu emptyText(String emptyText) {
        this.emptyText = emptyText;
        return this;
    }

    /** Adds the back button, which does the action. */
    public ListMenu back(Consumer<Player> action) {
        this.back = action;
        return this;
    }

    public ListMenu items(List<Item> items) {
        this.items = List.copyOf(items);
        return this;
    }

    public void show(Player player) {
        show(player, 1);
    }

    private void show(Player player, int page) {
        View view = Preferences.get(player.getUniqueId(), name);
        ListLogic.Result<Item> result = ListLogic.compose(items, Item::text, OLDEST_FIRST, view, settings.defaultPerPage(), settings.min(), settings.max(), page);
        String shownTitle = result.pages() > 1 ? texts.pageTitleFor(title, result.page(), result.pages()) : title;
        String content = result.total() == 0 ? emptyText : result.matched() == 0 ? texts.searchNoneFor(view.query()) : hint;
        Menu menu = new Menu(shownTitle, content);
        if (back != null) menu.back(texts, back);
        for (Item item : result.shown()) menu.button(item.text(), item.icon(), item.action());
        if (result.page() > 1) menu.button(texts.prevPage(), p -> show(p, result.page() - 1));
        if (result.page() < result.pages()) menu.button(texts.nextPage(), p -> show(p, result.page() + 1));
        if (result.total() > settings.searchThreshold() || !view.query().isEmpty()) {
            menu.button(texts.options(), p -> showOptions(p, result.perPage()));
        }
        menu.show(player);
    }

    /** The form for the words to look for, the order and the items on a page. */
    private void showOptions(Player player, int perPage) {
        View view = Preferences.get(player.getUniqueId(), name);
        int min = Math.max(1, settings.min());
        int max = Math.max(min, settings.max());
        FormWindowCustom window = new FormWindowCustom(texts.optionsTitle());
        window.addElement(new ElementLabel(texts.optionsHintFor(items.size())));
        window.addElement(new ElementInput(texts.query(), texts.queryHint(), view.query()));
        window.addElement(new ElementDropdown(texts.sort(), List.of(texts.sortDefault(), byTime ? texts.sortTime() : texts.sortName()),
                view.sort() == View.SORT_DEFAULT ? 0 : 1));
        window.addElement(new ElementSlider(texts.perPage(), min, max, 1, perPage));
        Registry.show(player, window, (p, event) -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) return;
            String query = response.getInputResponse(1) == null ? "" : response.getInputResponse(1).strip();
            if (query.length() > MAX_QUERY) query = query.substring(0, MAX_QUERY);
            boolean second = response.getDropdownResponse(2) != null && response.getDropdownResponse(2).getElementID() == 1;
            int sort = !second ? View.SORT_DEFAULT : byTime ? View.SORT_TIME : View.SORT_NAME;
            Preferences.set(p.getUniqueId(), name, new View(query, sort, Math.round(response.getSliderResponse(3))));
            show(p, 1);
        });
    }
}
