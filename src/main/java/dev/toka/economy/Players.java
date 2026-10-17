package dev.toka.economy;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import dev.toka.economy.command.Names;
import dev.toka.economy.core.EconomyService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/** Name lookup on the server: online players, name sources such as tokaProfiles, known accounts, then the player files. */
final class Players implements Names {
    private final Plugin plugin;
    private final Server server;
    private final EconomyService service;
    /** Extra name sources, asked after the online players. TODO: register a tokaProfiles source when it is installed. */
    private final List<Function<String, Optional<UUID>>> sources = new CopyOnWriteArrayList<>();
    private final ExecutorService lookup = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tokaEconomy-lookup");
        thread.setDaemon(true);
        return thread;
    });

    Players(Plugin plugin, EconomyService service) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.service = service;
    }

    /** Online player, name sources, known account. No disk. */
    Optional<UUID> resolveFast(String name) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        for (Function<String, Optional<UUID>> source : sources) {
            Optional<UUID> found = source.apply(name);
            if (found.isPresent()) {
                return found;
            }
        }
        return service.findByName(name);
    }

    @Override
    public void resolve(String name, Consumer<Optional<UUID>> done) {
        Optional<UUID> fast = resolveFast(name);
        if (fast.isPresent()) {
            done.accept(fast);
            return;
        }
        try {
            lookup.execute(() -> {
                Optional<UUID> found;
                try {
                    found = server.lookupName(name);
                } catch (RuntimeException e) {
                    found = Optional.empty();
                }
                Optional<UUID> result = found;
                try {
                    server.getScheduler().scheduleTask(plugin, () -> done.accept(result));
                } catch (RuntimeException ignored) {
                    // the plugin stopped while the lookup ran
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            done.accept(Optional.empty());
        }
    }

    @Override
    public String display(UUID id) {
        Optional<Player> online = server.getPlayer(id);
        if (online.isPresent()) {
            return online.get().getName();
        }
        return service.nameOf(id).orElse(id.toString().substring(0, 8));
    }

    void stop() {
        lookup.shutdownNow();
    }
}
