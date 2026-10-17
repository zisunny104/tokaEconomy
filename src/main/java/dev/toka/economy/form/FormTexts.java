package dev.toka.economy.form;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every text the windows show, filled from the language file.
 * Templates use ${0}, ${1}, ${2} for their values.
 *
 * @param optionsHint template with ${0} for the number of items
 * @param sortTime    the second order of a list that sorts by time instead of by name
 * @param searchNone  template with ${0} for the words searched
 * @param pageTitle   template with ${0} for the title, ${1} for the page and ${2} for the number of pages
 */
public record FormTexts(String back, String prevPage, String nextPage, String options, String optionsTitle, String optionsHint,
                        String query, String queryHint, String sort, String sortDefault, String sortName, String sortTime, String perPage,
                        String searchNone, String pageTitle) {
    private static final Pattern ARGUMENT = Pattern.compile("\\$\\{(\\d{1,2})}");

    public String optionsHintFor(int count) {
        return fill(optionsHint, count);
    }

    public String searchNoneFor(String query) {
        return fill(searchNone, query);
    }

    public String pageTitleFor(String title, int page, int pages) {
        return fill(pageTitle, title, page, pages);
    }

    static String fill(String template, Object... values) {
        if (template == null) {
            return "";
        }
        Matcher matcher = ARGUMENT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(index < values.length ? String.valueOf(values[index]) : matcher.group()));
        }
        return matcher.appendTail(out).toString();
    }
}
