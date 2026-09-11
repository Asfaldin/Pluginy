package elo.mainplugins.core.placeholder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderRegistryTest {

    private final List<String> warnings = new ArrayList<>();
    private final PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(warnings::add);

    @Test
    void firstResolverThatKnowsTheNameWins() {
        registry.register("core", (p, name) -> name.equals("money") ? "100" : null);
        registry.register("hud", (p, name) -> name.equals("money") ? "HUD" : name.equals("kasa") ? "1k" : null);
        assertEquals("100", registry.resolve(null, "money"));
        assertEquals("1k", registry.resolve(null, "kasa"));
    }

    @Test
    void unknownNameGivesNull() {
        registry.register("core", (p, name) -> null);
        assertNull(registry.resolve(null, "nope"));
    }

    @Test
    void unregisterRemovesOnlyThatOwner() {
        registry.register("core", (p, name) -> name.equals("a") ? "core" : null);
        registry.register("hud", (p, name) -> name.equals("b") ? "hud" : null);
        registry.unregister("hud");
        assertEquals("core", registry.resolve(null, "a"));
        assertNull(registry.resolve(null, "b"));
    }

    @Test
    void throwingResolverIsSkippedWithOneWarning() {
        registry.register("bad", (p, name) -> { throw new IllegalStateException("boom"); });
        registry.register("core", (p, name) -> "ok");
        assertEquals("ok", registry.resolve(null, "x"));
        assertEquals("ok", registry.resolve(null, "x"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("bad"));
    }
}
