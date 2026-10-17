package dev.toka.economy;

import dev.toka.economy.core.Reasons;
import dev.toka.i18n.Messages;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class LangFileTest {
    private static final Pattern KEY = Pattern.compile("[\"]((?:cmd|help|msg|err|ui|log|status)[.][A-Za-z0-9_.]*[A-Za-z0-9_])[\"]");

    @Test
    public void everyKeyInTheFileCanBeRead() throws IOException {
        Set<String> keys = fileKeys();
        assertTrue(keys.size() > 50);
        List<String> warnings = new ArrayList<>();
        Messages messages = messages(warnings);
        for (String key : keys) {
            assertTrue("missing " + key, messages.has(key));
            assertFalse("empty " + key, messages.lines(key, "a", "b", "c", "d", "e", "f").isEmpty());
        }
        assertTrue(warnings.toString(), warnings.isEmpty());
    }

    @Test
    public void everyKeyUsedBySourceExistsInTheFile() throws IOException {
        Set<String> keys = fileKeys();
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertFalse(used.isEmpty());
        for (String key : used) {
            assertTrue("source uses a key missing from zh-TW.yml: " + key, keys.contains(key));
        }
    }

    @Test
    public void keysBuiltFromPartsExist() throws IOException {
        Set<String> keys = fileKeys();
        for (String reason : Reasons.ALL) {
            assertTrue("status.reason." + reason, keys.contains("status.reason." + reason));
        }
        for (String kind : List.of("give", "take", "set")) {
            assertTrue(keys.contains("cmd.economy." + kind + ".usage"));
        }
    }

    @Test
    public void helpListsStartWithATitleAndNameTheirCommands() {
        Messages messages = messages(new ArrayList<>());
        for (String list : List.of("help.balance", "help.pay", "help.exchange", "help.economy")) {
            List<String> lines = messages.lines(list);
            assertTrue(list, lines.size() >= 3);
            assertTrue(list, lines.get(0).startsWith("§6=== §f§l") && lines.get(0).contains("§6==="));
            assertTrue(list, lines.get(1).startsWith("§e/"));
        }
        String all = String.join("\n", messages.lines("help.economy"));
        for (String command : List.of("/economy top", "/economy see", "/economy give", "/economy take", "/economy set", "/economy ledger",
                "/economy rates", "/economy reload", "/economy vacuum", "/exchange", "/pay", "/balance", "/topmoney", "/seemoney",
                "/givemoney", "/takemoney", "/setmoney")) {
            assertTrue(command, all.contains(command));
        }
        assertFalse(all.contains("/baltop"));
    }

    @Test
    public void currencyNamesAreNotInTheLanguageFile() throws IOException {
        String text = Files.readString(Path.of("src", "main", "resources", "lang", "zh-TW.yml"), StandardCharsets.UTF_8);
        assertFalse(text.contains("銀幣"));
        assertFalse(text.contains("金幣"));
    }

    private Messages messages(List<String> warnings) {
        return new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", warnings::add);
    }

    private Set<String> fileKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        Object root = new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of("src", "main", "resources", "lang", "zh-TW.yml"), StandardCharsets.UTF_8));
        flatten("", (Map<?, ?>) root, keys);
        return keys;
    }

    private static void flatten(String prefix, Map<?, ?> map, Set<String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key, child, out);
            } else {
                out.add(key);
            }
        }
    }
}
