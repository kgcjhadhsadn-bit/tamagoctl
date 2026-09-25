package pl.kudlacze.core.hooks;

import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Wykrywanie graczy Bedrock (Floodgate) i wysyłanie im natywnych formularzy zamiast GUI ze skrzyni.
 * Implementacja {@link FloodgateBedrock} jest ładowana tylko, gdy plugin floodgate jest włączony.
 */
public interface Bedrock {

    boolean isBedrock(UUID uuid);

    /** Formularz z listą przycisków; callback dostaje indeks klikniętego przycisku. */
    boolean sendButtons(UUID uuid, String title, String content, List<String> buttons, IntConsumer onClick);

    Bedrock NONE = new Bedrock() {
        @Override
        public boolean isBedrock(UUID uuid) {
            return false;
        }

        @Override
        public boolean sendButtons(UUID uuid, String title, String content, List<String> buttons, IntConsumer onClick) {
            return false;
        }
    };
}
