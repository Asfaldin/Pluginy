package elo.mainplugins.core.api;

import java.util.UUID;

/** Jeden wpis w topce najbogatszych graczy - patrz {@link EconomyService#getTop(int)}. */
public record TopGracz(UUID uuid, String nick, double kasa, long grosze) {

    /** Stary konstruktor - przelicza grosze z kwoty, żeby nie psuć istniejącego kodu. */
    public TopGracz(UUID uuid, String nick, double kasa) {
        this(uuid, nick, kasa, Math.round(kasa * 100));
    }
}