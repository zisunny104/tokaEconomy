package dev.toka.economy.command;

import dev.toka.i18n.Messages;
import dev.toka.i18n.Pager;

import java.util.ArrayList;
import java.util.List;

/** The text of a help command: the first line of the language list is the title, the rest is cut into pages. */
public final class Help {
    private Help() {
    }

    /**
     * @param key     language list such as help.economy
     * @param command what the page footer tells to type, such as /economy help
     * @param page    a page number, "all" for everything, or null
     * @return the lines to send
     */
    public static List<String> lines(Messages messages, String key, String command, String page, int perPage) {
        List<String> all = messages.lines(key);
        if (all.isEmpty()) {
            return all;
        }
        List<String> body = all.subList(1, all.size());
        boolean everything = "all".equalsIgnoreCase(page);
        Pager.Page<String> p = Pager.of(body, everything ? Math.max(1, body.size()) : Math.max(3, perPage), Pager.parse(page, 1));
        List<String> out = new ArrayList<>();
        out.add(all.get(0));
        out.addAll(p.lines());
        if (p.pages() > 1) {
            out.add(messages.text("ui.page.footer", p.page(), p.pages(), command));
        }
        return out;
    }
}
