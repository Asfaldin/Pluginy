package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.Reward;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class RewardGiverTest {

    private final List<String> warnings = new ArrayList<>();
    private final RewardGiver giver = new RewardGiver(warnings::add);
    private final FakeSink sink = new FakeSink();

    private static Reward r(String type, Object value) {
        return new Reward(type, value, 1, false, List.of());
    }

    @Test
    void moneyIsGivenAndAnnounced() {
        giver.give(List.of(r("money", 1500.0)), sink);
        assertEquals(List.of("money 1500.0", "msg reward.money {amount=1,500}"), sink.log);
    }

    @Test
    void itemAnnouncesReadableName() {
        giver.give(List.of(new Reward("item", "GOLDEN_APPLE", 3, false, List.of())), sink);
        assertEquals(List.of("item GOLDEN_APPLE x3", "msg reward.item {amount=3, item=golden apple}"), sink.log);
    }

    @Test
    void silentRewardHasNoMessage() {
        giver.give(List.of(new Reward("money", 5.0, 1, true, List.of())), sink);
        assertEquals(List.of("money 5.0"), sink.log);
    }

    @Test
    void commandReplacesPlayerAndHasNoMessage() {
        giver.give(List.of(r("command", "give {player} cake")), sink);
        assertEquals(List.of("cmd give Steve cake"), sink.log);
    }

    @Test
    void unknownCustomItemUsesFallback() {
        giver.give(List.of(new Reward("custom", "nope", 1, false, List.of(r("money", 1000.0)))), sink);
        assertEquals(List.of("money 1000.0", "msg reward.money {amount=1,000}"), sink.log);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingPluginTypeWithoutFallbackIsSkippedWithWarning() {
        giver.give(List.of(r("key", "epic"), r("money", 5.0)), sink);
        assertEquals(List.of("money 5.0", "msg reward.money {amount=5}"), sink.log);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("key") && warnings.get(0).contains("Steve"));
    }

    @Test
    void pluginTypeGoesToHandlerWithoutCoreMessage() {
        sink.externalTypes = Set.of("key");
        giver.give(List.of(r("key", "epic")), sink);
        assertEquals(List.of("ext key epic"), sink.log);
    }

    @Test
    void formatsMoney() {
        assertEquals("1,234.5", RewardGiver.formatMoney(1234.5));
        assertEquals("500", RewardGiver.formatMoney(500));
        assertEquals("0.25", RewardGiver.formatMoney(0.25));
    }

    private static final class FakeSink implements RewardSink {
        final List<String> log = new ArrayList<>();
        Set<String> externalTypes = Set.of();

        public String playerName() { return "Steve"; }
        public void giveMoney(double amount) { log.add("money " + amount); }
        public boolean giveItem(String material, int amount) { log.add("item " + material + " x" + amount); return true; }
        public boolean giveCustom(String id, int amount) {
            if (!id.equals("magic_sword")) return false;
            log.add("custom " + id + " x" + amount);
            return true;
        }
        public void runCommand(String command) { log.add("cmd " + command); }
        public boolean giveExternal(Reward reward) {
            if (!externalTypes.contains(reward.type())) return false;
            log.add("ext " + reward.type() + " " + reward.value());
            return true;
        }
        public void message(String key, Map<String, String> placeholders) {
            log.add("msg " + key + " " + new TreeMap<>(placeholders));
        }
    }
}
