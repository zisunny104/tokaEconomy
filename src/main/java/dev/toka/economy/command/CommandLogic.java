package dev.toka.economy.command;

import dev.toka.economy.Cause;
import dev.toka.economy.Result;
import dev.toka.economy.Status;
import dev.toka.economy.core.Codec.LedgerEntry;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Settings;
import dev.toka.i18n.Messages;
import dev.toka.i18n.Pager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * What the commands do, with no server types in it: the arguments come in as text, the answer goes out as
 * {@link Line}s (language key and arguments). Permission checks and sending are the plugin's job.
 */
public final class CommandLogic {
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final EconomyService service;
    private final Names names;
    private final Messages messages;

    public CommandLogic(EconomyService service, Names names, Messages messages) {
        this.service = service;
        this.names = names;
        this.messages = messages;
    }

    /** @return the whole number in the text (commas allowed), or -1 */
    public static long parseAmount(String text) {
        String clean = text == null ? "" : text.replace(",", "");
        return DIGITS.matcher(clean).matches() ? Long.parseLong(clean) : -1;
    }

    // ---- /balance [player] [currency], /economy see [player] [currency] ---------------------------------------------

    /**
     * @param maySeeOthers whether the caller may look at other players' balances (one's own is always allowed)
     */
    public void balance(Caller caller, boolean maySeeOthers, String[] args, Consumer<List<Line>> done) {
        String only = null;
        if (args.length > 1) {
            only = currencyArg(args[1]);
            if (only == null) {
                done.accept(List.of(unknownCurrency(args[1])));
                return;
            }
        }
        String shown = only;
        if (args.length == 0) {
            if (caller.uuid() == null) {
                done.accept(usage("cmd.balance.usage"));
                return;
            }
            service.createAccount(caller.uuid(), caller.name());
            done.accept(balances(caller.uuid(), true, caller.name(), null));
            return;
        }
        names.resolve(args[0], found -> {
            if (found.isEmpty()) {
                done.accept(List.of(Line.of("err.player.notFound", args[0])));
                return;
            }
            UUID id = found.get();
            boolean self = id.equals(caller.uuid());
            if (!self && !maySeeOthers) {
                done.accept(List.of(Line.of("err.noSee")));
            } else if (!service.hasAccount(id)) {
                done.accept(List.of(Line.of("err.noAccount", names.display(id))));
            } else {
                done.accept(balances(id, self, names.display(id), shown));
            }
        });
    }

    private List<Line> balances(UUID id, boolean self, String name, String only) {
        List<Line> lines = new ArrayList<>();
        lines.add(self ? Line.of("msg.balance.self") : Line.of("msg.balance.other", name));
        List<String> codes = new ArrayList<>(service.currencies());
        codes.remove(service.primary());
        codes.add(0, service.primary());
        for (String code : codes) {
            if (only == null || only.equals(code)) {
                lines.add(Line.of("msg.balance.line", service.currencyName(code, language()), service.format(service.balance(id, code).balance(), code, true)));
            }
        }
        return lines;
    }

    // ---- /pay <player> <amount> [currency] -------------------------------------------------------------------------

    public void pay(Caller caller, String[] args, Consumer<List<Line>> done) {
        if (caller.uuid() == null) {
            done.accept(List.of(Line.of("err.playerOnly")));
            return;
        }
        if (args.length < 2 || args.length > 3) {
            done.accept(usage("cmd.pay.usage"));
            return;
        }
        long amount = parseAmount(args[1]);
        if (amount <= 0) {
            done.accept(List.of(Line.of("err.amount")));
            return;
        }
        String code = currencyArg(args.length > 2 ? args[2] : null);
        if (code == null) {
            done.accept(List.of(unknownCurrency(args[2])));
            return;
        }
        names.resolve(args[0], found -> {
            if (found.isEmpty()) {
                done.accept(List.of(Line.of("err.player.notFound", args[0])));
                return;
            }
            UUID target = found.get();
            service.createAccount(caller.uuid(), caller.name());
            if (!service.hasAccount(target)) {
                service.createAccount(target, args[0]);
            }
            Result r = service.transfer(caller.uuid(), target, code, amount, Cause.of("pay", "tokaEconomy"));
            if (!r.ok()) {
                done.accept(List.of(error(r, code, names.display(target))));
                return;
            }
            List<Line> lines = new ArrayList<>();
            String sent = service.format(r.amount(), code, true);
            String balance = service.format(r.balance(), code, true);
            lines.add(r.fee() > 0
                    ? Line.of("msg.pay.sentFee", sent, names.display(target), service.format(r.fee(), code, true), balance)
                    : Line.of("msg.pay.sent", sent, names.display(target), balance));
            lines.add(new Line(target, "msg.pay.received", sent, caller.name()));
            done.accept(lines);
        });
    }

