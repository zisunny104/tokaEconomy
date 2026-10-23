package dev.toka.economy.window;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;
import dev.toka.economy.command.Caller;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Line;
import dev.toka.economy.command.Names;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.form.Confirm;
import dev.toka.economy.form.Custom;
import dev.toka.economy.form.FormTexts;
import dev.toka.economy.form.ListMenu;
import dev.toka.economy.form.Menu;
import dev.toka.i18n.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The admin window of /economy menu: give, take, set, see, ledger of a player, rates and reload. Everything runs through {@link CommandLogic}. */
public final class AdminWindows {
    private static final String PERM_ADMIN = "tokaEconomy.admin";
    private static final String ICON_ADJUST = "textures/items/gold_ingot";
    private static final String ICON_SEE = "textures/items/spyglass";
    private static final String ICON_LEDGER = "textures/items/book_writable";
    private static final String ICON_RATES = "textures/items/emerald";
    private static final String ICON_RELOAD = "textures/items/clock_item";
    private static final String ICON_CLOSE = "textures/ui/cancel";
    private static final String[] KINDS = {"give", "take", "set"};

    private final EconomyService service;
    private final CommandLogic logic;
    private final WindowLogic windows;
    private final Messages messages;
    private final Supplier<WindowSettings> settings;
    private final BiConsumer<Player, List<Line>> sender;
    private final Names names;
    private final Consumer<Player> reloader;

    public AdminWindows(EconomyService service, CommandLogic logic, Messages messages, Supplier<WindowSettings> settings,
                        BiConsumer<Player, List<Line>> sender, Names names, Consumer<Player> reloader) {
        this.service = service;
        this.logic = logic;
        this.windows = new WindowLogic(service, logic);
        this.messages = messages;
        this.settings = settings;
        this.sender = sender;
        this.names = names;
        this.reloader = reloader;
    }

    private String t(String key, Object... args) {
        return messages.text(key, args);
    }

    private String icon(String path) {
        return settings.get().icons() ? path : null;
    }

    private Caller caller(Player player) {
        return new Caller(player.getUniqueId(), player.getName());
    }

    private void cancelled(Player player) {
        sender.accept(player, List.of(Line.of("msg.window.cancelled")));
    }

    private String currencyName(String code) {
        return service.currencyName(code, service.settings().language());
    }

    // ---- main -----------------------------------------------------------------------------------------------------

    public void open(Player player) {
        if (!player.hasPermission(PERM_ADMIN)) {
            sender.accept(player, List.of(Line.of("err.noPermission")));
            return;
        }
        Menu menu = new Menu(t("ui.admin.title"), t("ui.admin.prompt"));
        menu.button(t("ui.admin.button.adjust"), icon(ICON_ADJUST), p -> pick(p, "", null, this::adjustForm0));
        menu.button(t("ui.admin.button.see"), icon(ICON_SEE), p -> pick(p, "", null, (q, name) ->
                logic.balance(caller(q), true, new String[]{name}, lines -> sender.accept(q, lines))));
        menu.button(t("ui.admin.button.ledger"), icon(ICON_LEDGER), p -> pick(p, "", null, this::ledger));
        menu.button(t("ui.admin.button.rates"), icon(ICON_RATES), this::rates);
        menu.button(t("ui.admin.button.reload"), icon(ICON_RELOAD), this::confirmReload);
        menu.button(t("ui.button.close"), icon(ICON_CLOSE), p -> {
        });
        menu.show(player);
    }

    // ---- choosing a player: search, online dropdown, or an offline name --------------------------------------------

