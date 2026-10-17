package dev.toka.economy.command;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Turns player names into accounts and back. The server side implements it; tests use a map. */
public interface Names {
    /** Calls back on the main thread; may look on disk first, so the caller must not wait for it. */
    void resolve(String name, Consumer<Optional<UUID>> done);

    /** A readable name for the account: online name, last known name, or a short id. */
    String display(UUID id);
}
