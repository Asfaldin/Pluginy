package elo.mainplugins.core.api;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Opcjonalny kontrakt tytułów na czacie - rejestruje go mainplugins-quests (tytuły z nagrody
 * "title: id", zdefiniowane w quests.yml). mainplugins-ranks dokleja tytuł do własnego renderera
 * czatu (Paper's AsyncChatEvent#renderer(...) to setter, więc drugi renderer nadpisałby pierwszy).
 * Bez Questów po prostu nie ma tytułów - nic się nie psuje.
 */
public interface TytulService {

    /** Tytuł gracza (np. "[Początkujący] ") gotowy do dołożenia przed nickiem, albo null, jeśli gracz jeszcze żadnego nie zdobył. */
    Component tytulGracza(UUID uuid);
}
