package pl.kudlacze.core.hooks;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** {@link Bedrock} na Floodgate API + Cumulus (formularze natywne Bedrock). */
public final class FloodgateBedrock implements Bedrock {

    @Override
    public boolean isBedrock(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    @Override
    public boolean sendButtons(UUID uuid, String title, String content, List<String> buttons, IntConsumer onClick) {
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
        buttons.forEach(form::button);
        form.validResultHandler(response -> onClick.accept(response.clickedButtonId()));
        return FloodgateApi.getInstance().sendForm(uuid, form.build());
    }
}
