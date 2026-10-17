package dev.toka.economy.form;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.window.FormWindow;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** What to do when a window is answered, by the window itself and the player it was shown to. */
final class Registry {
    private record Entry(Player player, BiConsumer<Player, PlayerFormRespondedEvent> handler) {
    }

    private static final Map<FormWindow, Entry> ENTRIES = Collections.synchronizedMap(new WeakHashMap<>());

    private Registry() {
    }

    static void show(Player player, FormWindow window, BiConsumer<Player, PlayerFormRespondedEvent> handler) {
        ENTRIES.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    /** Drops windows a leaving player never answered; the handlers reach their own window, so the weak keys alone would not free them. */
    static void forget(Player player) {
        synchronized (ENTRIES) {
            ENTRIES.values().removeIf(entry -> entry.player() == player);
        }
    }

    static void answer(PlayerFormRespondedEvent event) {
        Entry entry = ENTRIES.remove(event.getWindow());
        if (entry != null && entry.player() == event.getPlayer()) {
            entry.handler().accept(entry.player(), event);
        }
    }
}
