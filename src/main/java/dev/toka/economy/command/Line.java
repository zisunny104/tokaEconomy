package dev.toka.economy.command;

import java.util.UUID;

/**
 * One line of a command's answer, as a language key and its arguments. The command logic returns these and does not
 * send anything, so a window can show the same answer.
 *
 * @param to the player who gets the line, or null for the one who ran the command
 */
public record Line(UUID to, String key, Object... args) {
    public static Line of(String key, Object... args) {
        return new Line(null, key, args);
    }
}
