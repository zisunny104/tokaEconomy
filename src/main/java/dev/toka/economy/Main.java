package dev.toka.economy;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginIdentifiableCommand;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.Config;
import dev.toka.economy.command.Caller;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Help;
import dev.toka.economy.command.Line;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Settings;
import dev.toka.economy.storage.DocPersist;
import dev.toka.economy.window.EconomyWindows;
import dev.toka.economy.window.WindowSettings;
import dev.toka.i18n.Messages;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class Main extends PluginBase implements Listener {
    private static final String PERM_ADMIN = "tokaEconomy.admin";
    private static final String PERM_SEE = "tokaEconomy.see";
    private static final String PERM_TOP = "tokaEconomy.top";
    private static final int DAY_TICKS = 20 * 60 * 60 * 24;

    private Messages messages;
    private EconomyService service;
    private DocPersist persist;
    private Players players;
    private CommandLogic logic;
    private EconomyWindows windows;
    private volatile WindowSettings windowSettings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(getClass().getClassLoader(), getDataFolder().toPath(), getConfig().getString("language", "zh-TW"), line -> getLogger().warning(line));
        try {
            Settings settings = Settings.parse(getConfig().getAll());
            persist = DocPersist.open(getDataFolder().toPath().resolve("storage").resolve("sqlite"),
                    (key, error) -> getLogger().error(messages.text(key), error));
            service = new EconomyService(settings, persist, Clock.systemDefaultZone(),
                    (id, node) -> getServer().getPlayer(id).map(p -> p.hasPermission(node)).orElse(false), persist.snapshot());
        } catch (IllegalArgumentException e) {
            getLogger().error(messages.text("log.configInvalid", e.getMessage()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (Exception e) {
            getLogger().error(messages.text("log.storeFailed"), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        players = new Players(this, service);
        logic = new CommandLogic(service, players, messages);
        windowSettings = WindowSettings.parse(getConfig().getAll());
        windows = new EconomyWindows(service, logic, messages, () -> windowSettings, this::send);
        EconomyWindows.register(this);
        Economy.install(service, players);
        getServer().getPluginManager().registerEvents(this, this);
        registerExtraCommands(service.settings().compatCommands());
        getServer().getScheduler().scheduleDelayedRepeatingTask(this, this::cleanup, 20 * 30, DAY_TICKS);
        getLogger().info(messages.text("log.ready", service.currencies().size(), service.ledgerSize()));
    }

    @Override
    public void onDisable() {
        Economy.uninstall();
        if (players != null) {
            players.stop();
        }
        if (persist != null) {
            try {
                if (!persist.closeAfterDrain(10_000)) {
                    getLogger().error(messages.text("log.drainTimeout"));
                }
            } catch (Exception e) {
                getLogger().error(messages.text("log.storeFailed"), e);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.createAccount(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    // ---- ledger clean-up ------------------------------------------------------------------------------------------

    private void cleanup() {
        persist.cleanup(service).whenComplete((count, error) -> {
            if (error == null && count > 0) {
                getLogger().info(messages.text("log.cleanupDone", count));
            }
        });
    }

    // ---- commands -------------------------------------------------------------------------------------------------

    /** A name that is not in plugin.yml: registered only when nobody else has it. */
    private final class Extra extends Command {
        private final String action;

        Extra(String name, String action, String permission) {
            super(name);
            this.action = action;
            setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            run(sender, action, args);
            return true;
        }
    }

    private void registerExtraCommands(boolean compat) {
        extra("bal", "balance", "tokaEconomy.balance");
        extra("money", "balance", "tokaEconomy.balance");
        extra("topmoney", "top", PERM_TOP);
        if (compat) {
            extra("seemoney", "see", PERM_SEE);
            extra("givemoney", "give", PERM_ADMIN);
            extra("takemoney", "take", PERM_ADMIN);
            extra("setmoney", "set", PERM_ADMIN);
        }
    }

    private void extra(String name, String action, String permission) {
        Command owner = getServer().getCommandMap().getCommand(name);
        if (owner != null) {
            String who = owner instanceof PluginIdentifiableCommand p ? p.getPlugin().getName() : "server";
            getLogger().warning(messages.text("log.commandSkipped", name, who));
            return;
        }
        getServer().getCommandMap().register("tokaeconomy", new Extra(name, action, permission), name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "balance" -> run(sender, "balance", args);
            case "pay" -> run(sender, "pay", args);
            case "exchange" -> run(sender, "exchange", args);
            case "economy" -> economy(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void economy(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            run(sender, "help", args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            return;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "top", "see", "give", "take", "set", "reload", "vacuum", "ledger", "rates" -> run(sender, sub, rest);
            default -> send(sender, List.of(Line.of("err.usage", messages.text("cmd.economy.usage"))));
        }
    }

    /** Checks the permission of the action, runs it and sends the answer. */
    private void run(CommandSender sender, String action, String[] args) {
        if (service == null) {
            sender.sendMessage(messages.text("err.unavailable"));
            return;
        }
        boolean admin = sender.hasPermission(PERM_ADMIN);
        boolean adminOnly = switch (action) {
            case "give", "take", "set", "reload", "vacuum", "ledger", "rates" -> true;
            default -> false;
        };
        if (adminOnly && !admin) {
            sender.sendMessage(messages.text("err.noPermission"));
            return;
        }
        Caller caller = new Caller(sender instanceof Player p ? p.getUniqueId() : null, sender.getName());
        Consumer<List<Line>> out = lines -> send(sender, lines);
        if (args.length > 0 && args[0].equalsIgnoreCase("help") && !action.equals("help")) {
            sendHelp(sender, action, args.length > 1 ? args[1] : null);
            return;
        }
        if (action.equals("balance") && args.length == 0 && sender instanceof Player player) {
            windows.open(player);
            return;
        }
        switch (action) {
            case "balance", "see" -> logic.balance(caller, sender.hasPermission(PERM_SEE), args, out);
            case "pay" -> logic.pay(caller, args, out);
            case "exchange" -> logic.exchange(caller, args, out);
            case "give", "take", "set" -> logic.adjust(action, caller, args, out);
            case "ledger" -> logic.ledger(args, out);
            case "rates" -> out.accept(logic.rates());
            case "top" -> out.accept(logic.top(args));
            case "reload" -> reload(sender);
            case "vacuum" -> vacuum(sender);
            case "help" -> sendHelp(sender, "economy", args.length > 0 ? args[0] : null);
            default -> out.accept(List.of(Line.of("err.usage", messages.text("cmd.economy.usage"))));
        }
    }

    private void sendHelp(CommandSender sender, String action, String page) {
        String key = switch (action) {
            case "balance", "see" -> "help.balance";
            case "pay" -> "help.pay";
            case "exchange" -> "help.exchange";
            default -> "help.economy";
        };
        String command = switch (action) {
            case "balance", "see" -> "/balance help";
            case "pay" -> "/pay help";
            case "exchange" -> "/exchange help";
            default -> "/economy help";
        };
        for (String line : Help.lines(messages, key, command, page, service.settings().helpLines())) {
            sender.sendMessage(line);
        }
    }

    private void send(CommandSender sender, List<Line> lines) {
        for (Line line : lines) {
            CommandSender target = sender;
            if (line.to() != null) {
                target = getServer().getPlayer(line.to()).orElse(null);
                if (target == null) {
                    continue;
                }
            }
            String text = messages.text(line.key(), line.args());
            target.sendMessage(withPrefix(line.key()) ? messages.text("ui.prefix") + text : text);
        }
    }

    private static boolean withPrefix(String key) {
        return !(key.endsWith(".line") || key.endsWith(".header") || key.startsWith("ui.") || key.startsWith("msg.balance.")
                || key.equals("msg.top.empty") || key.equals("msg.ledger.empty"));
    }

    private record Loaded(Settings settings, WindowSettings windows) {
    }

    private void reload(CommandSender sender) {
        File file = new File(getDataFolder(), "config.yml");
        getServer().getScheduler().scheduleAsyncTask(this, new AsyncTask() {
            @Override
            public void onRun() {
                try {
                    Map<String, Object> root = new Config(file, Config.YAML).getAll();
                    setResult(new Loaded(Settings.parse(root), WindowSettings.parse(root)));
                } catch (RuntimeException e) {
                    setResult(e);
                }
            }

            @Override
            public void onCompletion(cn.nukkit.Server server) {
                Object result = getResult();
                if (result instanceof Loaded loaded) {
                    Settings settings = loaded.settings();
                    service.settings(settings);
                    windowSettings = loaded.windows();
                    sender.sendMessage(messages.text("ui.prefix") + messages.text("msg.reload.ok"));
                    getLogger().info(messages.text("log.reloaded", settings.currencies().size()));
                } else {
                    sender.sendMessage(messages.text("ui.prefix") + messages.text("err.reload", ((Exception) result).getMessage()));
                }
            }
        });
    }

    private void vacuum(CommandSender sender) {
        sender.sendMessage(messages.text("ui.prefix") + messages.text("msg.vacuum.started"));
        persist.cleanup(service).whenComplete((count, error) -> getServer().getScheduler().scheduleTask(this, () ->
                sender.sendMessage(messages.text("ui.prefix") + (error == null ? messages.text("msg.vacuum.done", count) : messages.text("err.vacuum")))));
    }
}
