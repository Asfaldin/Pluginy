package elo.mainplugins.quests.generator;

import org.bukkit.Material;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Jedna pozycja w tabeli dropów generatora (patrz GeneratorDefinition#bazaDropy/bonusDropy) -
 * szansaProcent == null oznacza "gwarantowany/równo ważony wybór" (patrz GeneratorManager,
 * używane w baza-dropy np. losowanie piasek/żwir 50/50); z podaną wartością to NIEZALEŻNA
 * szansa na dorzucenie tego przedmiotu OBOK reszty (bonus-dropy).
 */
public record GeneratorDrop(Material material, Double szansaProcent, int iloscMin, int iloscMax) {
    public int losujIlosc() {
        return iloscMin >= iloscMax ? iloscMin : ThreadLocalRandom.current().nextInt(iloscMin, iloscMax + 1);
    }
}
