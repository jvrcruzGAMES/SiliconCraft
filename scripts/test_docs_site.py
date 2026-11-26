"""Regression checks for release preservation and version-bump enforcement."""
import contextlib
from html.parser import HTMLParser
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import shutil
from unittest.mock import patch

import docs_site

SOURCE_WEBSITE = docs_site.WEBSITE


class ReleaseSnapshotsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.root = Path(self.temporary.name)
        self.website = self.root / "website"
        self.website.mkdir()
        (self.website / "CNAME").write_text("sc.jvrcruz.games\n")
        self.manifest = self.website / "versions.json"
        self.manifest.write_text('{"latest": null, "versions": []}')
        (self.root / "docs").mkdir()
        (self.root / "docs/README.md").write_text("# Manual\n\n[Project](../README.md) · [License](../LICENSE)\n")
        (self.root / "docs/example.n2").write_text("func main()\n    return 0\nend\n")
        (self.root / "README.md").write_text("# Mod\n\n[Manual](docs/README.md)\n")
        (self.root / "LICENSE").write_text("MIT License\n")
        logo = self.root / "src/main/resources/siliconcraft.png"
        logo.parent.mkdir(parents=True)
        logo.write_bytes(b"logo")
        self.set_version("0.1.1")
        for name, value in (("ROOT", self.root), ("WEBSITE", self.website), ("MANIFEST", self.manifest)):
            patcher = patch.object(docs_site, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def set_version(self, version):
        (self.root / "gradle.properties").write_text(f"mod_version={version}\n")

    def test_snapshot_contains_self_contained_links_and_downloads(self):
        docs_site.snapshot("0.1.1")
        docs = self.website / "versions/0.1.1/docs"
        self.assertNotIn("../README.md", (docs / "README.md").read_text())
        self.assertIn("[Project](project.md)", (docs / "README.md").read_text())
        self.assertIn("[Manual](README.md)", (docs / "project.md").read_text())
        self.assertTrue((docs / "example.n2").is_file())
        self.assertTrue((docs / "assets/logo.png").is_file())
        self.assertEqual("0.1.1", docs_site.check()["latest"])

    def test_new_release_preserves_older_snapshot_and_navigation(self):
        docs_site.snapshot("0.1.1")
        older = self.website / "versions/0.1.1"
        checksum = docs_site.digest(older)
        (self.root / "docs/README.md").write_text("# New manual\n")
        self.set_version("0.1.2")
        docs_site.snapshot("0.1.2")
        self.assertEqual(checksum, docs_site.digest(older))
        self.assertTrue((older / "navigation.json").is_file())
        self.assertEqual(["0.1.2", "0.1.1"], [r["version"] for r in docs_site.check()["versions"]])

    def test_version_bump_without_snapshot_fails(self):
        docs_site.snapshot("0.1.1")
        self.set_version("0.1.2")
        with self.assertRaisesRegex(ValueError, "Version bump requires a new snapshot"):
            docs_site.check()

    def test_existing_release_cannot_be_overwritten(self):
        docs_site.snapshot("0.1.1")
        manifest = self.manifest.read_bytes()
        with self.assertRaisesRegex(ValueError, "already exists"):
            docs_site.snapshot("0.1.1")
        self.assertEqual(manifest, self.manifest.read_bytes())

    def test_changed_archive_is_detected(self):
        docs_site.snapshot("0.1.1")
        (self.website / "versions/0.1.1/docs/README.md").write_text("changed")
        with self.assertRaisesRegex(ValueError, "has been modified"):
            docs_site.check()

    def test_snapshot_version_must_match_mod_and_be_a_safe_path(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            docs_site.snapshot("0.1.2")
        with self.assertRaisesRegex(ValueError, "Invalid mod version"):
            docs_site.release_directory("../../escape")

    @unittest.skipUnless(importlib.util.find_spec("mkdocs"), "Install website/requirements.txt to test rendering")
    def test_rendered_version_menu_preserves_page_and_falls_back_for_new_pages(self):
        for name in ("assets", "overrides"):
            shutil.copytree(SOURCE_WEBSITE / name, self.website / name)
        shutil.copyfile(SOURCE_WEBSITE / "hooks.py", self.website / "hooks.py")
        navigation = [{"Overview": "README.md"}, {"Guide": "guide.md"}]
        (self.root / "docs/guide.md").write_text("# Shared guide\n\n[Documentation](https://jvrcruzGAMES.github.io/SiliconCraft/)\n")
        with patch.object(docs_site, "navigation", return_value=navigation):
            docs_site.snapshot("0.1.1")
            self.set_version("0.1.2")
            (self.root / "docs/new.md").write_text("# New page\n")
            navigation.append({"New": "new.md"})
            docs_site.snapshot("0.1.2")
        output = self.root / "build/site"
        docs_site.build(output, "https://example.org/project/")

        class Options(HTMLParser):
            def __init__(self, page):
                super().__init__()
                self.targets = []
                self.feed(page.read_text())

            def handle_starttag(self, tag, attributes):
                if tag == "option":
                    self.targets.append(dict(attributes)["value"])

        self.assertEqual(
            ["/project/0.1.2/guide/", "/project/0.1.1/guide/"],
            Options(output / "0.1.2/guide/index.html").targets,
        )
        self.assertEqual(
            ["/project/0.1.2/new/", "/project/0.1.1/"],
            Options(output / "0.1.2/new/index.html").targets,
        )
        for version in ("0.1.1", "0.1.2"):
            self.assertTrue((output / version / "example.n2").is_file())
            search = json.loads((output / version / "search/search_index.json").read_text())
            self.assertTrue(any("Shared guide" in entry["title"] for entry in search["docs"]))
        # The production domain has no repository prefix. Archives retain their original URLs.
        docs_site.build(output, docs_site.DEFAULT_URL)
        self.assertEqual(
            ["/0.1.2/guide/", "/0.1.1/guide/"],
            Options(output / "0.1.2/guide/index.html").targets,
        )
        self.assertEqual("sc.jvrcruz.games\n", (output / "CNAME").read_text())
        for version in ("0.1.1", "0.1.2"):
            rendered = (output / version / "guide/index.html").read_text()
            self.assertIn(f'href="http://sc.jvrcruz.games/{version}/guide/"', rendered)
            self.assertIn('href="http://sc.jvrcruz.games/"', rendered)
            self.assertNotIn(docs_site.LEGACY_URL, rendered)
            archived = self.website / "versions" / version / "docs/guide.md"
            self.assertIn(docs_site.LEGACY_URL, archived.read_text())
        docs_site.check()


if __name__ == "__main__":
    unittest.main()