    /**
     * @param search the text that narrows the dropdown; empty for all online players
     * @param next   gets the chosen name; closing the form goes back to the menu
     */
    private void pick(Player player, String search, Line error, BiConsumer<Player, String> next) {
        String needle = search.strip().toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        options.add(t("ui.admin.pick.none"));
        for (Player other : player.getServer().getOnlinePlayers().values()) {
            if (other.isOnline() && other.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                options.add(other.getName());
            }
        }
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.pick.title"));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.pick.hint") : t(error.key(), error.args()) + "\n" + t("ui.admin.pick.hint")));
        form.addElement(new ElementInput(t("ui.admin.pick.search"), t("ui.admin.pick.searchHint"), search));
        form.addElement(new ElementDropdown(t("ui.admin.pick.online"), options, options.size() == 2 ? 1 : 0));
        form.addElement(new ElementInput(t("ui.admin.pick.offline"), t("ui.admin.pick.offlineHint"), ""));
        Custom.show(player, form, response -> {
            String typedSearch = input(response, 1);
            String offline = input(response, 3);
            int index = response.getDropdownResponse(2) == null ? 0 : response.getDropdownResponse(2).getElementID();
            if (!offline.isEmpty()) {
                next.accept(player, offline);
            } else if (index > 0 && index < options.size()) {
                next.accept(player, options.get(index));
            } else if (!typedSearch.isEmpty() && !typedSearch.equalsIgnoreCase(search.strip())) {
                pick(player, typedSearch, null, next);
            } else {
                pick(player, typedSearch, Line.of("ui.admin.pick.missing"), next);
            }
        }, this::open);
    }

    private static String input(FormResponseCustom response, int element) {
        String value = response.getInputResponse(element);
        return value == null ? "" : value.strip();
    }

    // ---- give, take, set --------------------------------------------------------------------------------------------

    private void adjustForm0(Player player, String name) {
        adjustForm(player, name, null, "", 0, 0);
    }

    private void adjustForm(Player player, String name, Line error, String amount, int kind, int currency) {
        List<String> codes = windows.currencies();
        List<String> currencyNames = new ArrayList<>();
        codes.forEach(code -> currencyNames.add(currencyName(code)));
        List<String> kindNames = new ArrayList<>();
        for (String k : KINDS) {
            kindNames.add(t("ui.admin.kind." + k));
        }
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.adjust.title", name));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.adjust.label", name) : t(error.key(), error.args())));
        form.addElement(new ElementDropdown(t("ui.admin.adjust.action"), kindNames, kind));
        form.addElement(new ElementDropdown(t("ui.admin.adjust.currency"), currencyNames, Math.min(currency, codes.size() - 1)));
        form.addElement(new ElementInput(t("ui.admin.adjust.amount"), t("ui.admin.adjust.amountHint"), amount));
        Custom.show(player, form, response -> {
            int k = response.getDropdownResponse(1) == null ? 0 : Math.max(0, Math.min(KINDS.length - 1, response.getDropdownResponse(1).getElementID()));
            int c = response.getDropdownResponse(2) == null ? 0 : Math.max(0, Math.min(codes.size() - 1, response.getDropdownResponse(2).getElementID()));
            String typed = input(response, 3);
            long value = CommandLogic.parseAmount(typed);
            if (value < 0 || (value == 0 && !KINDS[k].equals("set"))) {
                adjustForm(player, name, Line.of("err.amount"), typed, k, c);
                return;
            }
            String code = codes.get(c);
            new Confirm(t("ui.admin.confirm.title"),
                    t("ui.admin.confirm.content", kindNames.get(k), name, service.format(value, code, true)),
                    t("ui.admin.confirm.accept"), t("ui.common.cancel"))
                    .onConfirm(p -> logic.adjust(KINDS[k], caller(p), new String[]{name, String.valueOf(value), code}, lines -> sender.accept(p, lines)))
                    .onCancel(this::cancelled)
                    .show(player);
        }, this::open);
    }

    // ---- ledger of a player -------------------------------------------------------------------------------------------

    private void ledger(Player player, String name) {
        names.resolve(name, found -> {
            if (found.isEmpty()) {
                sender.accept(player, List.of(Line.of("err.player.notFound", name)));
                return;
            }
            UUID id = found.get();
            List<CommandLogic.Row> rows = logic.ledgerRows(id, settings.get().rowsMax());
            List<ListMenu.Item> items = new ArrayList<>();
            for (CommandLogic.Row row : rows) {
                items.add(new ListMenu.Item(t(row.line().key(), row.line().args()), null, p -> ledger(p, name), row.time(), row.id()));
            }
            WindowSettings s = settings.get();
            new ListMenu("adminLedger", texts(), new ListMenu.Settings(s.perPage(), s.min(), s.max(), s.threshold()))
                    .title(t("ui.admin.ledger.title", names.display(id))).hint(t("ui.ledger.hint", rows.size()))
                    .emptyText(t("ui.ledger.empty")).back(this::open).items(items).sortByTime().show(player);
        });
    }

    private FormTexts texts() {
        return new FormTexts(t("ui.button.back"), t("ui.button.prevPage"), t("ui.button.nextPage"), t("ui.button.options"),
                t("ui.options.title"), t("ui.options.hint", "${0}"), t("ui.options.query"), t("ui.options.queryHint"),
                t("ui.options.sort"), t("ui.ledger.sortNew"), t("ui.options.sortName"), t("ui.ledger.sortOld"), t("ui.options.perPage"),
                t("ui.search.none", "${0}"), t("ui.page.title", "${0}", "${1}", "${2}"));
    }

    // ---- rates and reload -------------------------------------------------------------------------------------------

    private void rates(Player player) {
        List<String> rows = new ArrayList<>();
        for (Line line : logic.rates()) {
            rows.add(t(line.key(), line.args()));
        }
        Menu menu = new Menu(t("ui.admin.rates.title"), String.join("\n", rows));
        menu.back(texts(), this::open);
        menu.show(player);
    }

    private void confirmReload(Player player) {
        new Confirm(t("ui.admin.reload.title"), t("ui.admin.reload.content"), t("ui.admin.reload.accept"), t("ui.common.cancel"))
                .onConfirm(reloader)
                .onCancel(this::cancelled)
                .show(player);
    }
}
