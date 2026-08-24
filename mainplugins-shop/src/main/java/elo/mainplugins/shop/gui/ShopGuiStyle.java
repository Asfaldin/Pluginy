package elo.mainplugins.shop.gui;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Kolory tytułów okien sklepu i kolor+tekst przycisków nawigacyjnych, wczytane z sekcji
 * "styl" w sklep-gui.yml (patrz ShopGuiLoader). NIE obejmuje: statycznego opisu pod ikonami
 * kategorii, ani koloru dynamicznych linii ceny/trendu w lore itemów - to świadomie zostało
 * w Javie (patrz komentarz w sklep-gui.yml).
 *
 * Tytuły okien kategorii/wyboru ilości/wyników wyszukiwania mają w większości TEKST
 * dynamiczny (nazwa kategorii/itemu/fraza szukania) - stąd tylko kolor jest tu edytowalny,
 * nie tekst. Wyjątek: main-menu ma stały tekst, ale i tak zostawiony jako pojedynczy kolor
 * dla spójności z resztą (sam tekst "Sklep Serwerowy" nie jest tym o co proszono).
 */
public record ShopGuiStyle(
        NamedTextColor tytulMainMenu,
        NamedTextColor tytulKategoria,
        NamedTextColor tytulWyborIlosci,
        NamedTextColor tytulWyniki,
        StyledLabel szukaj,
        StyledLabel wyjscieDoMenu,
        StyledLabel wyjscieZamknij,
        StyledLabel poprzedniaStrona,
        StyledLabel nastepnaStrona,
        StyledLabel sortowanie,
        StyledLabel powrotDoKategorii,
        StyledLabel powrotZWynikow,
        StyledLabel powrotZIlosci
) {
    /** Tekst + kolor jednego przycisku nawigacyjnego. */
    public record StyledLabel(String tekst, NamedTextColor kolor) {}
}
