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
import dev.toka.economy.form.FormListener;
import dev.toka.economy.form.FormTexts;
import dev.toka.economy.form.ListMenu;
import dev.toka.economy.form.Menu;
import dev.toka.i18n.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The windows of /balance: wallet, transfer, exchange, leaderboard and ledger. Every number and decision comes from
 * {@link CommandLogic} and {@link WindowLogic}; this class only shows them. It reads memory only, never the disk.
 */
public final class EconomyWindows {
    private static final String ICON_TRANSFER = "textures/items/gold_ingot";
    private static final String ICON_EXCHANGE = "textures/items/emerald";
    private static final String ICON_TOP = "textures/items/diamond";
    private static final String ICON_LEDGER = "textures/items/book_writable";
    private static final String ICON_PLAYER = "textures/items/name_tag";
    private static final String ICON_CLOSE = "textures/ui/cancel";

    private final EconomyService service;
    private final CommandLogic logic;
    private final WindowLogic windows;
    private final Messages messages;
    private final Supplier<WindowSettings> settings;
    private final BiConsumer<Player, List<Line>> sender;
    private final Names names;

    /**
     * @param sender sends the lines of a result to the player (and to anyone a line is addressed to)
     */
    public EconomyWindows(EconomyService service, CommandLogic logic, Messages messages, Supplier<WindowSettings> settings,
                          BiConsumer<Player, List<Line>> sender, Names names) {
        this.service = service;
        this.logic = logic;
        this.windows = new WindowLogic(service, logic);
        this.messages = messages;
        this.settings = settings;
        this.sender = sender;
        this.names = names;
    }

