package dev.toka.economy;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.Result;
import dev.toka.i18n.Messages;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Balance lines for the tokaDisplay sidebar. tokaDisplay is a soft dependency and is only reached by reflection. */
final class Sidebar {
    static final String OWNER = "tokaEconomy";
    private static final String DISPLAY = "tokaDisplay";

    private final Supplier<EconomyService> service;
    private final Messages messages;

    Sidebar(Supplier<EconomyService> service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    /** sidebar.show of the config; true when missing. */
    static boolean show(Map<?, ?> root) {
        Object block = root.get("sidebar");
        return !(block instanceof Map<?, ?> map) || !Boolean.FALSE.equals(map.get("show"));
    }

    /** The line of the currency for the player, or null when there is no account or the service cannot answer. Memory only. */
    static String line(EconomyService service, Messages messages, String code, UUID id) {
        if (service == null || id == null || !service.isCurrency(code)) {
            return null;
        }
        Result result = service.balance(id, code);
        if (!result.ok()) {
            return null;
        }
        String name = service.currencyName(code, service.settings().language());
        String key = "ui.sidebar." + code;
        if (!messages.has(key)) {
            key = "ui.sidebar.other";
        }
        return messages.text(key, name, service.format(result.balance(), code, false));
    }

    /** Registers one section per currency; false when tokaDisplay is missing, too old or not ready. */
    boolean register(Plugin display) {
        EconomyService current = service.get();
        if (display == null || !display.isEnabled() || current == null) {
            return false;
        }
        try {
            Method method = display.getClass().getMethod("registerSection", String.class, String.class, Function.class);
            for (String code : current.currencies()) {
                Function<Player, String> supplier = player -> line(service.get(), messages, code, player.getUniqueId());
                method.invoke(null, OWNER, code, supplier);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    void unregister(Plugin display) {
        if (display == null) {
            return;
        }
        try {
            display.getClass().getMethod("unregisterSections", String.class).invoke(null, OWNER);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // an old or stopped tokaDisplay has nothing to remove
        }
    }

    static String displayName() {
        return DISPLAY;
    }
}
