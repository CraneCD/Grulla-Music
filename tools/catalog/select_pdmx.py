"""Choose the PDMX scores for the catalogue (see composers.py for the rules)."""
import csv
import re
import sys
import unicodedata

from composers import COMPOSERS, MODERN_HINTS, TRADITIONAL_KEYS


def normalise(text):
    text = unicodedata.normalize("NFD", text.lower())
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return " " + " ".join(text.split()) + " "


MATCHERS = [(name, death, [" " + " ".join(normalise(k).split()) + " " for k in keys]) for name, death, keys in COMPOSERS if death <= 1930]
TRADITIONAL = [" " + k + " " for k in TRADITIONAL_KEYS]
MODERN = [" " + " ".join(normalise(h).split()) + " " for h in MODERN_HINTS]


def composer_of(field):
    """Canonical composer name, 'Traditional', or None when not safely public domain."""
    text = normalise(field)
    if " pdq " in text:
        return None
    for name, _, keys in MATCHERS:
        if any(k in text for k in keys):
            return name
    if any(k in text for k in TRADITIONAL):
        return "Traditional"
    return None


def select(csv_path):
    csv.field_size_limit(10**9)
    chosen = []
    for row in csv.DictReader(open(csv_path, encoding="utf-8")):
        if row["license_conflict"] != "False" or row["subset:deduplicated"] != "True":
            continue
        if row["license"] not in ("publicdomain", "cc-zero"):
            continue
        composer = composer_of(row["composer_name"]) or composer_of(row["artist_name"])
        if composer is None:
            continue
        title = (row["title"] if row["title"] != "NA" else row["song_name"]).strip()
        if not title or title == "NA" or any(h in normalise(title + " " + row["subtitle"]) for h in MODERN):
            continue
        chosen.append((row, composer, title))
    return chosen


if __name__ == "__main__":
    chosen = select(sys.argv[1])
    print(len(chosen))
    from collections import Counter
    print(Counter(c for _, c, _ in chosen).most_common(40))
