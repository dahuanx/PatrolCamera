#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 App 内更新用的清单 version.json（放在仓库根目录，App 只读）。

App 侧读取顺序（见 UpdateChecker.kt）：
    1) https://gitee.com/dahuanxx/PatrolCamera/raw/master/version.json
    2) https://raw.githubusercontent.com/dahuanx/PatrolCamera/main/version.json

用法：
    python tools/gen_version_json.py <versionCode> <versionName> <APK路径> [更新说明文件]

示例（在本仓库根目录执行）：
    python tools/gen_version_json.py 27 1.3.6 "APK/巡查水印相机-v1.3.6-正式版.apk" notes.txt
    python tools/gen_version_json.py 28 1.3.7 "APK/巡查水印相机-v1.3.7-正式版.apk" --notes "1. 修某某问题"

可选参数：
    --notes "<文字>"        更新说明（与"更新说明文件"二选一，文件优先）
    --min-version-code N    低于该版本强制更新（默认 0，即不强制）
    --force                 显式标记强制更新
    --out <路径>            输出路径（默认仓库根目录 version.json）
    --release-tag vX.Y.Z    发布 tag（默认 v<versionName>）

说明：APK 体积与 SHA-256 由本脚本自动计算并写入清单，
App 下载后会按这两个值 + 包内 versionCode 做三重校验。
"""
import argparse
import hashlib
import json
import os
import sys

GITEE_REPO = "dahuanxx/PatrolCamera"
GITHUB_REPO = "dahuanx/PatrolCamera"
ASSET_NAME = "PatrolCamera-v{ver}.apk"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("version_code", type=int, help="versionCode，整数，App 用它比较新旧")
    ap.add_argument("version_name", help="versionName，如 1.3.6")
    ap.add_argument("apk", help="APK 文件路径")
    ap.add_argument("notes_file", nargs="?", help="更新说明文本文件（可选）")
    ap.add_argument("--notes", default="", help="更新说明文字（与说明文件二选一）")
    ap.add_argument("--min-version-code", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--out", default="version.json")
    ap.add_argument("--release-tag", default="")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        sys.exit("找不到 APK：%s" % args.apk)

    notes = args.notes
    if args.notes_file:
        with open(args.notes_file, "r", encoding="utf-8") as f:
            notes = f.read()
    notes = notes.strip()

    tag = args.release_tag or ("v" + args.version_name)
    asset = ASSET_NAME.format(ver=args.version_name)

    data = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "minVersionCode": args.min_version_code,
        "force": bool(args.force),
        "url": "https://gitee.com/%s/releases/download/%s/%s" % (GITEE_REPO, tag, asset),
        "urlBackup": "https://github.com/%s/releases/download/%s/%s" % (GITHUB_REPO, tag, asset),
        "size": os.path.getsize(args.apk),
        "sha256": sha256_of(args.apk),
        "notes": notes,
        "publishedAt": __import__("datetime").date.today().isoformat(),
    }

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("已写入 %s" % os.path.abspath(args.out))
    print("  versionCode = %d / versionName = %s" % (args.version_code, args.version_name))
    print("  size        = %d" % data["size"])
    print("  sha256      = %s" % data["sha256"])
    print("  发布附件名必须是：%s（两个仓库一致）" % asset)


if __name__ == "__main__":
    main()
