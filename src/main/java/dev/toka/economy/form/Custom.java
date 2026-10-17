package dev.toka.economy.form;

import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.function.Consumer;

/** A form with fields. Sending it calls {@code onSubmit} with the answers; closing it calls {@code onClose}. */
public final class Custom {
    private Custom() {
    }

    public static void show(Player player, FormWindowCustom window, Consumer<FormResponseCustom> onSubmit, Consumer<Player> onClose) {
        Registry.show(player, window, (p, event) -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) {
                onClose.accept(p);
            } else {
                onSubmit.accept(response);
            }
        });
    }
}
