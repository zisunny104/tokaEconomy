package dev.toka.economy;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.PluginIdentifiableCommand;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.event.plugin.PluginDisableEvent;
import dev.toka.economy.plan.PlanSettings;
import dev.toka.economy.plan.PlanExtension;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.Config;
import dev.toka.economy.command.Caller;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Help;
import dev.toka.economy.command.Line;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Reasons;
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
    private Sidebar sidebar;
    private volatile boolean sidebarShow = true;
    private PlanSettings planSettings;
    private AutoCloseable plan;
    private dev.toka.economy.plan.PlanControl planControl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        suggest("balance",
                new CommandParameter[]{CommandParameter.newType("玩家", true, CommandParamType.TARGET), CommandParameter.newType("貨幣", true, CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"help"})});
        suggest("pay", new CommandParameter[]{CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("金額", CommandParamType.FLOAT), CommandParameter.newType("貨幣", true, CommandParamType.STRING)});
        suggest("exchange", new CommandParameter[]{CommandParameter.newType("來源貨幣", CommandParamType.STRING), CommandParameter.newType("目標貨幣", CommandParamType.STRING), CommandParameter.newType("金額", CommandParamType.FLOAT)});
        suggest("economy",
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"help","top","see","give","take","set","reload","vacuum","ledger","rates","legacy","plan"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"legacy"}), CommandParameter.newEnum("交接", new String[]{"list","drop","claim"}), CommandParameter.newType("舊名稱", true, CommandParamType.STRING), CommandParameter.newType("玩家", true, CommandParamType.TARGET)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"give","take","set"}), CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("金額", CommandParamType.FLOAT), CommandParameter.newType("貨幣", true, CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"see","balance"}), CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("貨幣", true, CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"plan"}), CommandParameter.newEnum("Plan動作", new String[]{"status","mode","fields"}), CommandParameter.newType("設定", true, CommandParamType.RAWTEXT)});
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
        sidebar = new Sidebar(() -> service, messages);
        sidebarShow = Sidebar.show(getConfig().getAll());
        planSettings = PlanSettings.parse(getConfig().getAll());
        Economy.install(service, players);
        getServer().getPluginManager().registerEvents(this, this);
        registerExtraCommands(service.settings().compatCommands());
        refreshSidebar();
        refreshPlan();
        getServer().getScheduler().scheduleTask(this, this::connectLegacy);
        getServer().getScheduler().scheduleDelayedTask(this, this::refreshSidebar, 40);
        getServer().getScheduler().scheduleDelayedRepeatingTask(this, this::cleanup, 20 * 30, DAY_TICKS);
        getLogger().info(messages.text("log.ready", service.currencies().size(), service.ledgerSize()));
        if (service.legacyCount() > 0) {
            getLogger().info(messages.text("log.legacyPending", service.legacyCount()));
        }
    }

    private void suggest(String name, CommandParameter[]... forms) {
        Command registered = (Command) getCommand(name);
        if (registered == null) return;
        registered.getCommandParameters().clear();
        for (int i = 0; i < forms.length; i++) registered.addCommandParameters("choice" + i, forms[i]);
    }

    @Override
    public void onDisable() {
        closePlan();
        if (sidebar != null) {
            sidebar.unregister(getServer().getPluginManager().getPlugin(Sidebar.displayName()));
        }
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
    public void onPluginEnable(PluginEnableEvent event) {
        if ("Plan".equals(event.getPlugin().getName())) refreshPlan();
        if (Sidebar.displayName().equals(event.getPlugin().getName())) {
            refreshSidebar();
        }
    }

    @EventHandler public void onPluginDisable(PluginDisableEvent event) {
        if ("Plan".equals(event.getPlugin().getName())) closePlan();
    }

    private void closePlan() {
        if(planControl!=null)planControl.registered(false);
        if (plan == null) return;
        try { plan.close(); }
        catch (Exception | LinkageError error) { getLogger().warning(messages.text("plan.unavailable", error.getClass().getSimpleName())); }
        finally { plan = null; }
    }

    private void refreshPlan() {
        closePlan();
        if(planControl==null)planControl=new dev.toka.economy.plan.PlanControl(this,this::refreshPlan,()->{var ids=new java.util.HashSet<String>();ids.add("account");service.currencies().forEach(code->ids.add("balance."+code));return ids;},(key,args)->messages.text(key,args),"economy");
        planSettings=PlanSettings.parse(getConfig().getAll());
        cn.nukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("Plan");
        if (!planControl.shouldRegister()) return;
        try {
            PlanExtension extension = new PlanExtension(service, messages, planSettings);
            if (extension.register()) {
                plan = extension;planControl.registered(true);
                getLogger().info(messages.text("plan.ready"));
            } else getLogger().warning(messages.text("plan.unsupported"));
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning(messages.text("plan.unavailable", error.getClass().getSimpleName()));
        }
    }

    /** Registers the balance sections (or removes them when sidebar.show is false); safe to call again at any time. */
    private void refreshSidebar() {
        if (sidebar == null) {
            return;
        }
        cn.nukkit.plugin.Plugin display = getServer().getPluginManager().getPlugin(Sidebar.displayName());
        sidebar.unregister(display);
        if (sidebarShow) {
            sidebar.register(display);
        }
    }

    private boolean listeningLegacy;

    /** The profile service decides who an old name is; the balances of an old account move only when it says so. */
    private void connectLegacy() {
        cn.nukkit.plugin.Plugin profiles = getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profiles == null || !profiles.isEnabled()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked") Map<String, UUID> resolved = (Map<String, UUID>) profiles.getClass().getMethod("legacyResolved").invoke(null);
            resolved.forEach(this::takeLegacy);
            if (!listeningLegacy) {
                profiles.getClass().getMethod("onLegacyResolved", java.util.function.BiConsumer.class).invoke(null,
                        (java.util.function.BiConsumer<String, UUID>) (name, uuid) -> getServer().getScheduler().scheduleTask(this, () -> takeLegacy(name, uuid)));
                listeningLegacy = true;
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            getLogger().warning("無法連上 tokaProfiles 的舊名稱取回：" + unavailable.getClass().getSimpleName());
        }
    }

    private void takeLegacy(String name, UUID uuid) {
        Player online = getServer().getOnlinePlayers().get(uuid);
        EconomyService.Claim claim = service.claimLegacy(name, uuid, online == null ? name : online.getName(), new Cause(Reasons.LEGACY_CLAIM, "tokaEconomy"));
        if (claim.status() == Status.OK) {
            String summary = logic.legacySummary(claim.legacy());
            getLogger().info(messages.text("log.legacyClaimed", name, online == null ? name : online.getName(), summary));
            if (online != null) {
                send(online, List.of(Line.of("msg.legacy.joined", summary)));
            }
        }
    }

    @EventHandler
    public void onProfilesEnabled(cn.nukkit.event.plugin.PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaProfiles")) {
            listeningLegacy = false;
            getServer().getScheduler().scheduleTask(this, this::connectLegacy);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.enter(player.getUniqueId(), player.getName());
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

    public String planStatusJson(){return planControl.statusJson();}
    public boolean configurePlan(String mode,String[] fields){return planControl.configure(mode,fields);}
    private void economy(CommandSender sender, String[] args) {
        if(planControl!=null && planControl.command(sender,args,PERM_ADMIN))return;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            run(sender, "help", args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            return;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "top", "see", "give", "take", "set", "reload", "vacuum", "ledger", "rates", "legacy" -> run(sender, sub, rest);
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
            case "give", "take", "set", "reload", "vacuum", "ledger", "rates", "legacy" -> true;
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
            case "legacy" -> logic.legacy(caller, args, out);
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

    private record Loaded(Settings settings, WindowSettings windows, boolean sidebar, PlanSettings plan) {
    }

    private void reload(CommandSender sender) {
        File file = new File(getDataFolder(), "config.yml");
        getServer().getScheduler().scheduleAsyncTask(this, new AsyncTask() {
            @Override
            public void onRun() {
                try {
                    Map<String, Object> root = new Config(file, Config.YAML).getAll();
                    setResult(new Loaded(Settings.parse(root), WindowSettings.parse(root), Sidebar.show(root), PlanSettings.parse(root)));
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
                    sidebarShow = loaded.sidebar();
                    planSettings = loaded.plan();
                    refreshSidebar();
                    refreshPlan();
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
