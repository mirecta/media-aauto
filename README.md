# Médiá Auto

Android aplikácia pre Android Auto na prehrávanie lokálnej hudby a videí a filmov a seriálov zo Sosac
(stream zo Streamuj.tv). Protokol je prevzatý zo [sosac-tizen](../sosac-tizen) (pôvodne z Kodi doplnku
`plugin.video.sosac-2`). Potrebujete účet na oboch službách.

## Čo vie

- **Android Auto** (štandardná media šablóna), 4 karty:
  - **Hudba** – interpreti → albumy → skladby, všetky skladby (prehrá sa celý album)
  - **Videá** – lokálne videá (len zvuk)
  - **Filmy** – môj zoznam, rozpozerané, videné, novinky, rebríčky, A–Z, žáner, kvalita
  - **Seriály** – to isté + epizódy; v seriáli položka „▶ Pokračovať“ a automaticky ďalšia epizóda
- Hľadanie (aj hlasové „pusti X v Médiá Auto“) – filmy, seriály, lokálna hudba a videá
- Pokračovanie od poslednej pozície, priebeh sa ukladá na Sosac (každých 30 s a pri pauze)
- V aute sa používa samostatná (nižšia) kvalita streamu, lebo sa prehráva len zvuk
- **Sťahovanie na offline prehranie**: v telefóne podržte prst na filme/epizóde → *Stiahnuť do telefónu*.
  Stiahnuté sú v ponuke *Stiahnuté* (telefón aj auto) a hrajú aj bez internetu. Kvalita a „len cez Wi-Fi“ v Nastaveniach.
- **Telefón**: ten istý strom, nastavenia účtov a prehrávač s obrazom

### Video v aute, keď stojí – „Médiá Video“

Android Auto zatiaľ nepovoľuje video aplikácie tretích strán (oficiálna podpora pre Android 17 sa
pripravuje). Aplikácia preto obsahuje druhú časť **Médiá Video**, ktorá sa v aute hlási ako
*navigačná* – tie dostanú od auta plochu na kreslenie mapy a my na ňu kreslíme video.

- V **Médiá Video** ťuknite na **Vybrať** (rozpozerané, môj zoznam, stiahnuté, všetky filmy/seriály, hľadanie) –
  po výbere sa hneď spustí obraz. Prehrávanie spustené v Médiá Auto alebo v telefóne sa tu tiež zobrazí.
- Médiá Video sa zobrazuje na mieste navigácie (napr. Waze); naspäť na navigáciu sa prepnete jej otvorením.
- Ovládanie: Vybrať, −30 s, play/pauza, +30 s, ťuknutie na obraz = pauza.
- Obraz sa ukáže **len pri overenej nulovej rýchlosti** auta (povolenie *rýchlosť auta* sa pýta
  pri prvom spustení). Počas jazdy alebo pri neznámej rýchlosti je obraz zakrytý a ide len zvuk.
- Ide o neoficiálne riešenie – funguje len pri sideloade so zapnutými *Neznámymi zdrojmi*, nie cez Google Play.

## Zostavenie a inštalácia

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pri sideloadnutej aplikácii treba v Android Auto zapnúť neznáme zdroje:
Android Auto nastavenia → 10× ťuknúť na *Verzia* (vývojársky režim) → ⋮ → Nastavenia pre vývojárov →
**Neznáme zdroje**. Potom aplikáciu zapnúť v *Prispôsobiť spúšťač*.

Testovanie bez auta: [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
(`$ANDROID_HOME/extras/google/auto/desktop-head-unit`).

## Prvé spustenie

1. Povoliť prístup k hudbe, videám a notifikáciám.
2. ⚙ Nastavenia → Sosac a Streamuj.tv meno a heslo → *Uložiť a overiť* (heslá sa ukladajú len ako hash).

## Štruktúra

- `sosac/SosacApi.kt` – klient Sosac a Streamuj.tv API
- `sosac/StreamResolver.kt` – výber dabingu/kvality a získanie adresy súboru; položky v rade majú URI
  `sosac://…` a skutočná adresa sa zistí až pri prehratí (`ResolvingDataSource`)
- `library/Library.kt` – strom položiek pre Auto aj telefón, zostavenie radu, pokračovanie
- `library/LocalMedia.kt` – MediaStore; `library/ArtworkProvider.kt` – obrázky cez `content://` pre Auto
- `download/` – sťahovanie (Media3 DownloadManager, cache s kľúčom `sosac:<kind>:<id>`)
- `PlaybackService.kt` – `MediaLibraryService` (Media3/ExoPlayer), ukladanie pozície
- `car/VideoCarAppService.kt` – obraz videa na displeji auta (navigačná plocha + virtuálny displej)
- `ui/` – prehliadač, nastavenia, prehrávač s obrazom

Chýba oproti Tizen verzii: titulky, ručný výber streamu, pridanie/odobranie zo zoznamu.
