#!/usr/bin/env python3
"""
Builds the online score catalogue that the app's "Find scores" screen searches.

Sources (all CC0 or public domain; see README.md in this folder):
  - OpenScore Lieder and OpenScore String Quartets (MuseScore files, converted to
    MusicXML with MuseScore 3 on the command line)
  - PDMX, a public-domain subset of MuseScore.com, filtered by select_pdmx.py

Only music by composers who died in 1930 or earlier, or traditional music, is kept.
Every score is checked by loading it in the app's own Verovio build (check_scores.js).

Output (the `catalog` branch of the app's repository):
  catalog.json.gz          the index (see Catalog.kt in :core for the format)
  scores/<source>/<shard>/<id>.mxl
  README.md, LICENSE-NOTES.md

Usage:
  build_catalog.py --work /tmp/catalog-work --out /tmp/catalog-out
The work folder holds downloads and conversions and is reused between runs:
  <work>/PDMX.csv, <work>/mxl.tar.gz          from https://zenodo.org/records/15571083
  <work>/lieder, <work>/quartets              git clones of OpenScore/Lieder and OpenScore/StringQuartets
"""
import argparse
import gzip
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import unicodedata
import zipfile
import xml.etree.ElementTree as ET

import yaml

from select_pdmx import composer_of, select

HERE = os.path.dirname(os.path.abspath(__file__))
CUTOFF = 1930
MAX_BYTES = 2_000_000


# --- MusicXML metadata ---------------------------------------------------------------

