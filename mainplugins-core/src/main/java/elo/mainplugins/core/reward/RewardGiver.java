package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardService;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Wydaje listę nagród: udane = wiadomość (chyba że silent), nieudane = fallback albo ostrzeżenie. */
public final class RewardGiver {

    private final Consumer<String> warn;

    public RewardGiver(Consumer<String> warn) {
        this.warn = warn;
    }

    public void give(List<Reward> rewards, RewardSink sink) {
        for (Reward reward : rewards) giveOne(reward, sink);
    }

    private void giveOne(Reward reward, RewardSink sink) {
        boolean given = switch (reward.type()) {
            case RewardService.MONEY -> {
                sink.giveMoney(((Number) reward.value()).doubleValue());
                yield true;
            }
            case RewardService.ITEM -> sink.giveItem((String) reward.value(), reward.amount());
            case RewardService.CUSTOM -> sink.giveCustom((String) reward.value(), reward.amount());
            case RewardService.COMMAND -> {
                sink.runCommand(((String) reward.value()).replace("{player}", sink.playerName()));
                yield true;
            }
            default -> sink.giveExternal(reward);
        };

        if (given) {
            if (!reward.silent()) announce(reward, sink);
            return;
        }
        if (!reward.fallback().isEmpty()) {
            give(reward.fallback(), sink);
            return;
        }
        warn.accept("Reward '" + reward.type() + ": " + reward.value() + "' could not be given to "
                + sink.playerName() + " and has no fallback - skipped.");
    }

    private void announce(Reward reward, RewardSink sink) {
        switch (reward.type()) {
            case RewardService.MONEY -> sink.message("reward.money",
                    Map.of("amount", formatMoney(((Number) reward.value()).doubleValue())));
            case RewardService.ITEM, RewardService.CUSTOM -> sink.message("reward." + reward.type(),
                    Map.of("amount", String.valueOf(reward.amount()), "item", readable((String) reward.value())));
            default -> { } // command: bez wiadomości; typy pluginów: wiadomość wysyła ich handler
        }
    }

    private static String readable(String id) {
        return id.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public static String formatMoney(double amount) {
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(amount);
    }
}
