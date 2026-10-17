package dev.toka.economy.core;

import java.util.UUID;

/** Asks whether a player (online or not) has a permission; an unknown or offline player has none. */
@FunctionalInterface
public interface PermissionLookup {
    boolean has(UUID player, String node);
}
