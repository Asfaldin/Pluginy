package elo.mainplugins.crates.model;

import java.util.List;

/** Klucz z crates.yml (sekcja keys). */
public record KeyDef(String id, String name, List<String> lore, ItemRef item) {}
