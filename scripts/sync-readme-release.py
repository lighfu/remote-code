#!/usr/bin/env python3
"""Sync README release cards from a verified Windows release manifest."""

import argparse
import html
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(r"(?:0|[1-9][0-9]{0,8})(?:\.(?:0|[1-9][0-9]{0,8})){2}")
REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*")
LOCALES = {
    "README.md": ("en", "", "ZIP · Node.js bundled", "Go · Portable ZIP"),
    "README.ja.md": ("", "ja", "ZIP · Node.js 同梱", "Go · ポータブル ZIP"),
    "README.zh-CN.md": ("zh-CN", "zh-CN", "ZIP · 内置 Node.js", "Go · 便携版 ZIP"),
    "README.ko.md": ("ko", "ko", "ZIP · Node.js 포함", "Go · 포터블 ZIP"),
}
NOTE_LOCALES = {
    "": ("English", "The Windows x64 package is `{archive_name}`."),
    "ja": ("日本語", "Windows x64 用の配布ファイルは `{archive_name}` です。"),
    "zh-CN": ("简体中文", "Windows x64 安装包为 `{archive_name}`。"),
    "ko": ("한국어", "Windows x64 배포 파일은 `{archive_name}`입니다."),
}


def read_release(manifest: Path) -> tuple[str, str, str]:
    release = json.loads(manifest.read_text(encoding="utf-8"))
    if not isinstance(release, dict):
        raise ValueError("The Windows release manifest must be a JSON object.")
    if release.get("schemaVersion") != 2 or release.get("platform") != "win32-x64" or release.get("runtime") != "native-go":
        raise ValueError("Expected a native Windows x64 release manifest (schema 2).")
    version = release.get("version")
    tag = release.get("tag")
    if not isinstance(version, str) or not VERSION.fullmatch(version) or tag != f"v{version}":
        raise ValueError("The manifest version and tag must match the release version.")
    repository = release.get("repository")
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        raise ValueError("Invalid GitHub repository in the release manifest.")
    archive = release.get("archive")
    archive_name = f"remote-code-{tag}-win32-x64.zip"
    if not isinstance(archive, dict) or archive.get("name") != archive_name:
        raise ValueError(f"The manifest archive name must be {archive_name}.")
    return version, archive_name, f"https://github.com/{repository}/releases/download/{tag}/{archive_name}"


def download_link(url: str) -> str:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname or any(c.isspace() for c in url):
        raise ValueError("The download URL must be an absolute HTTP or HTTPS URL.")
    return html.escape(url, quote=True)


def replace_versions(text: str, version: str, expected: int, source: str) -> str:
    result, count = VERSION.subn(version, text)
    if count != expected:
        raise ValueError(f"Expected {expected} version reference(s) in {source}, found {count}.")
    return result


def update_anchor(readme: str, asset: Path, href: str, version: str, source: str) -> str:
    pattern = re.compile(r'<a href="[^"]+">(?P<image><img src="' + re.escape(asset.as_posix())
                         + r'" [^>]*?alt=")(?P<alt>[^"]+)(?P<end>"></a>)')

    def render(match: re.Match[str]) -> str:
        alt = replace_versions(match["alt"], version, 1, f"{source}: {asset.name} alt text")
        return f'<a href="{href}">{match["image"]}{alt}{match["end"]}'

    result, count = pattern.subn(render, readme)
    if count != 1:
        raise ValueError(f"Expected one anchor for {asset.name} in {source}, found {count}.")
    return result


def note_links(repository_url: str, version: str) -> str:
    links = []
    for locale, (label, _) in NOTE_LOCALES.items():
        path = Path("docs/releases") / locale / f"{version}.md"
        links.append(f"[{label}]({repository_url}/blob/main/{path.as_posix()})")
    return " · ".join(links)


def generate(version: str, archive_name: str, href: str, repository_url: str) -> dict[Path, bytes]:
    files = {}
    for name, (locale, note_locale, legacy_subtitle, subtitle) in LOCALES.items():
        assets = Path("docs/assets/readme") / locale
        windows = assets / "download-windows.svg"
        notes = assets / "card-notes.svg"
        note = Path("docs/releases") / note_locale / f"{version}.md"
        readme = Path(name)
        text = (ROOT / readme).read_bytes().decode("utf-8")
        text = update_anchor(text, windows, href, version, name)
        text = update_anchor(text, notes, note.as_posix(), version, name)
        files[readme] = text.encode("utf-8")

        text = (ROOT / windows).read_bytes().decode("utf-8")
        text = text.replace(f">{legacy_subtitle}</text>", f">{subtitle}</text>", 1)
        files[windows] = replace_versions(text, version, 2, str(windows)).encode("utf-8")
        text = (ROOT / notes).read_bytes().decode("utf-8")
        files[notes] = replace_versions(text, version, 2, str(notes)).encode("utf-8")

    for locale, (_, summary) in NOTE_LOCALES.items():
        note = Path("docs/releases") / locale / f"{version}.md"
        if (ROOT / note).exists():
            files[note] = (ROOT / note).read_bytes()
        else:
            text = f"# {version}\n\n{note_links(repository_url, version)}\n\n{summary.format(archive_name=archive_name)}\n"
            files[note] = text.encode("utf-8")
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--download-url", help="Use a Windows Actions artifact URL instead of a GitHub Release URL.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--output", type=Path, help="Write the generated file overlay to this directory.")
    mode.add_argument("--check", action="store_true", help="Verify the source files already match the generated release cards.")
    args = parser.parse_args()

    try:
        version, archive_name, default_url = read_release(args.manifest)
        repository_url = default_url.split("/releases/", 1)[0]
        files = generate(version, archive_name, download_link(args.download_url or default_url), repository_url)
        output = args.output or ROOT
        changed = []
        for path, data in files.items():
            destination = output / path
            if destination.is_file() and destination.read_bytes() == data:
                continue
            changed.append(path.as_posix())
            if not args.check:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
    except (OSError, ValueError) as error:
        print(f"README release sync failed: {error}", file=sys.stderr)
        return 1

    if args.check and changed:
        print(f"README release {version} is out of date: {', '.join(changed)}", file=sys.stderr)
        return 1
    print(f"README release {version}: {len(files)} files verified, {len(changed)} updated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
