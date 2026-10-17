package dev.toka.economy.core;

import com.google.gson.JsonObject;

import java.util.Map;

import java.util.List;

/** Where changes go. One call is one atomic batch (accounts and ledger rows of a single operation). Must not block. */
public interface Persist {
    /** Saves the documents, then deletes the keys, in order. */
    void save(Map<String, JsonObject> documents, List<String> deletes);

    default void save(Map<String, JsonObject> documents) {
        save(documents, List.of());
    }
}
