package elo.mainplugins.core.unlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UnlockManagerTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private UnlockManager fresh() {
        return new UnlockManager(dir.resolve("unlocks.yml").toFile(), warnings::add);
    }

    @Test
    void giveHasTakeAndCaseInsensitive() {
        UnlockManager m = fresh();
        assertFalse(m.has(a, "kowal"));
        assertTrue(m.give(a, "Kowal"));
        assertFalse(m.give(a, "KOWAL"));
        assertTrue(m.has(a, "kowal"));
        assertTrue(m.take(a, "kowal"));
        assertFalse(m.take(a, "kowal"));
        assertFalse(m.has(a, "kowal"));
    }

    @Test
    void blankNameIsIgnored() {
        UnlockManager m = fresh();
        assertFalse(m.give(a, "  "));
        assertTrue(m.list(a).isEmpty());
    }

    @Test
    void survivesRestartSortedLowercase() {
        UnlockManager m = fresh();
        m.give(a, "Nether");
        m.give(a, "kowal");
        UnlockManager again = fresh();
        assertEquals(List.of("kowal", "nether"), List.copyOf(again.list(a)));
    }

    @Test
    void badUuidInFileIsSkippedWithWarning() throws Exception {
        File f = dir.resolve("unlocks.yml").toFile();
        Files.writeString(f.toPath(), "players:\n  not-a-uuid: [x]\n  " + a + ": [vip]\n");
        UnlockManager m = new UnlockManager(f, warnings::add);
        assertEquals(Set.of("vip"), m.list(a));
        assertEquals(1, warnings.size());
    }
}
