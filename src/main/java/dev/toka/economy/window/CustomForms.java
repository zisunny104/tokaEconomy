package dev.toka.economy.window;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Hands the answer of a custom form to what was bound to it (tokaForms has no custom form). Closing the form calls {@code onClose}. */
final class CustomForms implements Listener {
    private record Entry(Player player, Consumer<FormResponseCustom> onSubmit, Consumer<Player> onClose) {
    }

    private static final Map<FormWindow, Entry> ENTRIES = Collections.synchronizedMap(new WeakHashMap<>());

    private CustomForms() {
    }

    static void register(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new CustomForms(), plugin);
    }

    static void show(Player player, FormWindowCustom window, Consumer<FormResponseCustom> onSubmit, Consumer<Player> onClose) {
        ENTRIES.put(window, new Entry(player, onSubmit, onClose));
        player.showFormWindow(window);
    }

    @EventHandler
    public void onRespond(PlayerFormRespondedEvent event) {
        Entry entry = ENTRIES.remove(event.getWindow());
        if (entry == null || entry.player() != event.getPlayer()) {
            return;
        }
        if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) {
            entry.onClose().accept(entry.player());
        } else {
            entry.onSubmit().accept(response);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synchronized (ENTRIES) {
            ENTRIES.values().removeIf(entry -> entry.player() == event.getPlayer());
        }
    }
}
