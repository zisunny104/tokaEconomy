package dev.toka.economy.form;

import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.window.FormWindowModal;

import java.util.function.Consumer;

/** A yes or no window with two buttons. Closing the window counts as no. */
public final class Confirm {
    private final FormWindowModal window;
    private Consumer<Player> onConfirm = p -> {
    };
    private Consumer<Player> onCancel = p -> {
    };

    public Confirm(String title, String content, String confirmText, String cancelText) {
        this.window = new FormWindowModal(title, content, confirmText, cancelText);
    }

    public Confirm onConfirm(Consumer<Player> action) {
        this.onConfirm = action;
        return this;
    }

    public Confirm onCancel(Consumer<Player> action) {
        this.onCancel = action;
        return this;
    }

    public void show(Player player) {
        Registry.show(player, window, (p, event) -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseModal response && response.getClickedButtonId() == 0) {
                onConfirm.accept(p);
            } else {
                onCancel.accept(p);
            }
        });
    }
}
