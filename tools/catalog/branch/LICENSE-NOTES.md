# Where the scores come from

Every score here is public domain or dedicated to the public domain (CC0). As an extra
safeguard, only music by composers who died in 1930 or earlier, or traditional and
anonymous music, is included: such music is out of copyright in the United States
(published before 1931) and in countries with a term of life + 70 years.

| Source | Files | Licence of the scores | Credit |
|---|---|---|---|
| [OpenScore Lieder](https://github.com/OpenScore/Lieder) | `scores/lieder/` | CC0 1.0 | OpenScore Lieder Corpus (Gotham, Jonas et al.) |
| [OpenScore String Quartets](https://github.com/OpenScore/StringQuartets) | `scores/quartets/` | CC0 1.0 | OpenScore String Quartets |
| [PDMX](https://zenodo.org/records/15571083) | `scores/pdmx/` | Public domain mark or CC0, as set by the uploaders on MuseScore.com | Long, Novack, Berg-Kirkpatrick, McAuley: *PDMX: A Large-Scale Public Domain MusicXML Dataset for Symbolic Music Processing* (2024); dataset under CC BY 4.0 |

OpenScore files were converted from MuseScore format to MusicXML with MuseScore 3
(used only as a converter). PDMX files are the dataset's MusicXML, selected without
licence conflicts, de-duplicated, and limited to the composers above; film, game and pop
titles are excluded. Each entry in `catalog.json.gz` records its source, licence and the
original page (`link`).

If a score here should not be, open an issue on the repository and it will be removed.
