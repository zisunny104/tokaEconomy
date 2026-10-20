package dev.toka.economy.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.*;
import com.djrapitops.plan.extension.annotation.*;
import com.djrapitops.plan.extension.builder.ExtensionDataBuilder;
import dev.toka.economy.Result;
import dev.toka.economy.Status;
import dev.toka.economy.core.EconomyService;
import dev.toka.i18n.Messages;
import java.util.*;

/** Plan invokes providers in the background. Only thread-safe in-memory reads occur here. */
@PluginInfo(name = "tokaEconomy", iconName = "coins")
public final class PlanExtension implements DataExtension, AutoCloseable {
    private final EconomyService economy;
    private final Messages messages;
    private final PlanSettings settings;
    private volatile boolean active = true;
    private ExtensionService registered;

    public PlanExtension(EconomyService economy, Messages messages, PlanSettings settings) {
        this.economy = economy; this.messages = messages; this.settings = settings;
    }
    public boolean register() {
        if (!CapabilityService.getInstance().hasCapability("DATA_EXTENSION_BUILDER_API")) return false;
        ExtensionService service = ExtensionService.getInstance();
        if (service.register(this).isEmpty()) return false;
        registered = service;
        return true;
    }
    @Override public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.PLAYER_PERIODICAL};
    }
    public record Snapshot(boolean account, Map<String, Long> balances) {
        public Snapshot { balances = Map.copyOf(balances); }
    }
    public Snapshot snapshot(UUID player) {
        if (!active) throw new NotReadyException();
        boolean exists = economy.hasAccount(player);
        Map<String, Long> balances = new TreeMap<>();
        if (exists) for (String code : settings.balances()) {
            Result result = economy.balance(player, code);
            if (result.status() == Status.OK) balances.put(code, result.balance());
        }
        return new Snapshot(exists, balances);
    }
    @DataBuilderProvider public ExtensionDataBuilder player(UUID player) {
        Snapshot snapshot = snapshot(player);
        ExtensionDataBuilder builder = newExtensionDataBuilder();
        if (settings.account()) builder.addValue(Boolean.class,
                builder.valueBuilder(messages.text("plan.account")).buildBoolean(snapshot.account()));
        snapshot.balances().forEach((code, value) -> builder.addValue(Long.class,
                builder.valueBuilder(messages.text("plan.balance", code)).buildNumber(value)));
        return builder;
    }
    @Override public void close() {
        active = false;
        if (registered != null) { registered.unregister(this); registered = null; }
    }
}
