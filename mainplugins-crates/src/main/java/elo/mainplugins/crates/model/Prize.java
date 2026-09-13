package elo.mainplugins.crates.model;

import elo.mainplugins.core.api.Reward;

import java.util.List;

/** Jedna możliwa wygrana skrzynki: ikona i nazwa do animacji/podglądu, waga, co gracz dostaje. */
public record Prize(String name, ItemRef icon, int weight, boolean announce, List<Reward> rewards) {}
