package elo.mainplugins.shop.model;

/** Jedno pole okna. material tylko dla FILLER (może być null), amount tylko dla AMOUNT_SLOT (ile sztuk). */
public record SlotEntry(int slot, SlotRole role, String material, int amount) {}
