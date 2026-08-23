#!/usr/bin/env python3
"""Publish every VoiceCraft.Java release artifact as an idempotent Modrinth version."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any


FABRIC_API = "P7dR8mSH"
SIMPLE_VOICE_CHAT = "9eGKb6K1"
PLASMO_VOICE = "1bZhdhsH"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--token", default=os.environ.get("MODRINTH_TOKEN", ""))
    parser.add_argument("--version", required=True)
    parser.add_argument("--channel", choices=("release", "beta", "alpha"), required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--changelog", default="")
    parser.add_argument("--status", choices=("listed", "unlisted", "draft"), default="listed")
    parser.add_argument("--featured", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--existing", choices=("skip", "fail"), default="skip")
    parser.add_argument("--api", default="https://api.modrinth.com/v2")
    parser.add_argument("--skip-tag-validation", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if not args.dry_run and not args.token:
        raise SystemExit("MODRINTH_TOKEN is required unless --dry-run is used")
    if not args.artifacts.is_dir():
        raise SystemExit(f"artifact directory does not exist: {args.artifacts}")

    catalog: list[dict[str, Any]] = json.loads(args.catalog.read_text(encoding="utf-8"))
    specs = release_specs(args.artifacts, catalog, args.version)
    if not args.skip_tag_validation:
        validate_modrinth_tags(args, specs)
    existing = set() if args.dry_run else existing_version_numbers(args)
    print(f"Prepared {len(specs)} Modrinth versions for project {args.project}.")

    for spec in specs:
        version_number = spec["version_number"]
        if version_number in existing:
            if args.existing == "skip":
                print(f"SKIP {version_number}: already exists")
                continue
            raise SystemExit(f"Modrinth version already exists: {version_number}")

        data = {
            "name": f"{args.title} — {spec['label']}",
            "version_number": version_number,
            "changelog": args.changelog,
            "dependencies": spec["dependencies"],
            "game_versions": spec["game_versions"],
            "version_type": args.channel,
            "loaders": spec["loaders"],
            "featured": args.featured,
            "status": args.status,
            "project_id": args.project,
            "file_parts": ["file"],
            "primary_file": "file",
            "environment": "dedicated_server_only",
        }
        print(
            f"{'DRY-RUN' if args.dry_run else 'UPLOAD'} {version_number}: "
            f"{spec['artifact'].name} loaders={','.join(spec['loaders'])} "
            f"games={','.join(spec['game_versions'])}"
        )
        if not args.dry_run:
            upload_version(args, data, spec["artifact"])
            existing.add(version_number)
    return 0


def release_specs(artifacts: Path, catalog: list[dict[str, Any]], version: str) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    game_versions = unique(
        str(game)
        for target in catalog
        for game in target.get("modrinthGameVersions", [target["minecraft"]])
    )

    specs.append(
        platform_spec(
            artifacts,
            "paper",
            version,
            ["paper", "purpur", "folia", "spigot", "bukkit"],
            game_versions,
            "Paper / Folia / Bukkit",
            optional_voice_dependencies(True, True),
        )
    )
    specs.append(
        platform_spec(
            artifacts,
            "velocity",
            version,
            ["velocity"],
            game_versions,
            "Velocity",
            optional_voice_dependencies(True, True),
        )
    )
    specs.append(
        platform_spec(
            artifacts,
            "bungeecord",
            version,
            ["bungeecord"],
            game_versions,
            "BungeeCord",
            optional_voice_dependencies(True, True),
        )
    )

    for target in catalog:
        loader = str(target["loader"])
        minecraft = str(target["minecraft"])
        artifact = find_artifact(artifacts, loader, version, minecraft)
        dependencies = optional_voice_dependencies(
            bool(target["simpleVoiceChat"]), bool(target["plasmoVoice"])
        )
        if loader == "fabric":
            dependencies.insert(0, dependency(FABRIC_API, "required"))
        specs.append(
            {
                "artifact": artifact,
                "label": f"{loader_display(loader)} {minecraft}",
                "version_number": f"{loader}-{minecraft}-{version}",
                "loaders": [loader],
                "game_versions": [str(game) for game in target.get("modrinthGameVersions", [minecraft])],
                "dependencies": dependencies,
            }
        )
    return specs


def platform_spec(
    artifacts: Path,
    platform: str,
    version: str,
    loaders: list[str],
    games: list[str],
    label: str,
    dependencies: list[dict[str, str]],
) -> dict[str, Any]:
    return {
        "artifact": find_artifact(artifacts, platform, version),
        "label": label,
        "version_number": f"{platform}-{version}",
        "loaders": loaders,
        "game_versions": games,
        "dependencies": dependencies,
    }


def find_artifact(artifacts: Path, platform: str, version: str, minecraft: str | None = None) -> Path:
    suffix = f"-{minecraft}.jar" if minecraft else ".jar"
    prefix = f"VoiceCraft.Java-{platform}-{version}"
    candidates = sorted(
        path
        for path in artifacts.glob(f"{prefix}*.jar")
        if path.name.endswith(suffix)
        and not path.name.endswith("-sources.jar")
        and not path.name.endswith("-thin.jar")
    )
    if len(candidates) != 1:
        rendered = ", ".join(path.name for path in candidates) or "none"
        raise SystemExit(
            f"expected exactly one artifact for {platform} {minecraft or ''}, found: {rendered}"
        )
    return candidates[0]


def optional_voice_dependencies(simple_voice_chat: bool, plasmo_voice: bool) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    if simple_voice_chat:
        result.append(dependency(SIMPLE_VOICE_CHAT, "optional"))
    if plasmo_voice:
        result.append(dependency(PLASMO_VOICE, "optional"))
    return result


def dependency(project_id: str, dependency_type: str) -> dict[str, str]:
    return {"project_id": project_id, "dependency_type": dependency_type}


def loader_display(loader: str) -> str:
    return {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}.get(loader, loader)


def unique(values: Any) -> list[str]:
    return list(dict.fromkeys(values))


def existing_version_numbers(args: argparse.Namespace) -> set[str]:
    project = urllib.parse.quote(args.project, safe="")
    request = urllib.request.Request(
        f"{args.api}/project/{project}/version",
        headers=headers(args.token),
    )
    versions = json.loads(open_request(request).decode("utf-8"))
    return {str(version["version_number"]) for version in versions}


def validate_modrinth_tags(args: argparse.Namespace, specs: list[dict[str, Any]]) -> None:
    valid_games = {
        str(item["version"])
        for item in json.loads(open_request(urllib.request.Request(
            f"{args.api}/tag/game_version", headers=headers(args.token))).decode("utf-8"))
    }
    valid_loaders = {
        str(item["name"])
        for item in json.loads(open_request(urllib.request.Request(
            f"{args.api}/tag/loader", headers=headers(args.token))).decode("utf-8"))
    }
    requested_games = {game for spec in specs for game in spec["game_versions"]}
    requested_loaders = {loader for spec in specs for loader in spec["loaders"]}
    unknown_games = sorted(requested_games - valid_games)
    unknown_loaders = sorted(requested_loaders - valid_loaders)
    if unknown_games or unknown_loaders:
        raise SystemExit(
            f"unsupported Modrinth metadata: game_versions={unknown_games}, loaders={unknown_loaders}"
        )
    print(f"Validated {len(requested_games)} game versions and {len(requested_loaders)} loaders against Modrinth.")


def upload_version(args: argparse.Namespace, data: dict[str, Any], artifact: Path) -> None:
    boundary = f"voicecraft-{uuid.uuid4().hex}"
    body = multipart_body(boundary, data, artifact)
    request = urllib.request.Request(
        f"{args.api}/version",
        data=body,
        method="POST",
        headers={
            **headers(args.token),
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
        },
    )
    response = json.loads(open_request(request).decode("utf-8"))
    print(f"Created Modrinth version {response['id']}: {response['version_number']}")


def multipart_body(boundary: str, data: dict[str, Any], artifact: Path) -> bytes:
    delimiter = f"--{boundary}\r\n".encode()
    closing = f"--{boundary}--\r\n".encode()
    chunks: list[bytes] = []
    chunks.extend(
        (
            delimiter,
            b'Content-Disposition: form-data; name="data"\r\n',
            b"Content-Type: application/json\r\n\r\n",
            json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            b"\r\n",
            delimiter,
            f'Content-Disposition: form-data; name="file"; filename="{artifact.name}"\r\n'.encode(),
            f"Content-Type: {mimetypes.guess_type(artifact.name)[0] or 'application/java-archive'}\r\n\r\n".encode(),
            artifact.read_bytes(),
            b"\r\n",
            closing,
        )
    )
    return b"".join(chunks)


def headers(token: str) -> dict[str, str]:
    result = {"User-Agent": "VoiceCraft.Java GitLab CI (https://java.voicecraft.chat)"}
    if token:
        result["Authorization"] = token
    return result


def open_request(request: urllib.request.Request) -> bytes:
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Modrinth API returned HTTP {error.code}: {details}") from error


if __name__ == "__main__":
    sys.exit(main())
