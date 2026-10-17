package dev.toka.economy.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A window with buttons: each button is added together with the action it does, and the answer is found by the number of the button. */
public final class Menu {
    private final FormWindowSimple window;
    private final List<Consumer<Player>> actions = new ArrayList<>();

    public Menu(String title, String content) {
        this.window = new FormWindowSimple(title, content == null ? "" : content);
    }

    public Menu button(String text, Consumer<Player> action) {
        return button(text, null, action);
    }

    /** @param icon a path inside the game, such as textures/items/compass_item; null or empty for no icon */
    public Menu button(String text, String icon, Consumer<Player> action) {
        ElementButton button = icon == null || icon.isEmpty()
                ? new ElementButton(text)
                : new ElementButton(text, new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, icon));
        window.addButton(button);
        actions.add(action);
        return this;
    }

    public Menu back(FormTexts texts, Consumer<Player> target) {
        return button(texts.back(), target);
    }

    public void show(Player player) {
        Registry.show(player, window, (p, event) -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseSimple response)) return;
            int id = response.getClickedButtonId();
            if (id >= 0 && id < actions.size() && actions.get(id) != null) actions.get(id).accept(p);
        });
    }
}
