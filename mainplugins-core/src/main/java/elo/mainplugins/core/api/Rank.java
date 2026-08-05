package elo.mainplugins.core.api;

/**
 * Trzy rangi serwera (patrz {@link RankService}). GRACZ to domyślna, niezapisywana
 * ranga - każdy, kogo nikt jeszcze nie ustawił przez /setranga, jest Graczem.
 */
public enum Rank {
    GRACZ, VIP, ADMIN
}
