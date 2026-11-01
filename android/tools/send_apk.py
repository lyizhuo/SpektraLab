#!/usr/bin/env python3
"""Build debug APK and send it to a Telegram bot with a changelog.

Usage:
    python tools/send_apk.py [--config <path>] [--project-root <path>]
                             [--no-changelog] [--caption <text>]

Config file (JSON) keys:
    bot_token     -- Telegram Bot API token (from @BotFather)
    chat_id       -- Target chat ID (from @userinfobot or @getmyid_bot)
    project_root  -- Absolute path to the Android project root

The script auto-generates a changelog from git commit messages since the last
build tag (or the last 5 commits if no tag exists).
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("[setup] Installing requests...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests


def load_config(config_path: Path) -> dict:
    if not config_path.exists():
        sys.exit(f"[error] Config file not found: {config_path}")
    with open(config_path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    for key in ("bot_token", "chat_id", "project_root"):
        if not cfg.get(key) or "your" in str(cfg[key]).lower() or "填入" in str(cfg[key]):
            sys.exit(f"[error] Please fill in '{key}' in {config_path}")
    return cfg


def get_git_changelog(project_root: Path, max_entries: int = 10) -> str:
    """Get a brief changelog from git commit messages since the last build tag."""
    try:
        # Find the last build tag (format: build-YYYYMMDD-N or v*-build-*)
        tag_result = subprocess.run(
            ["git", "tag", "--list", "build-*", "--sort=-creatordate"],
            cwd=str(project_root),
            capture_output=True,
            text=True,
        )
        last_tag = tag_result.stdout.strip().split("\n")[0] if tag_result.stdout.strip() else None

        if last_tag:
            log_range = f"{last_tag}..HEAD"
        else:
            log_range = f"HEAD~{max_entries}"

        # Get commit subjects
        log_result = subprocess.run(
            ["git", "log", log_range, "--pretty=format:%s", "--no-merges"],
            cwd=str(project_root),
            capture_output=True,
            text=True,
        )
        commits = [line.strip() for line in log_result.stdout.strip().split("\n") if line.strip()]

        if not commits:
            return ""

        # Build changelog
        lines = []
        for i, commit in enumerate(commits[:max_entries], 1):
            lines.append(f"  {i}. {commit}")

        if last_tag:
            header = f"Changes since {last_tag}:"
        else:
            header = f"Recent changes (last {len(commits[:max_entries])} commits):"

        return f"{header}\n" + "\n".join(lines)
    except Exception as e:
        return f"(changelog unavailable: {e})"


def get_diff_stats(project_root: Path) -> str:
    """Get a one-line summary of what changed in the working tree."""
    try:
        result = subprocess.run(
            ["git", "diff", "--stat"],
            cwd=str(project_root),
            capture_output=True,
            text=True,
        )
        if result.stdout.strip():
            lines = result.stdout.strip().split("\n")
            summary = lines[-1]  # e.g. "3 files changed, 10 insertions(+), 5 deletions(-)"
            return f"Uncommitted changes: {summary}"
        return ""
    except Exception:
        return ""


def build_apk(project_root: Path) -> Path:
    gradlew = project_root / "gradlew.bat"
    if not gradlew.exists():
        gradlew = project_root / "gradlew"
        if not gradlew.exists():
            sys.exit(f"[error] gradlew not found in {project_root}")

    print("[build] Running gradlew assembleDebug...")
    result = subprocess.run(
        [str(gradlew), "assembleDebug", "-Pspektralab.heif=true"],
        cwd=str(project_root),
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"[error] Build failed (exit {result.returncode})")
        print(result.stdout[-2000:] if result.stdout else "")
        print(result.stderr[-2000:] if result.stderr else "")
        sys.exit(1)

    # Search for the debug APK in common locations
    apk_dirs = [
        project_root / "app" / "build" / "outputs" / "apk" / "debug",
        project_root / "app" / "build" / "outputs" / "apk",
    ]
    apk_path = None
    for apk_dir in apk_dirs:
        if apk_dir.exists():
            apks = list(apk_dir.glob("*.apk"))
            if apks:
                apk_path = apks[0]
                break

    if not apk_path:
        sys.exit(f"[error] APK not found after build. Searched: {apk_dirs}")

    size_mb = apk_path.stat().st_size / (1024 * 1024)
    print(f"[build] APK built: {apk_path.name} ({size_mb:.1f} MB)")
    return apk_path


def read_local_properties(project_root: Path) -> dict:
    """R2 credentials live in the git-ignored local.properties."""
    path = project_root / "local.properties"
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def r2_environment(props: dict) -> dict:
    env = os.environ.copy()
    env["AWS_ACCESS_KEY_ID"] = props["r2.accessKeyId"]
    env["AWS_SECRET_ACCESS_KEY"] = props["r2.secretAccessKey"]
    env["AWS_DEFAULT_REGION"] = "auto"
    return env


def run_aws(project_root: Path, endpoint: str, env: dict, args: list, timeout: int = 900) -> str:
    result = subprocess.run(
        ["aws", "--endpoint-url", endpoint] + args,
        cwd=str(project_root),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if result.returncode != 0:
        print(result.stdout[-2000:] if result.stdout else "")
        print(result.stderr[-2000:] if result.stderr else "")
        sys.exit(f"[r2] AWS CLI failed (exit {result.returncode})")
    return result.stdout


def git_short(project_root: Path) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=str(project_root),
        capture_output=True,
        text=True,
    )
    value = result.stdout.strip()
    return value if value else time.strftime("%Y%m%d-%H%M%S")


def version_name(project_root: Path) -> str:
    gradle = project_root / "app" / "build.gradle.kts"
    if gradle.exists():
        match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if match:
            return match.group(1)
    return "dev"


def send_via_r2(apk_path: Path, project_root: Path, cfg: dict, caption: str) -> None:
    """Large APKs exceed the Bot API 50 MB limit: upload to R2, prune to 3, send a link."""
    props = read_local_properties(project_root)
    required = ("r2.endpoint", "r2.bucket", "r2.accessKeyId", "r2.secretAccessKey")
    missing = [key for key in required if not props.get(key)]
    if missing:
        sys.exit(f"[r2] Missing {', '.join(missing)} in {project_root / 'local.properties'}")

    endpoint = props["r2.endpoint"].rstrip("/")
    bucket = props["r2.bucket"]
    env = r2_environment(props)
    version = version_name(project_root)
    # Keep each project's builds under its own prefix: the prune below must not
    # delete another app's APKs that share the bucket.
    prefix = "apk/spektralab/"
    key = f"{prefix}SpektraLab-v{version}-{git_short(project_root)}.apk"
    size_mb = apk_path.stat().st_size / (1024 * 1024)

    print(f"[r2] Uploading {apk_path.name} ({size_mb:.1f} MB) to s3://{bucket}/{key}")
    run_aws(
        project_root,
        endpoint,
        env,
        [
            "s3",
            "cp",
            str(apk_path),
            f"s3://{bucket}/{key}",
            "--content-type",
            "application/vnd.android.package-archive",
        ],
    )

    listing = json.loads(
        run_aws(
            project_root,
            endpoint,
            env,
            ["s3api", "list-objects-v2", "--bucket", bucket, "--prefix", prefix, "--output", "json"],
        )
    )
    objects = [
        item for item in listing.get("Contents", [])
        if item["Key"].lower().endswith(".apk")
    ]
    objects.sort(key=lambda item: item["LastModified"], reverse=True)
    print(f"[r2] {len(objects)} APK object(s); keeping the newest 3")
    for item in objects[3:]:
        print(f"[r2] Deleting old {item['Key']} ({item['LastModified']})")
        run_aws(
            project_root,
            endpoint,
            env,
            ["s3api", "delete-object", "--bucket", bucket, "--key", item["Key"]],
        )

    url = run_aws(
        project_root,
        endpoint,
        env,
        ["s3", "presign", f"s3://{bucket}/{key}", "--expires-in", "604800"],
    ).strip()
    probe = requests.get(url, headers={"Range": "bytes=0-0"}, timeout=60, stream=True)
    if probe.status_code not in (200, 206):
        sys.exit(f"[r2] Presigned URL probe failed: HTTP {probe.status_code} {probe.text[:300]}")
    print(f"[r2] Presigned URL ready (HTTP {probe.status_code})")

    message = (
        f"{caption}\n\n"
        f"直接下载 APK（{size_mb:.0f} MB）：\n{url}\n"
        "链接 7 天有效；R2 只保留最新 3 个版本。"
    )
    response = requests.post(
        f"https://api.telegram.org/bot{cfg['bot_token']}/sendMessage",
        data={
            "chat_id": cfg["chat_id"],
            "text": message,
            "disable_web_page_preview": "true",
        },
        timeout=60,
    )
    if response.status_code == 200:
        print("[send] R2 link sent successfully!")
    else:
        print(f"[error] Telegram API returned {response.status_code}: {response.text}")
        sys.exit(1)


def send_to_telegram(apk_path: Path, bot_token: str, chat_id: str, caption: str) -> None:
    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"
    print("[send] Uploading to Telegram...")

    with open(apk_path, "rb") as f:
        resp = requests.post(
            url,
            data={
                "chat_id": chat_id,
                "caption": caption,
            },
            files={
                "document": (
                    apk_path.name,
                    f,
                    "application/vnd.android.package-archive",
                )
            },
            timeout=120,
        )

    if resp.status_code == 200:
        print("[send] APK sent successfully!")
    else:
        print(f"[error] Telegram API returned {resp.status_code}: {resp.text}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Build APK and send to Telegram")
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "send_apk_config.json",
        help="Path to config file",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=None,
        help="Override project root (default: from config file)",
    )
    parser.add_argument(
        "--no-changelog",
        action="store_true",
        help="Skip git changelog generation",
    )
    parser.add_argument(
        "--caption",
        type=str,
        default=None,
        help="Custom caption text (overrides auto-generated changelog)",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    project_root = args.project_root or Path(cfg["project_root"])

    apk_path = build_apk(project_root)

    # Build caption
    if args.caption:
        caption = args.caption
    else:
        parts = [f"APK: {apk_path.name}"]
        if not args.no_changelog:
            changelog = get_git_changelog(project_root)
            diff_stats = get_diff_stats(project_root)
            if changelog:
                parts.append(changelog)
            if diff_stats:
                parts.append(diff_stats)
        caption = "\n\n".join(parts)

    if apk_path.stat().st_size > 50 * 1024 * 1024:
        send_via_r2(apk_path, project_root, cfg, caption)
    else:
        send_to_telegram(apk_path, cfg["bot_token"], cfg["chat_id"], caption)


if __name__ == "__main__":
    main()
