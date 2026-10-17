"""Build a Chinese-searchable offline catalogue from pinned factual tag data.

Only JSON data literals are read; upstream JavaScript is never executed.
Usage: python tools/build_local_tag_library.py [--source-dir PATH] [--danbooru-dir PATH]
"""
import argparse
from collections import Counter
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unicodedata
import urllib.parse
import urllib.request

REVISION = "2186c68862ea12a6a191f44e23bfc1699f8149fa"
REPOSITORY = "https://github.com/star-abyss/ai-tag-toolbox"
DANBOORU_REPOSITORY = "https://github.com/ffdkj/ffdkj-Danbooru_Tag-Chinese-English-Translation-Table"
DANBOORU_REVISION = "fd70c7eee13917d832107c7e3a7b135dc6b2211a"
DANBOORU_HASHES = {
    "tag.sqlite": "18859f6b3a9d1ed2a1c924de39c5ecae67f6ab023606f0647584f5900f843825",
    "LICENSE": "014b110a69780163b0a0a2ed87465d950dc3841694342115aab0db49fc2f3ccc",
}
TOOLBOX_HASHES = [
    "d6490fc20d32e3713ecd92a727413a3e24598a2bb988c8a28b2c58d898fdbbc3",
    "05fd42098bbea0af15b7d12198582cbc875f63064fd0678f7695556e832f68cd",
    "c47a935e8545559455990ffe4d971a5d4fb4765c2645d0b8e1d5f90253fd09d6",
    "38ca8765ce75b77a2c3b6e07c2d443c75387cec6823ea78137582561f8728d28",
    "43ef5f655abeecd8752820fe9737d5e1a6a974b442e5bd6feb98f0ec90db0f80",
    "fb73d688a6cd15c27b71a3c33f317c4fbcd7d680d95c9e4dce3a5a412f2d7e48",
    "e962d9c91ef57c1608f9f8fdbef9e2ace281e3f9b7ec560000f621993cd83b89",
]
DANBOORU_CATEGORIES = {
    0: ("danbooru_general", "常规", "tag"),
    1: ("artist", "画师", "tag"),
    3: ("copyright", "作品", "tag"),
    4: ("character_names", "角色名", "character"),
    5: ("metadata", "元数据", "tag"),
}
CHINESE = re.compile(r"[\u3400-\u9fff]")
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


def has_chinese(value):
    text = str(value or "").strip()
    return bool(CHINESE.search(text)) and text not in {"未知", "暂无", "无", "待翻译", "未翻译"}


def chinese_aliases(value):
    """Index the full translation, its unqualified name, and explicit alternatives."""
    text = unicodedata.normalize("NFKC", str(value)).strip()
    aliases = {text} if has_chinese(text) else set()
    # E.g. 初音未来（VOCALOID） must also match 初音未来 in a Chinese sentence.
    base = re.sub(r"\([^()]*\)", "", text).strip()
    if has_chinese(base):
        aliases.add(base)
    for part in re.split(r"[/、;；|]", base):
        part = part.strip()
        if has_chinese(part):
            aliases.add(part)
    return aliases


def verified_input(relative, repository, revision, expected, source_dir=None):
    if source_dir:
        data = (source_dir / relative).read_bytes()
    else:
        project = repository.removeprefix("https://github.com/")
        url = "https://raw.githubusercontent.com/" + project + "/" + revision + "/" + urllib.parse.quote(relative)
        request = urllib.request.Request(url, headers={"User-Agent": "ComfyPocket-tag-library-builder"})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected:
        raise ValueError(f"Input SHA-256 mismatch: {repository}@{revision}/{relative}")
    return data, dict(repository=repository, revision=revision, path=relative, sha256=digest, bytes=len(data))


def merge_danbooru(records, source):
    stats = Counter()
    with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Danbooru source database failed integrity check")
        rows = db.execute("SELECT name,cn_name,category,post_count FROM tags ORDER BY name")
        for name, translation, category_id, post_count in rows:
            stats["sourceRows"] += 1
            key = normalize(name)
            old = records.get(key)
            if not has_chinese(translation) and not (old and has_chinese(old["zh"])):
                stats["withoutChineseExcluded"] += 1
                continue
            category, section, kind = DANBOORU_CATEGORIES[category_id]
            if old is None:
                old = dict(name=name, zh=translation, aliases=set(), category=category, section=section,
                           kind=kind, trigger=name if kind == "character" else "", detail="", extra=())
                records[key] = old
                stats["added"] += 1
            else:
                stats["merged"] += 1
                if not has_chinese(old["zh"]):
                    old["zh"] = translation
                    stats["legacyTranslationsFilled"] += 1
                # Preserve curated categories such as negative and all optional appearance data.
                if kind == "character":
                    old.update(kind=kind, category=category, trigger=old.get("trigger") or name)
            old["post_count"] = max(0, int(post_count or 0))
            old["aliases"].add(name)
            old["aliases"].update(chinese_aliases(translation))
    return dict(stats)


def literal(text, name):
    match = re.search(r"(?:var|const)\s+" + re.escape(name) + r"\s*=\s*", text)
    if not match:
        raise ValueError("Missing data literal: " + name)
    return json.JSONDecoder().raw_decode(text[match.end():])[0]


