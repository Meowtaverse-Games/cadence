"""Synthetic-only tests for the checks that gate TestFlight uploads."""

import contextlib
import importlib.util
import io
import plistlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


numbers = load_script("testflight-build-number")
verification = load_script("verify-release-ipa")


class BuildNumberTests(unittest.TestCase):
    def test_first_upload_and_increment(self):
        for latest, expected in [("", 1), ("0\n", 1), ("41\n", 42)]:
            with self.subTest(latest=latest), patch.object(numbers.subprocess, "run") as run:
                run.return_value = subprocess.CompletedProcess([], 0, latest, "synthetic private output")
                self.assertEqual(numbers.next_build_number("0000000000"), expected)

    def test_api_failure_does_not_fall_back_to_one_or_expose_output(self):
        with patch.object(numbers.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "synthetic private output")
            with self.assertRaises(ValueError) as failure:
                numbers.next_build_number("0000000000")
            self.assertNotIn("synthetic private output", str(failure.exception))

    def test_invalid_app_id_never_calls_api(self):
        for app_id in ("", " ", "invalid"):
            with self.subTest(app_id=app_id), patch.object(numbers.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "手順11"):
                    numbers.next_build_number(app_id)
                run.assert_not_called()

    def test_malformed_build_number_is_rejected_without_echoing_it(self):
        for output in ("1.2", "-1", "synthetic private output"):
            with self.subTest(output=output), patch.object(numbers.subprocess, "run") as run:
                run.return_value = subprocess.CompletedProcess([], 0, output, "")
                with self.assertRaises(ValueError) as failure:
                    numbers.next_build_number("0000000000")
                self.assertNotIn(output, str(failure.exception))


class IpaVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.temporary.cleanup)
        self.payload = Path(self.temporary.name) / "Payload"
        self.app = self.payload / "Cadence.app"
        self.app.mkdir(parents=True)
        (self.app / "GoogleService-Info.plist").write_bytes(b"synthetic configuration")
        for relative_path, bundle_id in verification.BUNDLES.items():
            bundle = self.payload / relative_path
            bundle.mkdir(parents=True, exist_ok=True)
            (bundle / "Info.plist").write_bytes(plistlib.dumps({
                "CFBundleIdentifier": bundle_id, "CFBundleVersion": "42",
                "CFBundleShortVersionString": "0.1.0", "ITSAppUsesNonExemptEncryption": False,
            }))
            (bundle / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps({}))
        self.entitlements = {
            verification.FAMILY_CONTROLS: True,
            "application-identifier": "SYNTHETIC.com.meowtaverse-games.cadence",
            "get-task-allow": False,
        }
        self.profile = {"Entitlements": dict(self.entitlements, **{"beta-reports-active": True})}

    def verify_bundle(self):
        with patch.object(verification, "command_plist", side_effect=[self.entitlements, self.profile]):
            with contextlib.redirect_stdout(io.StringIO()):
                verification.verify_bundle(self.app, verification.BUNDLES["Cadence.app"], "42")

    def test_valid_app_store_bundle(self):
        self.verify_bundle()

    def test_rejects_development_ad_hoc_and_enterprise_profiles(self):
        for changes in ({"ProvisionedDevices": ["synthetic-device"]}, {"ProvisionsAllDevices": True}):
            with self.subTest(changes=changes), patch.dict(self.profile, changes):
                with self.assertRaises(ValueError):
                    self.verify_bundle()
        for changes in ({"get-task-allow": True}, {"beta-reports-active": False}):
            with self.subTest(changes=changes), patch.dict(self.profile["Entitlements"], changes):
                with self.assertRaises(ValueError):
                    self.verify_bundle()

    def test_family_controls_required_in_both_signature_and_profile(self):
        for entitlements in (self.entitlements, self.profile["Entitlements"]):
            with patch.dict(entitlements, {verification.FAMILY_CONTROLS: False}):
                with self.assertRaisesRegex(ValueError, "Family Controls"):
                    self.verify_bundle()

    def test_mismatched_profile_rejected(self):
        with patch.dict(self.profile["Entitlements"], {"application-identifier": "SYNTHETIC.invalid"}):
            with self.assertRaises(ValueError):
                self.verify_bundle()

    def test_inconsistent_extension_build_number_rejected(self):
        extension = self.payload / "Cadence.app/PlugIns/DeviceActivityMonitor.appex"
        info = plistlib.loads((extension / "Info.plist").read_bytes())
        info["CFBundleVersion"] = "41"
        (extension / "Info.plist").write_bytes(plistlib.dumps(info))
        with patch.object(verification, "command_plist") as command:
            with self.assertRaisesRegex(ValueError, "CFBundleVersion"):
                verification.verify_bundle(extension, verification.BUNDLES[extension.relative_to(self.payload).as_posix()], "42")
            command.assert_not_called()

    def make_archive(self, omit=None):
        archive = Path(self.temporary.name) / "synthetic.ipa"
        with ZipFile(archive, "w") as ipa:
            for path in self.payload.rglob("*"):
                if path.is_file() and (not omit or omit not in str(path)):
                    ipa.write(path, path.relative_to(self.payload.parent))
        return archive

    def test_archive_checks_all_three_bundles(self):
        archive = self.make_archive()
        with patch.object(verification, "verify_bundle") as verify:
            verification.verify_archive(archive, "42")
            self.assertEqual([call.args[1] for call in verify.call_args_list], list(verification.BUNDLES.values()))

    def test_missing_extension_and_firebase_configuration_rejected(self):
        for omitted in ("DeviceActivityMonitor.appex", "GoogleService-Info.plist"):
            with self.subTest(omitted=omitted), patch.object(verification, "verify_bundle"):
                with self.assertRaises(ValueError):
                    verification.verify_archive(self.make_archive(omit=omitted), "42")

    def test_failed_platform_command_does_not_expose_output(self):
        with patch.object(verification.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, b"synthetic private output", b"synthetic private output")
            with self.assertRaises(ValueError) as failure:
                verification.command_plist(["synthetic-tool"])
            self.assertNotIn("synthetic private output", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
