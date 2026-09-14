package elo.mainplugins.quests.model;

/** Jedno pole menu; material != null tylko dla FILLER z własnym wyglądem. */
public record SlotEntry(int slot, SlotRole role, String material) {}
