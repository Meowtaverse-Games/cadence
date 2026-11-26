#!/usr/bin/env python3
"""Fail before publishing unless all three bundles have App Store signing."""

import os
import plistlib
import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile


BUNDLES = {
    "Cadence.app": "com.meowtaverse-games.cadence",
    "Cadence.app/PlugIns/DeviceActivityMonitor.appex": "com.meowtaverse-games.cadence.DeviceActivityMonitor",
    "Cadence.app/PlugIns/ShieldConfiguration.appex": "com.meowtaverse-games.cadence.ShieldConfiguration",
}
FAMILY_CONTROLS = "com.apple.developer.family-controls"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command_plist(arguments):
    result = subprocess.run(arguments, capture_output=True)
    require(result.returncode == 0, "署名情報の読み取りに失敗しました。")
    # codesign/security stdout and stderr may contain account data. Never echo them.
    return plistlib.loads(result.stdout)


def verify_bundle(bundle, bundle_id, expected_build):
    info = plistlib.loads((bundle / "Info.plist").read_bytes())
    require(info.get("CFBundleIdentifier") == bundle_id, "bundle ID が一致しません。")
    require(info.get("CFBundleVersion") == expected_build, "CFBundleVersion が指定したビルド番号と一致しません。")
    require(info.get("ITSAppUsesNonExemptEncryption") is False, "暗号の申告がありません。")
    require((bundle / "PrivacyInfo.xcprivacy").is_file(), "プライバシーマニフェストがありません。")

    entitlements = command_plist(["codesign", "-d", "--entitlements", ":-", str(bundle)])
    profile = command_plist(["security", "cms", "-D", "-i", str(bundle / "embedded.mobileprovision")])
    profile_entitlements = profile.get("Entitlements", {})
    require(entitlements.get(FAMILY_CONTROLS) is True, "署名に Family Controls の entitlement がありません。")
    require(profile_entitlements.get(FAMILY_CONTROLS) is True, "profile に Family Controls の entitlement がありません。")
    # Development/ad hoc profiles list devices; enterprise profiles allow all devices.
    require("ProvisionedDevices" not in profile, "端末指定の profile は App Store 用ではありません。")
    require(not profile.get("ProvisionsAllDevices", False), "Enterprise profile は使用できません。")
    require(profile_entitlements.get("get-task-allow") is False, "デバッグ用 profile は使用できません。")
    require(profile_entitlements.get("beta-reports-active") is True, "App Store 用 profile ではありません。")
    application_id = profile_entitlements.get("application-identifier", "")
    require(application_id.endswith("." + bundle_id), "profile の bundle ID が一致しません。")
    require(entitlements.get("application-identifier") == application_id, "署名と profile の識別子が一致しません。")
    require(entitlements.get("get-task-allow") is False, "署名がデバッグ用です。")
    marketing_version = info.get("CFBundleShortVersionString", "")
    require(re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", marketing_version), "マーケティング版が不正です。")
    print(f"{bundle_id} version={marketing_version} CFBundleVersion={expected_build} familyControls=true appStoreProfile=true")


def verify_archive(archive, expected_build):
    with TemporaryDirectory(prefix="cadence-ipa-") as directory:
        with ZipFile(archive) as ipa:
            ipa.extractall(directory)
        payload = Path(directory) / "Payload"
        require(
            {path.relative_to(payload).as_posix() for path in payload.glob("*.app")} == {"Cadence.app"},
            "IPA のアプリ構成が一致しません。",
        )
        require(
            {path.relative_to(payload).as_posix() for path in payload.glob("*.app/PlugIns/*.appex")}
            == set(BUNDLES) - {"Cadence.app"},
            "IPA の extension 構成が一致しません。",
        )
        require((payload / "Cadence.app/GoogleService-Info.plist").is_file(), "IPA に Firebase の設定がありません。")
        for relative_path, bundle_id in BUNDLES.items():
            verify_bundle(payload / relative_path, bundle_id, expected_build)


def main():
    try:
        expected_build = os.environ.get("CADENCE_BUILD_NUMBER", "")
        require(re.fullmatch(r"[1-9][0-9]*", expected_build), "検査するビルド番号がありません。")
        archives = list(Path("build/ios/ipa").glob("*.ipa"))
        require(len(archives) == 1, "検査対象の IPA が1つではありません。")
        verify_archive(archives[0], expected_build)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        # Malformed plists, missing files and tool failures must not leak their data.
        raise SystemExit("IPA の署名・profile・設定の検査に失敗しました。詳細は非公開で確認してください。") from None


if __name__ == "__main__":
    main()
