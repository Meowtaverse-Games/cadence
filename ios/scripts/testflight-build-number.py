#!/usr/bin/env python3
"""Select a release build number without exposing App Store Connect CLI output."""

import os
import re
import subprocess
from pathlib import Path


def next_build_number(app_id):
    if not re.fullmatch(r"[0-9]+", app_id):
        raise ValueError("docs/apple-setup.md の手順11に従い、APP_STORE_APP_ID を数字の Secret として登録してください。")
    result = subprocess.run(
        ["app-store-connect", "get-latest-testflight-build-number", app_id, "--platform", "IOS"],
        capture_output=True, text=True,
    )
    # An API/authentication failure must never be mistaken for a first upload.
    if result.returncode:
        raise ValueError("TestFlight の最新ビルド番号を取得できませんでした。手順11のアプリ登録と API キーの権限を確認してください。")
    latest = result.stdout.strip()
    # The CLI returns no stdout (success) when there are no builds yet.
    if latest and not re.fullmatch(r"[0-9]+", latest):
        raise ValueError("TestFlight のビルド番号が整数ではありません。非公開で既存の番号を確認してください。")
    return int(latest or "0") + 1


def main():
    try:
        build_number = next_build_number(os.environ.get("APP_STORE_APP_ID", ""))
        with Path(os.environ["CM_ENV"]).open("a") as environment:
            environment.write(f"CADENCE_BUILD_NUMBER={build_number}\n")
        print(f"CFBundleVersion={build_number}")
    except (ValueError, OSError, KeyError) as error:
        # Never print subprocess exceptions: they can include the secret app ID.
        message = str(error) if isinstance(error, ValueError) else "ビルド番号の設定に失敗しました。Codemagic の実行環境を確認してください。"
        raise SystemExit(message) from None


if __name__ == "__main__":
    main()
