# Sklep - dynamiczne ceny skupu (specyfikacja)

Opis systemu tak, jak został zaprojektowany. Kod: `mainplugins-shop/src/main/java/elo/mainplugins/shop/DynamicPriceManager.java`.
Ustawienia gracza-admina: `shop.yml` -> `dynamic-prices`.

## Idea

Każdy item w sklepie ma **mnożnik ceny skupu**, niezależny od pozostałych itemów.
Cena skupu pokazywana graczowi = cena z cennika x mnożnik. Mnożnik chodzi w przedziale
**0.50 - 1.50** (granice z `shop.yml`), startowo 1.0.

**Cena kupna nigdy się nie zmienia** - system dotyczy wyłącznie skupu.

Każdy item ma też **normę**: średnią kroczącą tego, ile sztuk sprzedaje się go zwykle
w ciągu jednego cyklu (domyślnie godzina).

## Kategorie nie mają znaczenia

Każdy item liczy się osobno, niezależnie od pliku kategorii, w którym siedzi.
Wcześniejsza koncepcja (spadek jednego itemu = wzrost innych w tej samej kategorii)
została odrzucona: łamała się przy różnych rozmiarach kategorii (przy 69 pozycjach
efekt bliski zeru) i pozwalała manipulować cenami w małych kategoriach.

## Co się dzieje w każdym cyklu

### Gałąź A: sprzedaż powyżej progu suszy

Mnożnik spada. Siła spadku to **większa z dwóch wartości** (nie suma):

- **efekt ilości** - im więcej sprzedano ponad normę, tym mocniej; liczony
  logarytmicznie (log2), z sufitem 5% na cykl przy cenie standardowej (mnożnik 1.0),
- **efekt wysokości** - im mnożnik wyżej ponad 1.0, tym mocniej boli sprzedaż.
  Na szczycie (1.50) spadek jest ok. 4,2x silniejszy niż standardowy - tak dobrane,
  żeby zejście z 1.50 do 1.0 przy ciągłej sprzedaży zajęło ok. 3 godziny.

Dlaczego większy z dwóch, a nie suma: inaczej jedna duża transakcja itemu stojącego
na szczycie zrzuciłaby cenę z 1.50 do 0.50 w jednym cyklu.

Licznik suszy **cofa się o 1**, nie zeruje - jedna przypadkowa transakcja tuż przed
końcem progu nie kasuje całego postępu suszy.

### Gałąź B: sprzedaż poniżej progu suszy (albo jej brak)

Licznik suszy rośnie o 1, dalej zależnie od pozycji mnożnika:

- **poniżej 1.0** - wraca w górę tempem 80% pozostałego dystansu na cykl (szybko, bo
  przy realnym ruchu graczy stała, drobna sprzedaż i tak ściąga cenę w dół, więc
  powrót musi nadążać),
- **dokładnie 1.0** - stoi, dopóki licznik suszy < 2 cykle,
- **powyżej 1.0** - rośnie 12,5% na cykl aż do sufitu 1.50 (4 cykle z 1.0 na szczyt).

## Próg suszy - zamrożony

"Cisza" to sprzedaż poniżej 10% normy. Próg zapisywany jest **raz, w momencie startu
suszy**, i trzyma się przez całą jej długość - nie kurczy się razem z normą, która przy
braku sprzedaży sama by malała. (To był realny błąd wcześniejszej wersji koncepcji.)

## Zamrożenie po zejściu z góry

Gdy mnożnik spada z góry i dotrze do 1.0, **zamraża się tam na 2 cykle**, zanim zwykła
sprzedaż może zepchnąć go poniżej bazy. Licznik suszy tyka równolegle z zamrożeniem,
nie po nim.

## Nowy item w sklepie

Pierwsza transakcja nie rusza jeszcze ceny - tylko ustawia normę jako punkt odniesienia
(nie ma z czym porównać pierwszej sprzedaży).

## Duże transakcje

Bez ograniczeń. Cała sprzedana ilość, nawet 3000 sztuk naraz, idzie po aktualnej cenie
z danego momentu. Świadomie odrzucony pomysł ograniczania premii i kary do "rozsądnej
porcji".

## Zabezpieczenia

- mnożnik zawsze przycinany do 0.50 - 1.50,
- cena skupu nigdy nie przekroczy 90% ceny kupna (`max-sell-share`) - chroni przed pętlą
  "kup w sklepie, sprzedaj do sklepu, zysk bez pracy".

## Globalny reset co 14 dni

Wszystkie mnożniki wracają twardo do 1.0, jednym ruchem, o stałej porze. Na czacie leci
ogłoszenie ("Ceny w sklepie wróciły do wartości bazowych!"). **Normy sprzedaży NIE są
kasowane** - resetują się tylko ceny, wiedza o tym, ile się czego zwykle sprzedaje,
zostaje. Itemy zablokowane eventem reset pomija.

## Świadomie zaakceptowane ograniczenia (nie błędy)

- **Farmowalne itemy** (bruk, drewno, dropy ze spawnerów) trwale osiądą na dnie 0.50
  między resetami - farma sprzedaje niezależnie od ceny (zysk zawsze dodatni przy
  zerowym koszcie produkcji), a norma z czasem uczy się tej podaży i przestaje traktować
  ją jako nadmiar. Mechanizm suszy realnie działa głównie na itemy, których nie da się
  masowo farmić.
- **Magazynowanie i wysyp po suszy** pozostaje opłacalne: gracz może wstrzymać sprzedaż,
  poczekać na szczyt 1.50 i wysypać zapas po podbitej cenie.
- **Reset co 14 dni może być grany taktycznie**: wstrzymywanie sprzedaży tuż przed
  resetem, zalew zaraz po.

## Warstwa administracyjna

W kodzie komendy mają angielskie nazwy (`/@shop ...`), w specyfikacji pisane były po
polsku - to ta sama rzecz.

- `/@shop info <przedmiot>` - ceny, mnożnik, norma, stan suszy.
- `/@shop price <przedmiot> buy|sell <kwota>` - zmiana ceny bazowej, zapisywana wprost do
  pliku kategorii. Odrzuca zmianę, jeśli skup wyszedłby >= kupna.
- `/@shop reset <przedmiot>` i `/@shop resetall` - wymagają potwierdzenia (`/@shop confirm`,
  ważne 30 sekund), bo kasują historię rynkową.
- `/@shop event <przedmiot> <procent> [czas]` - blokuje mnożnik na sztywno (np. +40% na czas
  eventu); cykl korekty całkowicie pomija zablokowany item. `off` natychmiast przywraca
  1.0, nie stopniowo - inaczej ceny eventowe ciągnęłyby się jeszcze godzinami po
  ogłoszeniu końca eventu.

## Statystyki (osobny system)

Zasilają decyzje o cenach bazowych. Zbierane jest m.in. to, ile cykli item spędza na dnie,
na szczycie i pośrodku - bezpośrednia podpowiedź, czy cena bazowa w cenniku jest trafiona.
Raport CSV ma kolumnę SUGESTIA: "OBNIZ CENE BAZOWA", "PODNIES CENE BAZOWA" albo "ok".
Do tego liczniki dobowe, podgląd na bieżąco i archiwum dzień po dniu. Statystyki przeżywają
globalny reset cen - to niezależna, długa historia.
