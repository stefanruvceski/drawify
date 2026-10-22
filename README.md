# Drawify

Besplatna AR aplikacija za precrtavanje (Android). Izabereš sliku iz galerije, aplikacija je
prikaže poluprovidno preko kamere, a ti na ekranu vidiš i papir i svoju ruku i precrtavaš linije.

## Instalacija

1. Otvori **Releases** u ovom repou i preuzmi najnoviji `drawify-*.apk`.
2. Na telefonu dozvoli instalaciju iz nepoznatih izvora i instaliraj APK.
3. Svaki novi build se instalira preko starog (isti potpis).

## Korišćenje

- **Slika** – izaberi sliku iz galerije (ili iz galerije uradi *Podeli → Drawify*).
- Jednim prstom pomeraš sliku, sa dva prsta je zumiraš i rotiraš.
- **Providnost** – klizač na dnu.
- **Filter** – *Original*, *Bez belog* (belo postaje providno, ostaju samo linije) ili
  *Konture* (izvlači ivice – dobro za fotografije).
- **Ogledalo** – horizontalno okretanje slike.
- **Svetlo** – baterijska lampa telefona.
- **Centriraj** – vrati sliku na sredinu.
- 🔒 – zaključa sliku da se ne pomeri dok crtaš; 👁 – privremeno sakrije sliku.

Savet: telefon drži mirno (npr. na čaši ili stalku iznad papira) – slika je fiksirana na ekranu.

## Build

APK pravi GitHub Actions (`.github/workflows/build.yml`) na svaki push i objavljuje ga u Releases.
Lokalno: `./gradlew assembleRelease` (potreban Android SDK, JDK 17).
