#!/usr/bin/env python3
"""Snapshot release manuals and build all versions for GitHub Pages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parents[1]
WEBSITE = ROOT / "website"
MANIFEST = WEBSITE / "versions.json"
DEFAULT_URL = "http://sc.jvrcruz.games/"
LEGACY_URL = "https://jvrcruzGAMES.github.io/SiliconCraft/"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")


def read_manifest():
    return json.loads(MANIFEST.read_text())


def mod_version():
    return re.search(r"^mod_version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M)[1].strip()


def digest(directory):
    result = hashlib.sha256()
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            result.update(path.relative_to(directory).as_posix().encode())
            result.update(b"\0")
            result.update(path.read_bytes())
            result.update(b"\0")
    return result.hexdigest()


def release_directory(version):
    if not VERSION.fullmatch(version):
        raise ValueError(f"Invalid mod version: {version}")
    return WEBSITE / "versions" / version


def rewrite_links(text, origin, destination):
    """Turn repository links into self-contained snapshot links."""
    def replace(match):
        url = urlsplit(match[1])
        if url.scheme or url.netloc or not url.path:
            return match[0]
        source = (origin.parent / url.path).resolve()
        if source == ROOT / "README.md":
            target = Path("project.md")
        elif source == ROOT / "LICENSE":
            target = Path("license.md")
        elif source.is_relative_to(ROOT / "docs"):
            target = source.relative_to(ROOT / "docs")
        elif source.is_relative_to(ROOT):
            return "](https://github.com/jvrcruzGAMES/SiliconCraft/blob/master/" + source.relative_to(ROOT).as_posix() + ")"
        else:
            raise ValueError(f"Unsupported local documentation link in {origin}: {url.path}")
        relative = os.path.relpath(target, destination.parent).replace(os.sep, "/")
        return "](" + urlunsplit(("", "", relative, url.query, url.fragment)) + ")"
    return re.sub(r"\]\(([^)]+)\)", replace, text)


def snapshot(version):
    if version != mod_version():
        raise ValueError(f"Snapshot {version} does not match mod_version={mod_version()}")
    manifest = read_manifest()
    target = release_directory(version)
    if target.exists() or any(item["version"] == version for item in manifest["versions"]):
        raise ValueError(f"Documentation for {version} already exists; existing snapshots are preserved")
    # Stage the complete snapshot before changing the release registry.
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent) as temporary:
        staged = Path(temporary) / "docs"
        shutil.copytree(ROOT / "docs", staged)
        for source in (ROOT / "docs").rglob("*.md"):
            relative = source.relative_to(ROOT / "docs")
            (staged / relative).write_text(rewrite_links(source.read_text(), source, relative))
        index = staged / "README.md"
        index.write_text(index.read_text().replace(
            "This directory is the working manual.",
            f"This manual documents SiliconCraft **{version}**.",
        ))
        (staged / "project.md").write_text(rewrite_links(
            (ROOT / "README.md").read_text(), ROOT / "README.md", Path("project.md")))
        (staged / "license.md").write_text("# MIT License\n\n```text\n" + (ROOT / "LICENSE").read_text() + "```\n")
        (staged / "assets").mkdir(exist_ok=True)
        shutil.copyfile(ROOT / "src/main/resources/siliconcraft.png", staged / "assets/logo.png")
        # hash includes source examples, images, and license, not generated HTML.
        (Path(temporary) / "navigation.json").write_text(json.dumps(navigation(), indent=2) + "\n")
        checksum = digest(Path(temporary))
        Path(temporary).rename(target)
    manifest["versions"].insert(0, {"version": version, "sha256": checksum})
    manifest["latest"] = version
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Created documentation snapshot {version}: {target.relative_to(ROOT)}")


def check():
    manifest = read_manifest()
    versions = [item["version"] for item in manifest["versions"]]
    if not versions or len(versions) != len(set(versions)):
        raise ValueError("The version registry must contain unique releases")
    if manifest["latest"] != mod_version() or mod_version() not in versions:
        raise ValueError(f"Version bump requires a new snapshot: python3 scripts/docs_site.py snapshot {mod_version()}")
    for item in manifest["versions"]:
        directory = release_directory(item["version"]) / "docs"
        if not (directory / "README.md").is_file() or digest(directory.parent) != item["sha256"]:
            raise ValueError(f"Snapshot {item['version']} is missing or has been modified")
    print(f"Validated {len(versions)} preserved release snapshot(s); latest is {manifest['latest']}")
    return manifest


def navigation():
    return [
        {"Overview": "README.md"},
        {"Getting Started": [
            {"Build your first computer": "getting-started/README.md"},
            {"Peripheral wiring": "getting-started/peripherals.md"},
            {"Optional integrations": "getting-started/integrations.md"},
            {"Server network settings": "getting-started/network-settings.md"},
        ]},
        {"Assembly": [
            {"Introduction": "assembly/README.md"},
            {"Syntax and projects": "assembly/syntax.md"},
            {"Instruction reference": "assembly/reference.md"},
            {"Peripherals": "assembly/peripherals.md"},
            {"Networking": "assembly/network.md"},
            {"Create and airships": "assembly/create-peripherals.md"},
            {"Examples": "assembly/examples/README.md"},
        ]},
        {"N2": [
            {"Introduction": "n-language/n2/README.md"},
            {"Language guide": "n-language/n2/language.md"},
            {"Libraries": "n-language/n2/libraries.md"},
            {"Projects and imports": "n-language/n2/projects.md"},
            {"External compiler (scc)": "n-language/n2/compiler.md"},
            {"DeviceKit peripherals": "n-language/n2/peripherals.md"},
            {"Networking": "n-language/n2/network.md"},
            {"Create and airships": "n-language/n2/create-peripherals.md"},
            {"Examples": "n-language/n2/examples/README.md"},
        ]},
        {"N language overview": "n-language/README.md"},
        {"Project": "project.md"},
        {"License": "license.md"},
    ]


def build(output, base_url):
    manifest = check()
    output = output.resolve()
    if output == ROOT / "build" or not output.is_relative_to(ROOT / "build"):
        raise ValueError("Site output must be inside the project's build directory")
    if not base_url.endswith("/"):
        base_url += "/"
    root_path = urlsplit(base_url).path
    releases = [{
        "version": item["version"],
        "url": root_path + item["version"] + "/",
        "latest": item["version"] == manifest["latest"],
        "pages": [p.relative_to(release_directory(item["version"]) / "docs").as_posix()
                  for p in (release_directory(item["version"]) / "docs").rglob("*.md")],
    } for item in manifest["versions"]]
    # Build before replacing the prior output so a failed build leaves it intact.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        stage = Path(temporary) / "site"
        stage.mkdir()
        for item in manifest["versions"]:
            version = item["version"]
            docs = release_directory(version) / "docs"
            with tempfile.TemporaryDirectory() as work:
                prepared = Path(work) / "docs"
                shutil.copytree(docs, prepared)
                # Hosting URLs apply to every release without rewriting preserved snapshots.
                for document in prepared.rglob("*.md"):
                    document.write_text(document.read_text().replace(LEGACY_URL, base_url))
                for asset in (WEBSITE / "assets").iterdir():
                    shutil.copyfile(asset, prepared / "assets" / asset.name)
                config = {
                    "site_name": "SiliconCraft", "site_description": f"SiliconCraft {version} documentation",
                    "site_url": base_url + version + "/", "docs_dir": str(prepared),
                    "site_dir": str(stage / version), "use_directory_urls": True,
                    "repo_url": "https://github.com/jvrcruzGAMES/SiliconCraft", "edit_uri": "",
                    "theme": {"name": "material", "custom_dir": str(WEBSITE / "overrides"),
                              "logo": "assets/logo.png", "favicon": "assets/logo.png", "font": False,
                              "palette": [{"scheme": "slate", "primary": "custom", "accent": "teal"}],
                              "features": ["navigation.sections", "navigation.top", "content.code.copy", "search.highlight"]},
                    "plugins": ["search"], "nav": json.loads((docs.parent / "navigation.json").read_text()),
                    "markdown_extensions": ["tables", "fenced_code", {"toc": {"permalink": True}},
                                            "pymdownx.highlight", "pymdownx.superfences"],
                    "hooks": [str(WEBSITE / "hooks.py")],
                    "extra_css": ["assets/style.css"], "extra_javascript": ["assets/versions.js"],
                    "extra": {"mod_version": version, "releases": releases},
                    "validation": {"links": {"anchors": "warn", "unrecognized_links": "warn"}},
                }
                config_path = Path(work) / "mkdocs.yml"
                config_path.write_text(json.dumps(config))
                subprocess.run([sys.executable, "-m", "mkdocs", "build", "--strict", "--config-file", str(config_path)], check=True)
        latest = manifest["latest"]
        links = "".join(f'<li><a href="{r["version"]}/">Version {r["version"]}{" · latest" if r["latest"] else ""}</a></li>' for r in releases)
        (stage / "index.html").write_text(f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SiliconCraft documentation</title><style>
body{{margin:0;background:#0c1927;color:#e2edf2;font:18px/1.6 system-ui,sans-serif}}main{{max-width:48rem;margin:10vh auto;padding:2rem}}
img{{width:96px;image-rendering:pixelated}}h1{{font-size:2.5rem;line-height:1.15}}a{{color:#a8e5df}}li{{padding:.5rem 0}}.start{{display:inline-block;padding:.65rem 1rem;background:#183d46;border:1px solid #83c8c3;border-radius:.4rem}}
</style></head><body><main><img src="{latest}/assets/logo.png" alt="SiliconCraft SC chip logo">
<h1>SiliconCraft documentation</h1><p>Build your computer. Choose its instructions. Program your world.</p>
<p>Read the manual for your installed mod version. Each release keeps its own chapters, references, and downloadable source examples.</p>
<p><a class="start" href="{latest}/">Read version {latest}</a></p><h2>Mod versions</h2><ul>{links}</ul>
<p><a href="https://github.com/jvrcruzGAMES/SiliconCraft">Source on GitHub</a></p></main></body></html>''')
        (stage / ".nojekyll").touch()
        shutil.copyfile(WEBSITE / "CNAME", stage / "CNAME")
        if output.exists():
            shutil.rmtree(output)
        shutil.copytree(stage, output)
    print(f"Built documentation: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("snapshot").add_argument("version")
    sub.add_parser("check")
    build_parser = sub.add_parser("build")
    build_parser.add_argument("--output", type=Path, default=ROOT / "build/docs-site")
    build_parser.add_argument("--base-url", default=DEFAULT_URL)
    args = parser.parse_args()
    try:
        if args.command == "snapshot":
            snapshot(args.version)
        elif args.command == "check":
            check()
        else:
            build(args.output, args.base_url)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
