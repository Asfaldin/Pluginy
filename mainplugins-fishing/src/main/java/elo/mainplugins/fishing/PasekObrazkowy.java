package elo.mainplugins.fishing;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * "Wersja A" graficznego paska minigry (user 2026-08-31, patrz FishingMinigame.tick) -
 * obrazek jako PRAWDZIWY, płaski element UI wewnątrz BossBara/action bara (custom font
 * glyph z resourcepacka - technika "font images"), a NIE obiekt w świecie 3D jak
 * poprzednia próba (patrz GraficznyPasek.java - "wersja B", zostawiona nieużywana na
 * wypadek powrotu do niej później). Renderuje się w DOKŁADNIE tych samych dwóch
 * miejscach co StylPaska.TEKSTOWY (BossBar u góry / action bar u dołu, patrz
 * PozycjaPaska) - user zobaczył wersję B na żywo i uznał że wygląda "strasznie dziwnie",
 * bo obiekt w świecie ma perspektywę/głębię i nigdy nie będzie wyglądał jak czysty HUD.
 *
 * Sam obrazek (tło rurki + drewniana rama, wygenerowane programistycznie, patrz
 * rozmowa 2026-08-30/31) to JEDEN znak w customowym foncie "mainplugins:lowienie"
 * (assets/mainplugins/font/lowienie.json + textures/font/lowienie_pasek.png) - doklejany
 * PRZED zwykłym tekstowym suwakiem/miernikiem (patrz FishingMinigame.zbudujSuwakStrip/
 * zbudujMiernikPostepu), które dalej rysują RUCH (rybkę/suwak/postęp) dokładnie tak samo
 * jak w trybie tekstowym - tylko teraz na ładniejszym tle zamiast pustego paska.
 *
 * STROJENIE: "ascent"/"height" w lowienie.json to pierwsze przybliżenie - dopiero
 * live-test w grze pokaże czy obrazek jest dobrze wyrównany względem tekstu obok.
 */
final class PasekObrazkowy {

    private static final Key FONT = Key.key("mainplugins", "lowienie");
    private static final String GLIF_TLA = ""; // patrz assets/mainplugins/font/lowienie.json

    private PasekObrazkowy() {}

    /** Obrazek tła (rurka+rama) jako gotowy Component w customowym foncie - patrz FishingMinigame.tick. */
    static Component tlo() {
        return Component.text(GLIF_TLA).font(FONT);
    }
}
