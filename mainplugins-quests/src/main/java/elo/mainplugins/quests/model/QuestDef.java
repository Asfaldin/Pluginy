package elo.mainplugins.quests.model;

import elo.mainplugins.core.api.Reward;

import java.util.List;

/** Zadanie; id stałe w obrębie kategorii (na nim trzyma się postęp). rewardLabel null = etykieta z nagród. */
public record QuestDef(int id, String title, List<String> description, Requirement requirement,
                       List<Reward> rewards, String rewardLabel) {}
