package elo.mainplugins.quests;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void roundTrip() throws Exception {
        Map<UUID, ProgressStore.PlayerProgress> all = new LinkedHashMap<>();
        ProgressStore.PlayerProgress pa = new ProgressStore.PlayerProgress();
        pa.doneIn("main_path").add(1);
        pa.doneIn("main_path").add(2);
        pa.doneIn("mining").add(5);
        pa.titles().add("beginner");
        all.put(a, pa);
        ProgressStore.PlayerProgress pb = new ProgressStore.PlayerProgress();
        pb.doneIn("empty"); // pusta kategoria nie trafia do pliku
        all.put(b, pb);

        YamlConfiguration y = new YamlConfiguration();
        ProgressStore.write(y, all);
        YamlConfiguration again = new YamlConfiguration();
        again.loadFromString(y.saveToString());

        List<String> warnings = new ArrayList<>();
        Map<UUID, ProgressStore.PlayerProgress> read = ProgressStore.read(again, warnings::add);
        assertEquals(Set.of(1, 2), read.get(a).doneView("main_path"));
        assertEquals(Set.of(5), read.get(a).doneView("mining"));
        assertEquals(Set.of("beginner"), read.get(a).titles());
        assertFalse(read.containsKey(b));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void doneViewDoesNotCreate() {
        ProgressStore.PlayerProgress p = new ProgressStore.PlayerProgress();
        assertTrue(p.doneView("x").isEmpty());
        assertTrue(p.done().isEmpty());
    }

    @Test
    void badUuidIsSkipped() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("players:\n  nope:\n    done:\n      a: [1]\n");
        List<String> warnings = new ArrayList<>();
        assertTrue(ProgressStore.read(y, warnings::add).isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void writeClearsOldPlayers() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("players:\n  " + b + ":\n    done:\n      a: [1]\n");
        ProgressStore.write(y, Map.of());
        assertFalse(y.contains("players." + b));
    }
}