def build(source_dir, output, danbooru_dir=None):
    sources, provenance = {}, []
    for relative, expected in zip(FILES, TOOLBOX_HASHES):
        data, origin = verified_input(relative, REPOSITORY, REVISION, expected, source_dir)
        sources[relative] = data.decode("utf-8-sig")
        provenance.append(origin)

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

    for record in records.values():
        if record["category"] == "character_names" or (
            record["category"] == "character" and record["section"] == "角色名"
        ):
            record.update(kind="character", trigger=record["name"])
        else:
            record["kind"] = "tag"
    characters = json.loads(sources[FILES[5]])
    for row in sorted(characters, key=lambda r: r["id"]):
        base = records.get(normalize(row["id"]))
        record = dict(name=row["id"], zh=base["zh"] if base else "",
                      aliases=set(base["aliases"]) if base else set(),
                      category="character_names", section=row.get("seriesId", ""), kind="character",
                      trigger=row.get("trigger", row["id"]))
        # Alternative appearance tags are suggestions, never mandatory character attributes.
        record["detail"] = json.dumps(dict(optionalAppearance=row.get("tagIds", [])), ensure_ascii=False, separators=(",", ":"))
        # A series is context, not an alias for every character; don't flood a title query with identities.
        record["extra"] = (row["id"], row.get("trigger", "").split(",")[0])
        records[normalize(row["id"])] = record

    with tempfile.TemporaryDirectory(prefix="comfypocket-danbooru-") as downloaded:
        local = Path(downloaded)
        for relative, expected in DANBOORU_HASHES.items():
            data, origin = verified_input(relative, DANBOORU_REPOSITORY, DANBOORU_REVISION, expected, danbooru_dir)
            provenance.append(origin)
            (local / relative).write_bytes(data)
        danbooru_stats = merge_danbooru(records, local / "tag.sqlite")
        license_text = (local / "LICENSE").read_bytes()

    # A bilingual entry must contain a real Chinese correspondence. No fabricated translations.
    untranslated = sum(not has_chinese(record["zh"]) for record in records.values())
    records = {key: record for key, record in records.items() if has_chinese(record["zh"])}
    revision = "bilingual-v2-" + hashlib.sha256(
        json.dumps(provenance, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()[:16]
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".building")
    if temporary.exists():
        temporary.unlink()
    db = sqlite3.connect(temporary)
    db.executescript("""
        PRAGMA page_size=4096;
        PRAGMA user_version=2;
        CREATE TABLE entries (
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, zh TEXT NOT NULL,
          category TEXT NOT NULL, section TEXT NOT NULL, kind TEXT NOT NULL,
          trigger TEXT NOT NULL, detail TEXT NOT NULL, aliases TEXT NOT NULL,
          post_count INTEGER NOT NULL);
        CREATE TABLE lookup (term TEXT NOT NULL, entry_id INTEGER NOT NULL,
          PRIMARY KEY(term, entry_id)) WITHOUT ROWID;
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    counts = dict(tags=0, characters=0)
    categories = Counter()
    for key, record in sorted(records.items()):
        record["aliases"].update(chinese_aliases(record["zh"]))
        aliases = sorted(str(x) for x in record["aliases"] if x)
        kind = record["kind"]
        cursor = db.execute("INSERT INTO entries(name,zh,category,section,kind,trigger,detail,aliases,post_count) VALUES(?,?,?,?,?,?,?,?,?)",
                            (record["name"], record["zh"], record["category"], record["section"], kind,
                             record.get("trigger", ""), record.get("detail", ""),
                             json.dumps(aliases, ensure_ascii=False, separators=(",", ":")), record.get("post_count", 0)))
        keys = {normalize(record["name"]), normalize(record["zh"])}
        keys.update(normalize(x) for x in aliases)
        keys.update(normalize(x) for x in record.get("extra", ()))
        db.executemany("INSERT OR IGNORE INTO lookup(term,entry_id) VALUES(?,?)",
                       ((term, cursor.lastrowid) for term in sorted(keys) if term))
        counts["tags" if kind == "tag" else "characters"] += 1
        categories[record["category"]] += 1
    for key, value in {**counts, "revision": revision, "repository": REPOSITORY,
                       "danbooru_revision": DANBOORU_REVISION}.items():
        db.execute("INSERT INTO metadata VALUES(?,?)", (key, str(value)))
    db.execute("CREATE INDEX entry_kind ON entries(kind)")
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert db.execute("SELECT count(*) FROM lookup WHERE term='蓝发'").fetchone()[0] > 0
    db.execute("VACUUM")
    db.close()
    temporary.replace(output)
    output.with_name("ffdkj-LICENSE.txt").write_bytes(license_text)
    manifest = dict(schema=2, repository=REPOSITORY, revision=revision, counts=counts,
                    translatedEntries=sum(counts.values()), categories=dict(sorted(categories.items())),
                    excludedLegacyWithoutChinese=untranslated,
                    danbooru=dict(repository=DANBOORU_REPOSITORY, revision=DANBOORU_REVISION,
                                  license="MIT", licenseAsset="ffdkj-LICENSE.txt", **danbooru_stats),
                    bytes=output.stat().st_size, sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                    sources=provenance, characterSource=json.loads(sources[FILES[6]])["source"])
    output.with_name("manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--danbooru-dir", type=Path, help="Pinned tag.sqlite and LICENSE directory")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/main/assets/tag-library/catalogue-v1.db")
    args = parser.parse_args()
    build(args.source_dir, args.output, args.danbooru_dir)