    /** Register once in onEnable: the answers of the windows. */
    public static void register(cn.nukkit.plugin.Plugin plugin) {
        FormListener.register(plugin);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    private String t(String key, Object... args) {
        return messages.text(key, args);
    }

    private String text(Line line) {
        return messages.text(line.key(), line.args());
    }

    private String icon(String path) {
        return settings.get().icons() ? path : null;
    }

    private String money(long amount, String code) {
        return service.format(amount, code, true);
    }

    private String name(String code) {
        return service.currencyName(code, service.settings().language());
    }

    private Caller caller(Player player) {
        return new Caller(player.getUniqueId(), player.getName());
    }

    private FormTexts texts(String sortDefault, String sortTime) {
        return new FormTexts(t("ui.button.back"), t("ui.button.prevPage"), t("ui.button.nextPage"), t("ui.button.options"),
                t("ui.options.title"), t("ui.options.hint", "${0}"), t("ui.options.query"), t("ui.options.queryHint"),
                t("ui.options.sort"), sortDefault, t("ui.options.sortName"), sortTime, t("ui.options.perPage"),
                t("ui.search.none", "${0}"), t("ui.page.title", "${0}", "${1}", "${2}"));
    }

    private FormTexts texts() {
        return texts(t("ui.options.sortDefault"), null);
    }

    /** @param name the list, which keeps its own order, search and page size for the player */
    private ListMenu list(String name, FormTexts texts, String title, String hint, String empty, List<ListMenu.Item> items, Consumer<Player> back) {
        WindowSettings s = settings.get();
        return new ListMenu(name, texts, new ListMenu.Settings(s.perPage(), s.min(), s.max(), s.threshold()))
                .title(title).hint(hint).emptyText(empty).back(back).items(items);
    }

    private void cancelled(Player player) {
        sender.accept(player, List.of(Line.of("msg.window.cancelled")));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    // ---- wallet ---------------------------------------------------------------------------------------------------

    /** The main window: the balances and the buttons the player may use. */
    public void open(Player player) {
        service.createAccount(player.getUniqueId(), player.getName());
        List<String> rows = new ArrayList<>();
        for (Line line : logic.balanceRows(player.getUniqueId())) {
            rows.add(text(line));
        }
        Menu menu = new Menu(t("ui.balance.title"), t("ui.balance.prompt", join(rows)));
        if (player.hasPermission("tokaEconomy.pay")) {
            menu.button(t("ui.button.transfer"), icon(ICON_TRANSFER), this::pickPlayer);
        }
        if (player.hasPermission("tokaEconomy.exchange") && windows.exchangeEnabled()) {
            menu.button(t("ui.button.exchange"), icon(ICON_EXCHANGE), this::pickPair);
        }
        if (player.hasPermission("tokaEconomy.top")) {
            menu.button(t("ui.button.top"), icon(ICON_TOP), this::pickBoard);
        }
        menu.button(t("ui.button.ledger"), icon(ICON_LEDGER), this::ledger);
        menu.button(t("ui.button.close"), icon(ICON_CLOSE), p -> {
        });
        menu.show(player);
    }

    // ---- transfer -------------------------------------------------------------------------------------------------

    private void pickPlayer(Player player) {
        List<ListMenu.Item> items = new ArrayList<>();
        items.add(new ListMenu.Item(t("ui.transfer.offline.item"), icon(ICON_PLAYER), this::offlineForm));
        for (Player other : player.getServer().getOnlinePlayers().values()) {
            if (other != player && other.isOnline()) {
                UUID id = other.getUniqueId();
                String name = other.getName();
                items.add(new ListMenu.Item(name, icon(ICON_PLAYER), p -> transferForm(p, id, name, null, "", 0)));
            }
        }
        list("players", texts(), t("ui.transfer.players.title"), t("ui.transfer.players.hint"), t("ui.transfer.players.empty"), items, this::open).show(player);
    }

    /** The name of a player who is not online; the account is made when needed. */
    private void offlineForm(Player player) {
        offlineForm(player, null, "");
    }

    private void offlineForm(Player player, Line error, String typed) {
        FormWindowCustom form = new FormWindowCustom(t("ui.transfer.offline.title"));
        form.addElement(new ElementLabel(error == null ? t("ui.transfer.offline.label") : text(error)));
        form.addElement(new ElementInput(t("ui.transfer.offline.name"), t("ui.transfer.offline.nameHint"), typed));
        Custom.show(player, form, response -> {
            String name = response.getInputResponse(1) == null ? "" : response.getInputResponse(1).strip();
            if (name.isEmpty()) {
                offlineForm(player, Line.of("err.player.notFound", name), name);
                return;
            }
            names.resolve(name, found -> {
                if (found.isEmpty() || found.get().equals(player.getUniqueId())) {
                    offlineForm(player, found.isEmpty() ? Line.of("err.player.notFound", name) : Line.of("err.self"), name);
                    return;
                }
                UUID id = found.get();
                if (!service.hasAccount(id)) {
                    service.createAccount(id, name);
                }
                transferForm(player, id, names.display(id), null, "", 0);
            });
        }, this::pickPlayer);
    }

    /** The amount (and currency) form; {@code error} explains why the last try came back. Closing it cancels. */
    private void transferForm(Player player, UUID target, String targetName, Line error, String amount, int currency) {
        List<String> codes = windows.currencies();
        FormWindowCustom form = new FormWindowCustom(t("ui.transfer.form.title", targetName));
        form.addElement(new ElementLabel(label(error, t("ui.transfer.form.label", targetName), player)));
        form.addElement(new ElementInput(t("ui.transfer.form.amount"), t("ui.transfer.form.amountHint"), amount));
        if (codes.size() > 1) {
            form.addElement(new ElementDropdown(t("ui.transfer.form.currency"), currencyNames(codes), Math.max(0, Math.min(currency, codes.size() - 1))));
        }
        Custom.show(player, form, response -> {
            String typed = response.getInputResponse(1) == null ? "" : response.getInputResponse(1);
            int index = chosen(response, 2, codes.size());
            String code = codes.get(index);
            WindowLogic.Review review = windows.transfer(player.getUniqueId(), target, targetName, code, typed);
            if (!review.ok()) {
                transferForm(player, target, targetName, review.error(), typed, index);
                return;
            }
            new Confirm(t("ui.transfer.confirm.title"),
                    t("ui.transfer.confirm.content", targetName, money(review.amount(), code), money(review.fee(), code),
                            money(review.total(), code), money(review.balance(), code)),
                    t("ui.transfer.confirm.accept"), t("ui.common.cancel"))
                    .onConfirm(p -> logic.pay(caller(p), new String[]{targetName, String.valueOf(review.amount()), code}, lines -> sender.accept(p, lines)))
                    .onCancel(this::cancelled)
                    .show(player);
        }, this::cancelled);
    }

    // ---- exchange -------------------------------------------------------------------------------------------------

    private void pickPair(Player player) {
        List<ListMenu.Item> items = new ArrayList<>();
        for (WindowLogic.Pair pair : windows.pairs()) {
            items.add(new ListMenu.Item(t("ui.exchange.pair", name(pair.from()), name(pair.to()), pair.rate().stripTrailingZeros().toPlainString()),
                    icon(ICON_EXCHANGE), p -> exchangeForm(p, pair, null, "")));
        }
        list("pairs", texts(), t("ui.exchange.pairs.title"), t("ui.exchange.pairs.hint"), t("ui.exchange.pairs.empty"), items, this::open).show(player);
    }

    private void exchangeForm(Player player, WindowLogic.Pair pair, Line error, String amount) {
        FormWindowCustom form = new FormWindowCustom(t("ui.exchange.form.title"));
        long have = service.balance(player.getUniqueId(), pair.from()).balance();
        form.addElement(new ElementLabel(label(error, t("ui.exchange.form.label", name(pair.from()), name(pair.to()),
                pair.rate().stripTrailingZeros().toPlainString(), money(have, pair.from())), null)));
        form.addElement(new ElementInput(t("ui.exchange.form.amount", name(pair.from())), t("ui.exchange.form.amountHint"), amount));
        Custom.show(player, form, response -> {
            String typed = response.getInputResponse(1) == null ? "" : response.getInputResponse(1);
            WindowLogic.Review review = windows.exchange(player.getUniqueId(), pair, typed);
            if (!review.ok()) {
                exchangeForm(player, pair, review.error(), typed);
                return;
            }
            new Confirm(t("ui.exchange.confirm.title"),
                    t("ui.exchange.confirm.content", money(review.amount(), pair.from()), money(review.received(), pair.to()),
                            money(review.balance(), pair.from())),
                    t("ui.exchange.confirm.accept"), t("ui.common.cancel"))
                    .onConfirm(p -> logic.exchange(caller(p), new String[]{pair.from(), pair.to(), String.valueOf(review.amount())}, lines -> sender.accept(p, lines)))
                    .onCancel(this::cancelled)
                    .show(player);
        }, this::cancelled);
    }

    // ---- leaderboard ----------------------------------------------------------------------------------------------

    private void pickBoard(Player player) {
        List<String> codes = windows.currencies();
        if (codes.size() == 1) {
            board(player, codes.get(0), this::open);
            return;
        }
        Menu menu = new Menu(t("ui.top.currencies.title"), t("ui.top.currencies.prompt"));
        menu.back(texts(), this::open);
        for (String code : codes) {
            menu.button(name(code), icon(ICON_TOP), p -> board(p, code, this::pickBoard));
        }
        menu.show(player);
    }

    private void board(Player player, String code, Consumer<Player> back) {
        CommandLogic.Board board = logic.board(code, settings.get().rowsMax(), player.getUniqueId());
        List<ListMenu.Item> items = new ArrayList<>();
        for (Line row : board.rows()) {
            items.add(ListMenu.Item.of(text(row), p -> board(p, code, back)));
        }
        String rank = board.selfRank() > 0 ? t("ui.top.rank", board.selfRank()) : t("ui.top.unranked");
        list("top", texts(), t("ui.top.list.title", name(code)), t("ui.top.list.hint", rank), t("ui.top.list.empty"), items, back).show(player);
    }

    // ---- ledger ---------------------------------------------------------------------------------------------------

    private void ledger(Player player) {
        List<CommandLogic.Row> rows = logic.ledgerRows(player.getUniqueId(), settings.get().rowsMax());
        List<ListMenu.Item> items = new ArrayList<>();
        for (CommandLogic.Row row : rows) {
            items.add(new ListMenu.Item(text(row.line()), null, this::ledger, row.time(), row.id()));
        }
        list("ledger", texts(t("ui.ledger.sortNew"), t("ui.ledger.sortOld")), t("ui.ledger.title"), t("ui.ledger.hint", rows.size()),
                t("ui.ledger.empty"), items, this::open).sortByTime().show(player);
    }

    // ---- helpers of the forms -------------------------------------------------------------------------------------

    /** The text above the fields: the reason of the last failure, then the intro, then the balances of the player. */
    private String label(Line error, String intro, Player balancesOf) {
        List<String> lines = new ArrayList<>();
        if (error != null) {
            lines.add(text(error));
        }
        lines.add(intro);
        if (balancesOf != null) {
            for (Line line : logic.balanceRows(balancesOf.getUniqueId())) {
                lines.add(text(line));
            }
        }
        return join(lines);
    }

    private List<String> currencyNames(List<String> codes) {
        List<String> names = new ArrayList<>();
        for (String code : codes) {
            names.add(name(code));
        }
        return names;
    }

    private static int chosen(FormResponseCustom response, int element, int size) {
        if (size <= 1 || response.getDropdownResponse(element) == null) {
            return 0;
        }
        return Math.max(0, Math.min(size - 1, response.getDropdownResponse(element).getElementID()));
    }
}
