"""Build the offline factual tag catalogue from a pinned reference snapshot.

Only JSON data literals are read; upstream JavaScript is never executed.
Usage: python tools/build_local_tag_library.py [--source-dir PATH]
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata
import urllib.parse
import urllib.request

REVISION = "2186c68862ea12a6a191f44e23bfc1699f8149fa"
REPOSITORY = "https://github.com/star-abyss/ai-tag-toolbox"
FILES = [
    "assets/数据资产/标签/data-tags.js",
    "assets/数据资产/标签/extra-tags.js",
    "assets/数据资产/标签/character-general-tags.json",
    "assets/数据资产/标签/synonyms.js",
    "assets/数据资产/标签/search-keywords.js",
    "assets/数据资产/角色/characters.json",
    "assets/数据资产/角色/manifest.json",
]


def normalize(value):
    return re.sub(r"[\s_]+", " ", unicodedata.normalize("NFKC", str(value)).lower()).strip()


def literal(text, name):
    match = re.search(r"(?:var|const)\s+" + re.escape(name) + r"\s*=\s*", text)
    if not match:
        raise ValueError("Missing data literal: " + name)
    return json.JSONDecoder().raw_decode(text[match.end():])[0]


def build(source_dir, output):
    sources, provenance = {}, []
    for relative in FILES:
        if source_dir:
            data = (source_dir / relative).read_bytes()
        else:
            url = "https://raw.githubusercontent.com/star-abyss/ai-tag-toolbox/" + REVISION + "/" + urllib.parse.quote(relative)
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
        sources[relative] = data.decode("utf-8-sig")
        provenance.append(dict(path=relative, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))

    records = {}
    for filename in FILES[:2]:
        for line in sources[filename].splitlines():
            if not re.match(r'^\s*\[\s*"', line):
                continue
            row = json.loads(line.strip().rstrip(","))
            if len(row) != 6:
                raise ValueError("Unexpected row layout")
            name, zh, aliases, category, section, _ = row
            key = normalize(name)
            if key not in records:
                records[key] = dict(name=name, zh=zh, aliases=set(aliases.split()),
                                    category=category, section=section)
            else:
                records[key]["aliases"].update(aliases.split())
                if not records[key]["zh"]:
                    records[key]["zh"] = zh
    for row in json.loads(sources[FILES[2]]):
        key = normalize(row["en"])
        if key not in records:
            records[key] = dict(name=row["en"], zh=row.get("zh", ""),
                                aliases=set(row.get("aliases", [])),
                                category=row["category"], section=row.get("subcategory", ""))

    synonyms = sources[FILES[3]]
    for phrase, targets in literal(synonyms, "SYNONYMS").items():
        for target in targets:
            if normalize(target) in records:
                records[normalize(target)]["aliases"].add(phrase)
    for mapping in (literal(synonyms, "SYNONYMS_BY_EN"), literal(synonyms, "SYNONYM_ALIASES"),
                    literal(sources[FILES[4]], "SEARCH_KEYWORDS")):
        for name, aliases in mapping.items():
            if normalize(name) in records:
                records[normalize(name)]["aliases"].update(aliases)

    characters = json.loads(sources[FILES[5]])
    output.parent.mkdir(parents=True, exist_ok=True)
    # Build into a new file, replacing only this generated resource after verification.
    temporary = output.with_suffix(".building")
    if temporary.exists():
        temporary.unlink()
    db = sqlite3.connect(temporary)
    db.executescript("""
        PRAGMA page_size=4096;
        PRAGMA user_version=1;
        CREATE TABLE entries (
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, zh TEXT NOT NULL,
          category TEXT NOT NULL, section TEXT NOT NULL, kind TEXT NOT NULL,
          trigger TEXT NOT NULL, detail TEXT NOT NULL, aliases TEXT NOT NULL);
        CREATE TABLE lookup (term TEXT NOT NULL, entry_id INTEGER NOT NULL,
          PRIMARY KEY(term, entry_id)) WITHOUT ROWID;
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    counts = dict(tags=0, characters=0)

    def add(record, kind, trigger="", detail="", extra=()):
        aliases = sorted(str(x) for x in record["aliases"] if x)
        cursor = db.execute("INSERT INTO entries(name,zh,category,section,kind,trigger,detail,aliases) VALUES(?,?,?,?,?,?,?,?)",
                            (record["name"], record["zh"], record["category"], record["section"], kind,
                             trigger, detail, json.dumps(aliases, ensure_ascii=False)))
        keys = {normalize(record["name"]), normalize(record["zh"])}
        keys.update(normalize(x) for x in aliases)
        keys.update(normalize(x) for x in extra)
        db.executemany("INSERT OR IGNORE INTO lookup(term,entry_id) VALUES(?,?)",
                       ((key, cursor.lastrowid) for key in sorted(keys) if key))
        counts["tags" if kind == "tag" else "characters"] += 1

    # Role identity rows are separate from generic vocabulary, including legacy entries.
    character_ids = {normalize(row["id"]) for row in characters}
    for key, record in sorted(records.items()):
        if record["category"] == "character_names" or (
            record["category"] == "character" and record["section"] == "角色名"
        ):
            if key not in character_ids:
                add(record, "character", trigger=record["name"])
        else:
            add(record, "tag")
    for row in sorted(characters, key=lambda r: r["id"]):
        base = records.get(normalize(row["id"]))
        record = dict(name=row["id"], zh=base["zh"] if base else "",
                      aliases=set(base["aliases"]) if base else set(),
                      category="character_names", section=row.get("seriesId", ""))
        # Alternative appearance tags are suggestions, never mandatory character attributes.
        detail = json.dumps(dict(optionalAppearance=row.get("tagIds", [])), ensure_ascii=False, separators=(",", ":"))
        add(record, "character", row.get("trigger", row["id"]), detail,
            (row["id"], row.get("seriesId", ""), row.get("trigger", "").split(",")[0]))
    for key, value in {**counts, "revision": REVISION, "repository": REPOSITORY}.items():
        db.execute("INSERT INTO metadata VALUES(?,?)", (key, str(value)))
    db.execute("CREATE INDEX entry_kind ON entries(kind)")
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert db.execute("SELECT count(*) FROM lookup WHERE term='蓝发'").fetchone()[0] > 0
    db.execute("VACUUM")
    db.close()
    temporary.replace(output)
    manifest = dict(schema=1, repository=REPOSITORY, revision=REVISION, counts=counts,
                    bytes=output.stat().st_size, sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                    sources=provenance, characterSource=json.loads(sources[FILES[6]])["source"])
    output.with_name("manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/main/assets/tag-library/catalogue-v1.db")
    args = parser.parse_args()
    build(args.source_dir, args.output)