    // ---- /exchange <from> <to> <amount> ----------------------------------------------------------------------------

    public void exchange(Caller caller, String[] args, Consumer<List<Line>> done) {
        if (caller.uuid() == null) {
            done.accept(List.of(Line.of("err.playerOnly")));
            return;
        }
        if (service.settings().rates().isEmpty()) {
            done.accept(List.of(Line.of("msg.exchange.none")));
            return;
        }
        if (args.length == 0) {
            done.accept(rates());
            return;
        }
        if (args.length != 3) {
            done.accept(usage("cmd.exchange.usage"));
            return;
        }
        String from = currencyArg(args[0]);
        String to = currencyArg(args[1]);
        if (from == null || to == null) {
            done.accept(List.of(unknownCurrency(from == null ? args[0] : args[1])));
            return;
        }
        long amount = parseAmount(args[2]);
        if (amount <= 0) {
            done.accept(List.of(Line.of("err.amount")));
            return;
        }
        service.createAccount(caller.uuid(), caller.name());
        Result r = service.exchange(caller.uuid(), from, to, amount, Cause.of("exchange", "tokaEconomy"));
        if (!r.ok()) {
            done.accept(List.of(error(r, from, from + " -> " + to)));
            return;
        }
        done.accept(List.of(Line.of("msg.exchange.done", service.format(r.amount(), from, true), service.format(r.received(), to, true),
                service.format(r.balance(), from, true), service.format(service.balance(caller.uuid(), to).balance(), to, true))));
    }

    // ---- /economy give|take|set <player> <amount> [currency] -------------------------------------------------------

    /** @param kind give, take or set */
    public void adjust(String kind, Caller caller, String[] args, Consumer<List<Line>> done) {
        if (args.length < 2 || args.length > 3) {
            done.accept(usage("cmd.economy." + kind + ".usage"));
            return;
        }
        long amount = parseAmount(args[1]);
        if (amount < 0 || (amount == 0 && !kind.equals("set"))) {
            done.accept(List.of(Line.of("err.amount")));
            return;
        }
        String code = currencyArg(args.length > 2 ? args[2] : null);
        if (code == null) {
            done.accept(List.of(unknownCurrency(args[2])));
            return;
        }
        names.resolve(args[0], found -> {
            if (found.isEmpty()) {
                done.accept(List.of(Line.of("err.player.notFound", args[0])));
                return;
            }
            UUID target = found.get();
            if (!kind.equals("take") && !service.hasAccount(target)) {
                service.createAccount(target, args[0]);
            }
            Cause cause = Cause.of(kind, "admin:" + caller.name());
            Result r = switch (kind) {
                case "give" -> service.deposit(target, code, amount, cause);
                case "take" -> service.withdraw(target, code, amount, cause);
                default -> service.set(target, code, amount, cause);
            };
            if (!r.ok()) {
                done.accept(List.of(error(r, code, names.display(target))));
                return;
            }
            String key = switch (kind) {
                case "give" -> "msg.admin.give";
                case "take" -> "msg.admin.take";
                default -> "msg.admin.set";
            };
            done.accept(List.of(Line.of(key, names.display(target), service.format(amount, code, true), service.format(r.balance(), code, true))));
        });
    }

    // ---- /economy ledger [player] [page] ---------------------------------------------------------------------------

    public void ledger(String[] args, Consumer<List<Line>> done) {
        boolean onlyPage = args.length == 1 && DIGITS.matcher(args[0]).matches();
        int page = Pager.parse(args.length > 1 ? args[1] : (onlyPage ? args[0] : null), 1);
        if (args.length == 0 || onlyPage) {
            done.accept(ledgerPage(null, "", page));
            return;
        }
        names.resolve(args[0], found -> done.accept(found.isEmpty()
                ? List.of(Line.of("err.player.notFound", args[0]))
                : ledgerPage(found.get(), " " + names.display(found.get()), page)));
    }

