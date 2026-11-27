#!/usr/bin/env python3
"""Merge a freshly published jeno index into the existing repository index."""
from __future__ import annotations

import sys
import tomllib
from pathlib import Path


def load(path: Path) -> dict:
    with path.open("rb") as stream:
        data = tomllib.load(stream)
    if data.get("schemaVersion") != 1:
        raise SystemExit(f"{path}: unsupported schemaVersion")
    if not isinstance(data.get("name"), str) or not data["name"]:
        raise SystemExit(f"{path}: missing name")
    versions = data.get("versions")
    if not isinstance(versions, list) or not all(isinstance(v, str) for v in versions):
        raise SystemExit(f"{path}: invalid versions")
    return data


def render(name: str, versions: list[str]) -> str:
    quoted = ", ".join(f'"{version}"' for version in versions)
    return f'schemaVersion = 1\nname = "{name}"\nversions = [{quoted}]\n'


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <existing-index> <new-index>", file=sys.stderr)
        return 2
    existing_path, new_path = map(Path, sys.argv[1:])
    new = load(new_path)
    if existing_path.is_file():
        existing = load(existing_path)
        if existing["name"] != new["name"]:
            raise SystemExit(
                f"index name mismatch: {existing['name']} != {new['name']}"
            )
        versions = set(existing["versions"]) | set(new["versions"])
    else:
        versions = set(new["versions"])
    existing_path.write_text(render(new["name"], sorted(versions)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
