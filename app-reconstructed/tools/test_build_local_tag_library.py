"""Functional tests for bilingual imports; no network or third-party Python modules."""
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

from build_local_tag_library import chinese_aliases, has_chinese, merge_danbooru, normalize


class BilingualImportTest(unittest.TestCase):
    def test_chinese_correspondence_is_not_an_english_echo_or_a_placeholder(self):
        for value in ("", "Hololive", ":D", "未翻译", "待翻译", None):
            self.assertFalse(has_chinese(value))
        self.assertTrue(has_chinese("初音未来（VOCALOID）"))
        self.assertTrue(has_chinese("机械魔法少女"))

    def test_full_translation_unqualified_role_and_explicit_alternatives_are_indexable(self):
        self.assertIn("初音未来", chinese_aliases("初音未来（VOCALOID）"))
        self.assertIn("初音未来(VOCALOID)", chinese_aliases("初音未来（VOCALOID）"))
        self.assertTrue({"战术背心", "负重背心"}.issubset(chinese_aliases("战术背心 / 负重背心")))
        self.assertEqual("blue hair", normalize("BLUE_HAIR"))

    def test_merge_fills_chinese_without_losing_curated_data_and_excludes_untranslated(self):
        records = {
            "blue hair": dict(name="blue hair", zh="蓝发", aliases={"蓝色头发"}, category="hair", section="颜色", kind="tag"),
            "blurry": dict(name="blurry", zh="模糊", aliases=set(), category="negative", section="", kind="tag"),
            "fixture role": dict(name="fixture_role", zh="", aliases=set(), category="character_names",
                                 section="series", kind="character", trigger="fixture_role",
                                 detail='{"optionalAppearance":["blue hair"]}'),
        }
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source.db"
            with closing(sqlite3.connect(source)) as db:
                db.execute("CREATE TABLE tags(name TEXT,cn_name TEXT,category INTEGER,post_count INTEGER)")
                db.executemany("INSERT INTO tags VALUES(?,?,?,?)", [
                    ("blue_hair", "蓝色头发", 0, 100),
                    ("blurry", "模糊不清", 5, 80),
                    ("fixture_role", "测试角色（测试作品）", 4, 30),
                    ("mechanical_magical_girl", "机械魔法少女", 0, 20),
                    ("english_echo", "English Echo", 1, 10),
                ])
                db.commit()
            stats = merge_danbooru(records, source)
            # On Windows this also proves the import closed its SQLite handle.
            source.unlink()
        self.assertEqual(5, stats["sourceRows"])
        self.assertEqual(1, stats["withoutChineseExcluded"])
        self.assertNotIn("english echo", records)
        self.assertEqual("蓝发", records["blue hair"]["zh"])
        self.assertEqual("hair", records["blue hair"]["category"])
        self.assertIn("蓝色头发", records["blue hair"]["aliases"])
        self.assertEqual("negative", records["blurry"]["category"])
        self.assertEqual("测试角色（测试作品）", records["fixture role"]["zh"])
        self.assertEqual('{"optionalAppearance":["blue hair"]}', records["fixture role"]["detail"])
        self.assertEqual("character", records["fixture role"]["kind"])
        self.assertEqual(100, records["blue hair"]["post_count"])
        self.assertEqual("机械魔法少女", records["mechanical magical girl"]["zh"])


if __name__ == "__main__":
    unittest.main()
