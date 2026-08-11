package elo.mainplugins.core.api;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Opcjonalny kontrakt tytułów na czacie - implementuje go i rejestruje w ServicesManager
 * mainplugins-quests. Tytuł to na razie efekt uboczny ukończenia konkretnego questu Głównej
 * Ścieżki (patrz QuestManager) - zero osobnej persystencji, dane już siedzą w quests.yml.
 * mainplugins-ranks (jedyny, kto dziś dotyka renderera czatu - patrz RankManager.onChat)
 * musi doklejać ten tytuł do WŁASNEGO renderera, a nie rejestrować drugi, konkurencyjny -
 * Paper's AsyncChatEvent#renderer(...) to zwykły setter, nie łańcuch, więc dwa niezależne
 * event.renderer() nadpisałyby się nawzajem zamiast się złożyć.
 */
public interface TytulService {

    /** Tytuł gracza (np. "[Początkujący] ") gotowy do dołożenia przed nickiem, albo null, jeśli gracz jeszcze żadnego nie zdobył. */
    Component tytulGracza(UUID uuid);
}
