package dev.toka.economy.plan;

import java.util.*;

/** Immutable field selection; legacy currency switches remain readable. */
public record PlanSettings(boolean enabled, boolean account, Set<String> balances) {
    public PlanSettings { balances = Set.copyOf(balances); }
    public static PlanSettings parse(Map<String, Object> root) {
        Set<String> known = new HashSet<>();known.add("account");
        if(root.get("currencies") instanceof Map<?,?> currencies)currencies.keySet().forEach(code->known.add("balance."+code));
        Map<?,?> legacy=root.get("plan") instanceof Map<?,?> m?m:Map.of();
        if(legacy.get("balances") instanceof Map<?,?> m)m.keySet().forEach(code->known.add("balance."+code));
        PlanControl.Options options=PlanControl.parse(root,known);
        Set<String> balances = new HashSet<>();
        for(String field:known)if(field.startsWith("balance.") && options.has(field))balances.add(field.substring(8));
        if(!legacy.containsKey("fields") && legacy.get("balances") instanceof Map<?,?> m)m.forEach((code,value)->{if(Boolean.TRUE.equals(value))balances.add(code.toString());else balances.remove(code.toString());});
        return new PlanSettings(options.enabled(),options.has("account"),balances);
    }
}
