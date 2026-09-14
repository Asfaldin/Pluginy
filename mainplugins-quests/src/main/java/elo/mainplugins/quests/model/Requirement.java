package elo.mainplugins.quests.model;

import java.util.List;

/** Co trzeba zrobić, żeby zdać zadanie - 4 proste typy (free, items, money, have-item). */
public sealed interface Requirement {

    /** Kliknięcie od razu zdaje zadanie. */
    record Free() implements Requirement {}

    /** Przynieś wszystkie przedmioty - zabierane po zdaniu. */
    record Items(List<ItemRef> items) implements Requirement {}

    /** Zapłać z portfela. */
    record Money(double amount) implements Requirement {}

    /** Miej przedmiot przy sobie - zostaje u gracza. */
    record HaveItem(ItemRef item) implements Requirement {}
}
