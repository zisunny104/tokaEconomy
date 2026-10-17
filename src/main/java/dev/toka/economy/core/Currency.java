package dev.toka.economy.core;

import java.util.Map;

/**
 * @param code    unchanging code, [a-z0-9]{2,16}
 * @param names   language to display name; may be empty
 * @param initial balance of a new account
 * @param feeMin  minimum transfer fee, or -1 to use the general one
 */
public record Currency(String code, String symbol, Map<String, String> names, long initial, long feeMin) {
}
