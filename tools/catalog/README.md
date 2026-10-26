# Score catalogue builder

Builds the catalogue behind the app's "Find scores" screen and publishes it on the
`catalog` branch of this repository. The app downloads `catalog.json.gz` and the chosen
scores from `https://raw.githubusercontent.com/CraneCD/Grulla-Music/catalog/`.

## Sources and rules

- **OpenScore Lieder** and **OpenScore String Quartets** (CC0), converted from MuseScore files.
- **PDMX** (public domain / CC0 scores from MuseScore.com), selected by `select_pdmx.py`:
  no licence conflict, de-duplicated, composer in `composers.py` (died in 1930 or earlier)
  or traditional, and no film/game/pop title hints. Uploaders set PDMX licences
  themselves, which is why the composer rule is applied.
- Every score must load and play in the app's own Verovio (`check_scores.js`).

## Running it

Needs Python 3 with PyYAML, Node.js, and MuseScore 3 (`apt install musescore3`; only a
converter, nothing of it is shipped). Ubuntu's MuseScore 3.2 does not open files saved by
3.5/3.6, so the builder converts a copy whose format header says 3.01 (the formats differ
only in details); every result is then checked in Verovio.

```sh
W=/tmp/catalog-work; O=/tmp/catalog-out
mkdir -p $W && cd $W
git clone --depth 1 https://github.com/OpenScore/Lieder.git lieder
git clone --depth 1 https://github.com/OpenScore/StringQuartets.git quartets
curl -L -o PDMX.csv 'https://zenodo.org/records/15571083/files/PDMX.csv?download=1'
curl -L -o mxl.tar.gz 'https://zenodo.org/records/15571083/files/mxl.tar.gz?download=1'
cd - && python3 tools/catalog/build_catalog.py --work $W --out $O
```

Then publish `$O` as a single commit on the orphan `catalog` branch (replacing the
previous one, so the branch never grows with old versions):

```sh
cd $O && git init -q -b catalog && git add -A && git commit -qm "Score catalogue" \
  && git push -f https://github.com/CraneCD/Grulla-Music.git catalog
```
