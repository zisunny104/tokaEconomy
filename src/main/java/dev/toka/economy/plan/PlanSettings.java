package dev.toka.economy.plan;

import java.util.*;

/** Immutable disclosure settings; a currency must be explicitly enabled. */
public record PlanSettings(boolean enabled, boolean account, Set<String> balances) {
    public PlanSettings { balances = Set.copyOf(balances); }
    public static PlanSettings parse(Map<String, Object> root) {
        Map<?, ?> plan = root.get("plan") instanceof Map<?, ?> m ? m : Map.of();
        Set<String> balances = new HashSet<>();
        if (plan.get("balances") instanceof Map<?, ?> m) m.forEach((key, value) -> {
            if (Boolean.TRUE.equals(value)) balances.add(key.toString());
        });
        return new PlanSettings(Boolean.TRUE.equals(plan.get("enabled")), Boolean.TRUE.equals(plan.get("account")), balances);
    }
}