    private List<Line> ledgerPage(UUID who, String suffix, int page) {
        EconomyService.LedgerPage p = service.ledger(who, page, 10);
        List<Line> lines = new ArrayList<>();
        if (p.total() == 0) {
            lines.add(Line.of("msg.ledger.empty"));
            return lines;
        }
        lines.add(Line.of("msg.ledger.header", p.total()));
        for (LedgerEntry e : p.entries()) {
            lines.add(Line.of("msg.ledger.line", LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(e.time()), service.zone()).format(TIME),
                    party(e.from()), party(e.to()), service.format(e.amount(), e.currency(), true), reason(e.reason()), e.source()));
        }
        if (p.pages() > 1) {
            lines.add(Line.of("ui.page.footer", p.page(), p.pages(), "/economy ledger" + suffix));
        }
        return lines;
    }

    private String party(UUID id) {
        return id == null ? messages.text("status.system") : names.display(id);
    }

    private String reason(String code) {
        String key = "status.reason." + code;
        return messages.has(key) ? messages.text(key) : code;
    }

    // ---- /economy rates --------------------------------------------------------------------------------------------

    public List<Line> rates() {
        Settings s = service.settings();
        List<Line> lines = new ArrayList<>();
        if (s.rates().isEmpty()) {
            lines.add(Line.of("msg.exchange.none"));
            return lines;
        }
        lines.add(Line.of("msg.rates.header"));
        s.rates().forEach((from, targets) -> targets.forEach((to, rate) -> lines.add(Line.of("msg.rates.line",
                service.currencyName(from, language()), service.currencyName(to, language()), rate.stripTrailingZeros().toPlainString()))));
        return lines;
    }

    // ---- /economy top [currency] [page] ----------------------------------------------------------------------------

    public List<Line> top(String[] args) {
        String code = service.primary();
        int page = 1;
        for (String arg : args) {
            if (DIGITS.matcher(arg).matches()) {
                page = Pager.parse(arg, 1);
            } else if (service.isCurrency(arg)) {
                code = arg.toLowerCase(Locale.ROOT);
            } else {
                return List.of(unknownCurrency(arg));
            }
        }
        EconomyService.TopPage p = service.top(code, page, service.settings().topPerPage());
        List<Line> lines = new ArrayList<>();
        lines.add(Line.of("msg.top.header", service.currencyName(code, language())));
        if (p.total() == 0) {
            lines.add(Line.of("msg.top.empty"));
            return lines;
        }
        for (EconomyService.Rank rank : p.entries()) {
            lines.add(Line.of("msg.top.line", rank.rank(), rank.name().isEmpty() ? names.display(rank.uuid()) : rank.name(), service.format(rank.balance(), code, true)));
        }
        if (p.pages() > 1) {
            lines.add(Line.of("ui.page.footer", p.page(), p.pages(), "/economy top " + code));
        }
        return lines;
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private String language() {
        return service.settings().language();
    }

    private List<Line> usage(String key) {
        return List.of(Line.of("err.usage", messages.text(key)));
    }

    /** @return the code of the named currency (the main one when the name is null), or null when it is not one */
    private String currencyArg(String name) {
        if (name == null) {
            return service.primary();
        }
        return service.isCurrency(name) ? name.toLowerCase(Locale.ROOT) : null;
    }

    private Line unknownCurrency(String given) {
        return Line.of("err.currency", given, String.join("、", service.currencies()));
    }

    /** The line for a failed result. */
    public Line error(Result r, String code, String who) {
        return switch (r.status()) {
            case INVALID_AMOUNT -> Line.of("err.amount");
            case UNKNOWN_CURRENCY -> unknownCurrency(code);
            case NO_ACCOUNT -> Line.of("err.noAccount", who);
            case INSUFFICIENT_FUNDS -> Line.of("err.insufficient", service.format(r.amount(), code, true), service.format(r.balance(), code, true));
            case OVERFLOW -> Line.of("err.overflow");
            case SAME_ACCOUNT -> Line.of("err.self");
            case NO_RATE -> Line.of("err.noRate", who);
            case UNAVAILABLE, OK -> Line.of("err.unavailable");
        };
    }
}
