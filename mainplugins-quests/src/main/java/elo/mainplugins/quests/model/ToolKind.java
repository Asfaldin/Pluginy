package elo.mainplugins.quests.model;

/**
 * Pięć ewoluujących narzędzi z mainplugins-tools (ToolsService.dajEwoluujacy*) - używane
 * zarówno przez {@link RewardEntry.ToolReward} (wręczenie narzędzia), jak i
 * {@link Requirement.ToolLevelRequirement} (sprawdzenie poziomu - UWAGA: ToolsService ma dziś
 * metodę poziomu TYLKO dla PICKAXE/AXE/SWORD, HOE/SHOVEL nie mają jeszcze odpowiednika
 * poziomKilofa/Siekiery/Miecza - QuestContentLoader odrzuca z warningiem TOOL_LEVEL dla tych
 * dwóch zamiast crashować serwer).
 */
public enum ToolKind {
    PICKAXE, AXE, HOE, SWORD, SHOVEL
}
