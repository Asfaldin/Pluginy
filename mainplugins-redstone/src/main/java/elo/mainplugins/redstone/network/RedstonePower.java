package elo.mainplugins.redstone.network;

import org.bukkit.block.Block;

/**
 * Prosty lokalny odczyt "czy ten blok-zaczep jest zasilony prawdziwym redstone" - użyty
 * jako bramka dla STATION/HARVESTER ("musi być zasilona redstonem"). To NIE jest powrót
 * do wycofanego silnika przewodzenia (żaden graf, żaden zanik siły) - tylko jednorazowe
 * pytanie o stan bieżącego bloku, dokładnie tak jak zwykłe redstone-urządzenia w
 * wanilii (dźwignia/przycisk na sąsiedniej ścianie tego samego bloku wystarczy, bo
 * wanilijska "silna moc" i tak przechodzi przez lity blok).
 */
public final class RedstonePower {

    private RedstonePower() {}

    public static boolean isPowered(Block anchor) {
        return anchor.getBlockPower() > 0 || anchor.isBlockIndirectlyPowered();
    }
}
