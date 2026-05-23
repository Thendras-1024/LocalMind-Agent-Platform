#!/usr/bin/env python3
"""
Download remote shop/blog image URLs to local storage and replace DB image paths.

Dry-run by default:
  python scripts/migrate_remote_images.py --database hmdp_0

Execute:
  python scripts/migrate_remote_images.py --database hmdp_0 --execute
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Iterable

REMOTE_RE = re.compile(r"^https?://", re.IGNORECASE)
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Localize remote image URLs stored in tb_shop.images and tb_blog.images.")
    parser.add_argument("--host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")))
    parser.add_argument("--user", default=os.getenv("MYSQL_USER", "root"))
    parser.add_argument("--password", default=os.getenv("MYSQL_PASSWORD", "123456"))
    parser.add_argument("--database", default=os.getenv("MYSQL_DATABASE", "hmdp_0"))
    parser.add_argument("--upload-root", default=os.getenv("LOCALMIND_UPLOAD_ROOT", "./data/uploads/imgs"))
    parser.add_argument("--execute", action="store_true", help="Update database. Without this flag the script only reports changes.")
    parser.add_argument("--timeout", type=int, default=10)
    return parser.parse_args()


def connect_mysql(args: argparse.Namespace):
    try:
        import pymysql
    except ImportError:
        print("Missing dependency: pymysql. Install with: pip install pymysql", file=sys.stderr)
        sys.exit(2)

    return pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.database,
        charset="utf8mb4",
        autocommit=False,
    )


def split_images(images: str | None) -> list[str]:
    if not images:
        return []
    return [item.strip() for item in images.split(",") if item.strip()]


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def build_backup_sql(database: str, rows: Iterable[tuple[str, int, str]], backup_path: Path) -> None:
    backup_path.parent.mkdir(parents=True, exist_ok=True)
    with backup_path.open("w", encoding="utf-8") as f:
        f.write(f"-- Image migration backup for {database}\n")
        f.write(f"-- Generated at {datetime.now().isoformat(timespec='seconds')}\n\n")
        for table, row_id, old_images in rows:
            f.write(f"UPDATE `{table}` SET `images` = {sql_quote(old_images)} WHERE `id` = {row_id};\n")


def extension_from_url(url: str) -> str:
    path = urllib.parse.urlparse(url).path
    ext = Path(path).suffix.lower()
    return ext if ext in IMAGE_EXTENSIONS else ".jpg"


def download(url: str, target: Path, timeout: int) -> bool:
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "LocalMindImageMigrator/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content_type = response.headers.get("Content-Type", "")
            if content_type and not content_type.lower().startswith("image/"):
                return False
            target.write_bytes(response.read())
            return True
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def public_legacy_path(table: str, row_id: int, index: int, url: str) -> str:
    ext = extension_from_url(url)
    return f"/imgs/legacy/{table}/{row_id}/{index}{ext}"


def storage_path(upload_root: Path, public_path: str) -> Path:
    if not public_path.startswith("/imgs/"):
        raise ValueError(f"Unexpected public path: {public_path}")
    return upload_root / public_path.removeprefix("/imgs/")


def process_table(cursor, table: str, fallback: str, upload_root: Path, timeout: int, execute: bool):
    cursor.execute(f"SELECT id, images FROM `{table}` WHERE images LIKE '%http://%' OR images LIKE '%https://%'")
    rows = cursor.fetchall()
    backup_rows: list[tuple[str, int, str]] = []
    updates: list[tuple[str, int]] = []
    remote_count = 0
    failed_count = 0

    for row_id, images in rows:
        parts = split_images(images)
        changed = False
        new_parts: list[str] = []
        for idx, part in enumerate(parts):
            if not REMOTE_RE.match(part):
                new_parts.append(part)
                continue

            remote_count += 1
            public_path = public_legacy_path(table, int(row_id), idx, part)
            ok = False
            if execute:
                ok = download(part, storage_path(upload_root, public_path), timeout)
            if ok or not execute:
                new_parts.append(public_path)
            else:
                failed_count += 1
                new_parts.append(fallback)
            changed = True

        if changed:
            backup_rows.append((table, int(row_id), images))
            updates.append((",".join(new_parts), int(row_id)))

    return {
        "table": table,
        "rows": len(rows),
        "remote_count": remote_count,
        "failed_count": failed_count,
        "backup_rows": backup_rows,
        "updates": updates,
    }


def main() -> int:
    args = parse_args()
    upload_root = Path(args.upload_root).resolve()
    conn = connect_mysql(args)
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    backup_path = Path("sql") / f"image_migration_backup_{args.database}_{timestamp}.sql"

    try:
        with conn.cursor() as cursor:
            results = [
                process_table(cursor, "tb_shop", "/imgs/default/shop.svg", upload_root, args.timeout, args.execute),
                process_table(cursor, "tb_blog", "/imgs/default/blog.svg", upload_root, args.timeout, args.execute),
            ]
            backup_rows = [row for result in results for row in result["backup_rows"]]
            if args.execute and backup_rows:
                build_backup_sql(args.database, backup_rows, backup_path)
                for result in results:
                    for new_images, row_id in result["updates"]:
                        cursor.execute(f"UPDATE `{result['table']}` SET images = %s WHERE id = %s", (new_images, row_id))
                conn.commit()

        print("Mode:", "EXECUTE" if args.execute else "DRY-RUN")
        print("Upload root:", upload_root)
        for result in results:
            print(
                f"{result['table']}: rows_with_remote={result['rows']}, "
                f"remote_images={result['remote_count']}, updates={len(result['updates'])}, "
                f"download_failed={result['failed_count']}"
            )
        if args.execute and backup_rows:
            print("Backup SQL:", backup_path)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
