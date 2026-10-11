package elo.mainplugins.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

/**
 * Opcjonalny kontrakt custom mobów z Kreatora mobów - implementuje go i rejestruje w ServicesManager
 * mainplugins-mobs. Inne pluginy (np. Spawnery) spawnują przez niego moby zrobione w aplikacji
 * i pokazują ich miniaturę. Zwracany przez CoreAPI.getCustomMobService() jako null, gdy pluginu
 * mobów nie ma albo nie ma licencji - wołający musi mieć na to fallback.
 */
public interface CustomMobService {

    /** Id wszystkich mobów z Kreatora wczytanych na serwerze (małymi literami). */
    Set<String> ids();

    /** Nazwa moba z Kreatora; null, gdy takiego id nie ma. */
    String displayName(String id);

    /**
     * Nowy mob w tym miejscu - z modelem, animacjami, umiejętnościami i dropami z Kreatora.
     * Zwraca jego ciało (to ono dostaje obrażenia i to jego dotyczy EntityDeathEvent); null = nie ma takiego moba.
     */
    LivingEntity spawn(String id, Location at);

    /** Czy encja to ciało custom moba (dowolnego). */
    boolean isCustomMob(Entity entity);

    /** Usuwa custom moba razem z modelem (bez dropów). Dla zwykłej encji - zwykłe remove(). */
    void remove(Entity entity);

    /**
     * Miniatura modelu (same części, bez AI i hitboxów, nie zapisywana w świecie) - np. kręcąca się
     * w klatce spawnera. feet = punkt pod stopami modelu, size = maksymalny rozmiar w blokach.
     * Null, gdy nie ma takiego moba.
     */
    Preview preview(String id, Location feet, float size);

    /** Miniatura moba - obracana przez wołającego. */
    interface Preview {
        /** Obrót wokół pionowej osi (stopnie); ticks = ile ticków gra wygładza przejście. */
        void spin(float yawDegrees, int ticks);

        /** Czy wszystkie części wciąż istnieją (np. nie zniknęły z wyładowanym chunkiem). */
        boolean valid();

        void remove();
    }
}
