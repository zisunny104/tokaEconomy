package dev.toka.economy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/** Button labels carry no colour code, except a leading red one on close/cancel. */
public class ButtonColourTest {
    private static final String[] BUTTON_KEYS = {
            "ui.button.options", "ui.button.transfer", "ui.button.exchange", "ui.button.top", "ui.button.ledger",
            "ui.transfer.confirm.accept", "ui.transfer.offline.item", "ui.exchange.confirm.accept",
            "ui.admin.button.adjust", "ui.admin.button.see", "ui.admin.button.ledger", "ui.admin.button.rates",
            "ui.admin.button.reload", "ui.admin.confirm.accept", "ui.admin.reload.accept",
            "ui.button.close", "ui.common.cancel"};

    @Test
    @SuppressWarnings("unchecked")
    public void buttonLabelsHaveNoColour() throws IOException {
        try (var in = Files.newBufferedReader(Path.of("src", "main", "resources", "lang", "zh-TW.yml"), StandardCharsets.UTF_8)) {
            Object root = new org.yaml.snakeyaml.Yaml().load(in);
            for (String key : BUTTON_KEYS) {
                Object value = root;
                for (String part : key.split("\\.")) value = ((Map<String, Object>) value).get(part);
                String text = String.valueOf(value);
                String rest = text.startsWith("§c") ? text.substring(2) : text;
                assertTrue(key + " = " + text, !rest.contains("§"));
            }
        }
    }
}
