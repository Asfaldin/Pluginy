package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.Reward;

import java.util.Map;

/** To, czego RewardGiver potrzebuje od świata (gracz, ekonomia, itemy). W testach - atrapa. */
public interface RewardSink {

    String playerName();

    void giveMoney(double amount);

    /** false = materiał nie jest itemem. */
    boolean giveItem(String material, int amount);

    /** false = nieznane id. */
    boolean giveCustom(String id, int amount);

    void runCommand(String command);

    /** false = brak handlera typu albo handler odmówił. */
    boolean giveExternal(Reward reward);

    void message(String key, Map<String, String> placeholders);
}
