package elo.mainplugins.crates;

import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.ItemRef;
import elo.mainplugins.crates.model.Prize;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrateOddsTest {

    private static Prize p(String name, int w) {
        return new Prize(name, new ItemRef("DIAMOND", null, 1), w, false, List.of());
    }

    private final CrateDef crate = new CrateDef("c", "C", List.of(), new ItemRef("CHEST", null, 1),
            List.of("k"), List.of(p("a", 20), p("b", 1)), List.of());

    @Test
    void chanceIsWeightOverTotal() {
        assertEquals(20.0 / 21 * 100, CrateOdds.chancePercent(crate, crate.prizes().get(0)), 1e-9);
        assertEquals("95.2", CrateOdds.formatChance(CrateOdds.chancePercent(crate, crate.prizes().get(0))));
        assertEquals("4.8", CrateOdds.formatChance(CrateOdds.chancePercent(crate, crate.prizes().get(1))));
    }

    @Test
    void pickFollowsWeights() {
        assertEquals("a", CrateOdds.pick(crate, n -> 0).name());
        assertEquals("a", CrateOdds.pick(crate, n -> 19).name());
        assertEquals("b", CrateOdds.pick(crate, n -> 20).name());
    }

    @Test
    void legacyTierMapsToCrateByPosition() {
        List<String> ids = List.of("basic", "abyss", "darkstar");
        assertEquals("basic", CrateOdds.legacyCrateId(1, ids));
        assertEquals("darkstar", CrateOdds.legacyCrateId(3, ids));
        assertEquals("basic", CrateOdds.legacyCrateId(9, ids));
        assertNull(CrateOdds.legacyCrateId(1, List.of()));
    }
}
