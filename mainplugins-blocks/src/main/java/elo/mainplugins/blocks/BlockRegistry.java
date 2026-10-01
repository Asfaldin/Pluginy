package elo.mainplugins.blocks;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wczytane bloki: po id, po stanie note blocka (rozpoznawanie postawionych) i ich przedmioty. */
public final class BlockRegistry {

    private final NamespacedKey itemKey;
    private final Map<String, BlockDef> byId = new LinkedHashMap<>();
    private final Map<String, BlockDef> byState = new HashMap<>();
    private final Map<String, BlockData> data = new HashMap<>();

    public BlockRegistry(Plugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "block");
    }

    /** Podmienia zestaw bloków; zwraca opis problemu (kolizja stanów) albo null. */
    public String set(Collection<BlockDef> defs) {
        byId.clear();
        byState.clear();
        data.clear();
        String problem = null;
        for (BlockDef d : defs) {
            BlockData bd = Bukkit.createBlockData(BlockStates.state(d.state()));
            String key = bd.getAsString();
            BlockDef clash = byState.get(key);
            if (clash != null) {
                problem = "Bloki " + clash.id() + " i " + d.id() + " mają ten sam stan note blocka - zapisz jeden z nich w aplikacji jeszcze raz.";
                continue;
            }
            byId.put(d.id(), d);
            byState.put(key, d);
            data.put(d.id(), bd);
        }
        return problem;
    }

    public Collection<BlockDef> all() {
        return byId.values();
    }

    public BlockDef get(String id) {
        return byId.get(id);
    }

    /** Własny blok stojący w świecie albo null (zwykły note block i każdy inny blok). */
    public BlockDef at(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) return null;
        return byState.get(block.getBlockData().getAsString());
    }

    public BlockData vanillaNoteBlock() {
        return Bukkit.createBlockData(BlockStates.VANILLA);
    }

    public BlockData blockData(BlockDef def) {
        return data.get(def.id()).clone();
    }

    public ItemStack item(BlockDef def, int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, amount));
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("mainplugins", "block/" + def.id()));
        item.setData(DataComponentTypes.ITEM_NAME, Component.text(def.name()).decoration(TextDecoration.ITALIC, false));
        item.editPersistentDataContainer(pdc -> pdc.set(itemKey, PersistentDataType.STRING, def.id()));
        return item;
    }

    /** Blok, którego przedmiotem jest ten stack, albo null. */
    public BlockDef of(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        String id = item.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : byId.get(id);
    }
}
