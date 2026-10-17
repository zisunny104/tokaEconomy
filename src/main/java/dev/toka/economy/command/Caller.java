package dev.toka.economy.command;

import java.util.UUID;

/** Who runs a command; the uuid is null for the console. */
public record Caller(UUID uuid, String name) {
}
