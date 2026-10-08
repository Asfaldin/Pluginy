package elo.mainplugins.guilds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuildRulesTest {

    private final List<String> warnings = new ArrayList<>();

    private GuildsConfig config(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return GuildsConfig.parse(y, warnings::add);
    }

    @Test
    void tagAndNameRules() throws Exception {
        GuildsConfig c = config("guilds: { tag-min-length: 2, tag-max-length: 4, name-max-length: 10 }");
        assertNull(GuildRules.checkTag("ABC", c));
        assertEquals("error.tag-length", GuildRules.checkTag("A", c));
        assertEquals("error.tag-length", GuildRules.checkTag("ABCDE", c));
        assertEquals("error.tag-chars", GuildRules.checkTag("A$B", c));
        assertNull(GuildRules.checkName("Złote Smoki", c.nameMax() >= 11 ? c : config("guilds: { name-max-length: 20 }")));
        assertEquals("error.name-length", GuildRules.checkName("This name is far too long", c));
        assertEquals("error.name-chars", GuildRules.checkName("<script>", c));
    }

    @Test
    void badConfigValuesFallBackToDefaults() throws Exception {
        GuildsConfig c = config("guilds: { max-members: 1, home-delay-seconds: 999, create-cost: -50 }");
        assertEquals(8, c.maxMembersFree());
        assertEquals(3, c.homeDelaySeconds());
        assertEquals(0, c.createCost());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void rolesAndKickRights() {
        UUID leader = UUID.randomUUID(), officer = UUID.randomUUID(), member = UUID.randomUUID(), other = UUID.randomUUID();
        Guild g = new Guild("ABC", "Test", leader);
        g.members.add(officer);
        g.members.add(member);
        g.officers.add(officer);
        assertEquals("abc", g.id);
        assertEquals(Guild.Role.LEADER, g.role(leader));
        assertTrue(g.canKick(leader, officer));
        assertTrue(g.canKick(officer, member));
        assertFalse(g.canKick(officer, leader));
        assertFalse(g.canKick(member, officer));
        assertFalse(g.canKick(officer, officer));
        assertFalse(g.canManage(member));
        assertEquals(Guild.Role.MEMBER, g.role(other));
    }

    @Test
    void topSortsByMembersThenBank() {
        Guild a = new Guild("AAA", "A", UUID.randomUUID());
        Guild b = new Guild("BBB", "B", UUID.randomUUID());
        b.members.add(UUID.randomUUID());
        Guild c = new Guild("CCC", "C", UUID.randomUUID());
        c.bank = 500;
        List<Guild> top = GuildRules.top(List.of(a, b, c), 10);
        assertEquals(List.of(b, c, a), top);
    }

    @Test
    void bundledDefaultsParseCleanly() throws Exception {
        for (String lang : List.of("en", "pl")) {
            warnings.clear();
            YamlConfiguration y = new YamlConfiguration();
            try (var in = getClass().getResourceAsStream("/defaults/" + lang + "/guilds.yml")) {
                assertNotNull(in, lang);
                y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            GuildsConfig c = GuildsConfig.parse(y, warnings::add);
            assertEquals(30, c.maxMembersPlan(), lang);
            assertTrue(warnings.isEmpty(), lang + ": " + warnings);
        }
    }
}