def read_musicxml(data):
    """Root element of a .mxl (zip) or plain MusicXML file."""
    if data[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            root_path = None
            if "META-INF/container.xml" in z.namelist():
                container = ET.fromstring(z.read("META-INF/container.xml"))
                for el in container.iter():
                    if el.tag.endswith("rootfile") and el.get("full-path"):
                        root_path = el.get("full-path")
                        break
            if root_path is None:
                root_path = next(n for n in z.namelist() if n.endswith((".xml", ".musicxml")) and not n.startswith("META-INF"))
            data = z.read(root_path)
    return ET.fromstring(data)


def clean(text):
    if not text:
        return ""
    if "Ã" in text or "â€" in text:
        try:
            text = text.encode("latin-1").decode("utf-8")
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass
    text = text.replace("\\a", "å").replace("\\o", "ø")
    return " ".join(text.split())


def score_facts(root):
    """
    Title, collection, parts and measure count from the MusicXML document. The movement
    title names the piece itself (a song); the work title often names its collection
    (the song cycle), so the movement title wins and the work becomes the collection.
    """
    def text(path):
        el = root.find(path)
        return clean(el.text) if el is not None and el.text else ""
    work, movement = text("work/work-title"), text("movement-title")
    if re.fullmatch(r"\W*(no\.?\s*)?\d+[a-z]?\W*", movement, re.IGNORECASE):
        # A bare number names the piece only within its work: "Op. 39 – 21".
        return (f"{work} – {movement}" if work else ""), None, parts_of(root), bars_of(root)
    title = movement or work
    collection = work if movement and work and work != movement else None
    return title, collection, parts_of(root), bars_of(root)


def parts_of(root):
    parts = []
    for part in root.findall("part-list/score-part"):
        name = clean(part.findtext("part-name") or "")
        if not name or name.lower() in ("music", "staff", "part", "mus", "instrument"):
            name = clean(part.findtext("score-instrument/instrument-name") or "")
        program = part.findtext("midi-instrument/midi-program")
        parts.append((name, int(program) if program and program.strip().isdigit() else None))
    return parts


def bars_of(root):
    first = root.find("part")
    return len(first.findall("measure")) if first is not None else 0


INSTRUMENT_WORDS = {
    "piano": "Piano", "pianoforte": "Piano", "klavier": "Piano", "harpsichord": "Harpsichord", "organ": "Organ",
    "violin": "Violin", "violino": "Violin", "viola": "Viola", "cello": "Cello", "violoncello": "Cello",
    "contrabass": "Double bass", "double bass": "Double bass", "flute": "Flute", "piccolo": "Flute", "recorder": "Recorder",
    "oboe": "Oboe", "clarinet": "Clarinet", "bassoon": "Bassoon", "saxophone": "Saxophone", "sax": "Saxophone",
    "trumpet": "Trumpet", "horn": "Horn", "trombone": "Trombone", "tuba": "Tuba", "guitar": "Guitar", "lute": "Lute",
    "harp": "Harp", "voice": "Voice", "soprano": "Voice", "alto": "Voice", "tenor": "Voice", "bass": "Voice",
    "baritone": "Voice", "mezzo": "Voice", "singstimme": "Voice", "gesang": "Voice", "choir": "Voice", "chorus": "Voice",
    "accordion": "Accordion", "mandolin": "Mandolin", "banjo": "Banjo", "whistle": "Whistle", "fiddle": "Violin",
    "bagpipe": "Bagpipes", "pipes": "Bagpipes", "ukulele": "Ukulele", "bell": "Bells", "timpani": "Percussion",
    "drum": "Percussion",
}


# General MIDI program (1-based, as in MusicXML) -> family, for parts without a useful name.
GM_FAMILIES = [
    (1, 8, "Piano"), (7, 7, "Harpsichord"), (17, 21, "Organ"), (22, 24, "Accordion"), (25, 32, "Guitar"),
    (33, 40, "Double bass"), (41, 41, "Violin"), (42, 42, "Viola"), (43, 43, "Cello"), (44, 44, "Double bass"),
    (46, 46, "Violin"), (47, 47, "Harp"), (49, 52, "Strings"), (53, 55, "Voice"), (57, 57, "Trumpet"),
    (58, 58, "Trombone"), (59, 59, "Tuba"), (60, 60, "Trumpet"), (61, 61, "Horn"), (65, 68, "Saxophone"),
    (69, 70, "Oboe"), (71, 71, "Bassoon"), (72, 72, "Clarinet"), (73, 74, "Flute"), (75, 75, "Recorder"),
    (76, 80, "Flute"),
]


def instruments(parts):
    """Plain instrument families from part names (or MIDI programs), e.g. ["Voice", "Piano"]."""
    found = []
    for name, program in parts:
        low = " " + re.sub(r"[^a-z ]+", " ", unicodedata.normalize("NFD", name.lower())) + " "
        match = None
        for word, family in sorted(INSTRUMENT_WORDS.items(), key=lambda kv: -len(kv[0])):
            if f" {word} " in low or (len(word) > 4 and word in low):
                match = family
                break
        if match is None and re.fullmatch(r" (s|a|t|b|sa|tb|satb|s a t b)( \d)? ", low):
            match = "Voice"
        if match is None and program is not None:
            match = next((family for lo, hi, family in GM_FAMILIES if lo <= program <= hi), None)
        if match and match not in found:
            found.append(match)
    return found


def tidy_title(title, composer):
    """Drops a trailing " - Composer" that MuseScore uploads often add to titles."""
    surname = composer.split()[-1].lower() if composer and composer != "Traditional" else None
    match = re.match(r"^(.*\S)\s+[-–—]\s+(.+)$", title)
    if match and surname and surname in match.group(2).lower():
        return match.group(1)
    return title


def difficulty(bars, notes_per_bar):
    """1 easy, 2 medium, 3 hard: a rough guide from note density."""
    if notes_per_bar is None:
        return None
    if notes_per_bar < 6:
        return 1
    if notes_per_bar < 14:
        return 2
    return 3


# --- Sources -------------------------------------------------------------------------------

def openscore(work, corpus, source, prefix):
    """Converts an OpenScore corpus with MuseScore 3; returns catalogue candidates."""
    base = os.path.join(work, corpus)
    composers = yaml.safe_load(open(os.path.join(base, "data/composers.yaml"), encoding="utf-8"))
    sets = yaml.safe_load(open(os.path.join(base, "data/sets.yaml"), encoding="utf-8"))
    scores = yaml.safe_load(open(os.path.join(base, "data/scores.yaml"), encoding="utf-8"))
    by_path = {c["path"]: c for c in composers.values()}
    jobs, items = [], []
    for score_id, score in scores.items():
        composer = by_path.get(score["path"].split("/")[0])
        if not composer or not isinstance(composer.get("died"), int) or composer["died"] > CUTOFF:
            continue
        folder = os.path.join(base, "scores", score["path"])
        mscx = os.path.join(folder, f"{prefix}{score_id}.mscx")
        if not os.path.isfile(mscx):
            continue
        out = os.path.join(work, "converted", source, f"{prefix}{score_id}.mxl")
        if not os.path.isfile(out):
            jobs.append({"in": os.path.abspath(readable_copy(mscx, work, source)), "out": os.path.abspath(out)})
        set_info = sets.get(score.get("set_id"), {})
        leaf = score["path"].split("/")[-1]
        fallback = re.sub(r"^\d+[a-z]?_", "", leaf).replace("_", " ")
        items.append({
            "id": f"{source}-{score_id}",
            "file": out,
            "composer": composer["name"],
            "fallback_title": score.get("name") or fallback,
            "set": set_info.get("name") if set_info.get("name", "").startswith("Other songs") is False else None,
            "src": source,
            "lic": "CC0",
            "link": score.get("link") or f"https://musescore.com/score/{score_id}",
            "lang": score.get("language"),
        })
    convert(jobs, work)
    return items


def readable_copy(mscx, work, source):
    """
    Ubuntu's MuseScore 3.2 refuses files saved by MuseScore 3.5/3.6 (format 3.02), although
    the format differs only in details; a copy labelled 3.01 converts fine. Every result
    is still checked by loading it in Verovio.
    """
    text = open(mscx, encoding="utf-8").read()
    if '<museScore version="3.02">' not in text:
        return mscx
    copy = os.path.join(work, "patched", source, os.path.basename(mscx))
    os.makedirs(os.path.dirname(copy), exist_ok=True)
    open(copy, "w", encoding="utf-8").write(text.replace('<museScore version="3.02">', '<museScore version="3.01">', 1))
    return copy


def convert(jobs, work):
    """MuseScore 3 batch conversion, in chunks (one process per chunk is much faster)."""
    for start in range(0, len(jobs), 50):
        chunk = jobs[start:start + 50]
        for job in chunk:
            os.makedirs(os.path.dirname(job["out"]), exist_ok=True)
        path = os.path.join(work, "job.json")
        json.dump(chunk, open(path, "w"))
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
        subprocess.run(["mscore3", "-j", path], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1800)
        print(f"  converted {min(start + 50, len(jobs))}/{len(jobs)}", file=sys.stderr)


def pdmx(work):
    chosen = select(os.path.join(work, "PDMX.csv"))
    wanted = {row["mxl"].lstrip("./"): (row, composer, title) for row, composer, title in chosen}
    out_dir = os.path.join(work, "converted", "pdmx")
    os.makedirs(out_dir, exist_ok=True)
    items = []
    missing = {name for name, (row, _, _) in wanted.items() if not os.path.isfile(os.path.join(out_dir, pdmx_id(row) + ".mxl"))}
    if missing:
        with tarfile.open(os.path.join(work, "mxl.tar.gz"), "r|gz") as tar:
            for member in tar:
                name = member.name.lstrip("./")
                if name in missing:
                    row = wanted[name][0]
                    data = tar.extractfile(member).read()
                    open(os.path.join(out_dir, pdmx_id(row) + ".mxl"), "wb").write(data)
    for name, (row, composer, title) in wanted.items():
        score_id = pdmx_id(row)
        try:
            notes_per_bar = float(row["notes_per_bar"])
        except ValueError:
            notes_per_bar = None
        items.append({
            "id": f"pdmx-{score_id}",
            "file": os.path.join(out_dir, score_id + ".mxl"),
            "composer": composer,
            "fallback_title": clean(re.sub(r"^Traditional music - ", "", title)),
            "src": "pdmx",
            "lic": "CC0" if row["license"] == "cc-zero" else "PD",
            "link": f"https://musescore.com/score/{score_id}",
            "rating": float(row["rating"]) if row["rating"] not in ("", "NA") else None,
            "notes_per_bar": notes_per_bar,
            "seconds": float(row["song_length.seconds"]) if row["song_length.seconds"] not in ("", "NA") else None,
        })
    return items


def pdmx_id(row):
    """MuseScore's score id, from the metadata path (./metadata/5/5740212.json)."""
    return os.path.splitext(os.path.basename(row["metadata"]))[0]


# --- Build ------------------------------------------------------------------------------

def shard(score_id):
    digits = re.sub(r"\D", "", score_id)
    return (digits[-2:] if len(digits) >= 2 else "00")


def build(work, out):
    candidates = openscore(work, "lieder", "lieder", "lc") + openscore(work, "quartets", "quartets", "sq") + pdmx(work)
    print(f"{len(candidates)} candidates", file=sys.stderr)
    entries, files = [], []
    for item in candidates:
        path = item["file"]
        if not os.path.isfile(path) or os.path.getsize(path) > MAX_BYTES:
            continue
        data = open(path, "rb").read()
        try:
            root = read_musicxml(data)
        except Exception:
            continue
        if root.tag not in ("score-partwise", "score-timewise"):
            continue
        title, collection, parts, bars = score_facts(root)
        useless = not title or title.lower() in ("title", "untitled", "score") or re.fullmatch(r"[\W\d]*", title)
        title = item["fallback_title"] if useless else title
        title = tidy_title(title, item["composer"])
        if not title:
            continue
        rel = f"scores/{item['src']}/{shard(item['id'])}/{item['id']}.mxl"
        entry = {
            "id": item["id"],
            "t": title,
            "c": item["composer"],
            "i": instruments(parts),
            "p": len(parts),
            "b": bars,
            "src": item["src"],
            "lic": item["lic"],
            "f": rel,
            "kb": max(1, round(len(data) / 1024)),
            "link": item["link"],
        }
        if item.get("seconds"):
            entry["s"] = round(item["seconds"])
        level = difficulty(bars, item.get("notes_per_bar"))
        if level:
            entry["d"] = level
        if item.get("rating"):
            entry["r"] = round(item["rating"], 1)
        if collection or item.get("set"):
            entry["set"] = collection or item["set"]
        if item.get("lang"):
            entry["lang"] = item["lang"]
        entries.append(entry)
        files.append((path, rel))

    # Only scores that the app's Verovio can actually render.
    ok = verovio_check([p for p, _ in files], work)
    keep = [(e, f) for e, f in zip(entries, files) if f[0] in ok]
    print(f"{len(keep)} of {len(entries)} render in Verovio", file=sys.stderr)

    if os.path.isdir(os.path.join(out, "scores")):
        shutil.rmtree(os.path.join(out, "scores"))
    for entry, (path, rel) in keep:
        target = os.path.join(out, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copyfile(path, target)
    catalog = {
        "version": 1,
        "sources": {
            "lieder": {"name": "OpenScore Lieder", "url": "https://github.com/OpenScore/Lieder", "license": "CC0 1.0"},
            "quartets": {"name": "OpenScore String Quartets", "url": "https://github.com/OpenScore/StringQuartets", "license": "CC0 1.0"},
            "pdmx": {"name": "PDMX (from MuseScore.com)", "url": "https://zenodo.org/records/15571083",
                     "license": "Public domain / CC0 (dataset CC BY 4.0)"},
        },
        "scores": [e for e, _ in sorted(keep, key=lambda ef: (ef[0]["c"], ef[0]["t"]))],
    }
    with gzip.open(os.path.join(out, "catalog.json.gz"), "wt", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
    for name in ("README.md", "LICENSE-NOTES.md"):
        shutil.copyfile(os.path.join(HERE, "branch", name), os.path.join(out, name))
    print(f"catalogue: {len(keep)} scores", file=sys.stderr)


def verovio_check(paths, work):
    """Paths that render in Verovio. Results are cached by file size and time, so rebuilds only check new files."""
    cache_path = os.path.join(work, "verovio-checked.json")
    cache = json.load(open(cache_path)) if os.path.isfile(cache_path) else {}
    def key(path):
        st = os.stat(path)
        return f"{st.st_size}:{int(st.st_mtime)}"
    todo = [p for p in paths if cache.get(p, {}).get("key") != key(p)]
    if todo:
        listing = os.path.join(work, "check.txt")
        open(listing, "w").write("\n".join(todo))
        result = subprocess.run(["node", os.path.join(HERE, "check_scores.js"), listing], capture_output=True, text=True, timeout=36000)
        if result.returncode != 0:
            sys.exit("Verovio check failed: " + result.stderr[-2000:])
        ok = set(line for line in result.stdout.splitlines() if line)
        for p in todo:
            cache[p] = {"key": key(p), "ok": p in ok}
        json.dump(cache, open(cache_path, "w"))
    return set(p for p in paths if cache.get(p, {}).get("ok"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    build(args.work, args.out)
