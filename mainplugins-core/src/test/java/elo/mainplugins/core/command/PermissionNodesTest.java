package elo.mainplugins.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionNodesTest {

    @Test
    void playerAndAdminNodes() {
        assertEquals("mainplugins.shop.command.sklep", PermissionNodes.nodeFor("MainpluginsShop", "sklep"));
        assertEquals("mainplugins.shop.command.admin.reloadsklep", PermissionNodes.nodeFor("MainpluginsShop", "@reloadsklep"));
        assertEquals("mainplugins.core.command.przelej", PermissionNodes.nodeFor("MainpluginsCore", "przelej"));
    }

    @Test
    void adminIsAtPrefix() {
        assertTrue(PermissionNodes.isAdmin("@dajcustom"));
        assertFalse(PermissionNodes.isAdmin("sklep"));
    }
}
