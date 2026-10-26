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
    """Title, part names, measure count from the MusicXML document."""
    title = ""
    for path in ("work/work-title", "movement-title"):
        el = root.find(path)
        if el is not None and clean(el.text):
            title = clean(el.text)
            break
    parts = []
    for part in root.findall("part-list/score-part"):
        name = part.find("part-name")
        parts.append(clean(name.text) if name is not None and name.text else "")
    first = root.find("part")
    bars = len(first.findall("measure")) if first is not None else 0
    return title, parts, bars


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


def instruments(parts):
    """Plain instrument families from part names, e.g. ["Voice", "Piano"]."""
    found = []
    for name in parts:
        low = " " + re.sub(r"[^a-z ]+", " ", unicodedata.normalize("NFD", name.lower())) + " "
        match = None
        for word, family in sorted(INSTRUMENT_WORDS.items(), key=lambda kv: -len(kv[0])):
            if f" {word} " in low or (len(word) > 4 and word in low):
                match = family
                break
        if match and match not in found:
            found.append(match)
    return found


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
        title, parts, bars = score_facts(root)
        title = title if title and title.lower() not in ("title", "untitled", "score") else item["fallback_title"]
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
        if item.get("set"):
            entry["set"] = item["set"]
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
    listing = os.path.join(work, "check.txt")
    open(listing, "w").write("\n".join(paths))
    result = subprocess.run(["node", os.path.join(HERE, "check_scores.js"), listing], capture_output=True, text=True, timeout=36000)
    if result.returncode != 0:
        sys.exit("Verovio check failed: " + result.stderr[-2000:])
    return set(line for line in result.stdout.splitlines() if line)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    build(args.work, args.out)
