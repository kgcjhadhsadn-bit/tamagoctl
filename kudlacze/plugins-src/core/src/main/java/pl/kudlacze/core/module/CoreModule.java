package pl.kudlacze.core.module;

/**
 * Moduł pluginu core. Każdy moduł włącza się osobno w config.yml (sekcja {@code moduly}),
 * dzięki czemu ten sam jar działa na lobby, survivalu i seasons z innym zestawem funkcji.
 */
public interface CoreModule {

    /** Klucz modułu w sekcji {@code moduly} w config.yml. */
    String id();

    /** Rejestracja listenerów, komend i zadań. Wywoływane po załadowaniu światów. */
    void enable();

    /** Sprzątanie (zatrzymanie zadań, zapis stanu). */
    default void disable() {
    }
}
