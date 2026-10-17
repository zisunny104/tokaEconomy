package dev.toka.economy.core;

import com.google.gson.JsonObject;

import java.util.Map;

/** Where changes go. One call is one atomic batch (accounts and ledger rows of a single operation). Must not block. */
public interface Persist {
    void save(Map<String, JsonObject> documents);
}
