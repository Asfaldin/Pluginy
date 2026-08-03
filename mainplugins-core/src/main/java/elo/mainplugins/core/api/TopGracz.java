package elo.mainplugins.core.api;

import java.util.UUID;

/** Jeden wpis w topce najbogatszych graczy - patrz {@link EconomyService#getTop(int)}. */
public record TopGracz(UUID uuid, String nick, double kasa) {}