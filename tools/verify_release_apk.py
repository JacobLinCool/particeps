from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import zipfile
from collections.abc import Mapping
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CERTIFICATE_SHA256_PATH = ROOT / ".github" / "android-release-signing-certificate.sha256"
EXPECTED_SHA256_PATTERN = re.compile(rb"[0-9a-f]{64}\n")
ACTUAL_SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
SIGNER_COUNT_PATTERN = re.compile(r"^Number of signers: ([0-9]+)$", re.MULTILINE)
CERTIFICATE_SHA256_LABEL = "certificate SHA-256 digest: "
EXPECTED_ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
NATIVE_LIBRARY = "libgojni.so"
# Android's 16 KiB runtime targets are arm64; x86_64 provides the supported userspace simulation.
# The two retained 32-bit distribution ABIs run on 4 KiB targets, and NDK r28+ intentionally emits
# their ELF LOAD segments at 4 KiB while emitting the 64-bit counterparts at 16 KiB. Packaging is
# checked independently with zipalign -P 16 for the whole APK.
MINIMUM_ELF_PAGE_ALIGNMENT_BY_ABI = {
    "armeabi-v7a": 4 * 1024,
    "arm64-v8a": 16 * 1024,
    "x86": 4 * 1024,
    "x86_64": 16 * 1024,
}
REQUIRED_PERMISSIONS = (
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "android.permission.QUERY_ALL_PACKAGES",
)
VPN_SERVICE = "cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingVpnService"
REGISTRY_DIGEST_ASSET = "assets/particeps/event-source-registry.sha256"
NATIVE_SBOM_ASSET = "assets/particeps/traffic-shaping-sbom.json"
NATIVE_NOTICES_ASSET = "assets/particeps/THIRD_PARTY_NOTICES.md"
TUN2SOCKS_NOTICE_ASSET = "assets/licenses/tun2socks-MIT.txt"
MODULE_LICENSE_ASSET_PREFIX = "assets/particeps/licenses/"
TRACKED_NATIVE_BINARY_SUFFIXES = frozenset(
    {".a", ".aar", ".dll", ".dylib", ".exe", ".o", ".obj", ".so"},
)
FORBIDDEN_NATIVE_LOG_CALL = re.compile(
    r"\b(?:fmt|log)\.(?:Print|Printf|Println|Fatal|Fatalf|Fatalln|Panic|Panicf|Panicln)\s*\(",
)
FORBIDDEN_ANDROID_LOG_CALL = re.compile(
    r"(?:\bandroid\.util\.Log\b|\bTimber\s*\.|\bSystem\.(?:out|err)\b|"
    r"\bprint(?:ln)?\s*\(|\.printStackTrace\s*\()",
)


class ReleaseApkVerificationError(ValueError):
    pass


def tracked_repository_files(root: Path = ROOT) -> tuple[Path, ...]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise ReleaseApkVerificationError("cannot enumerate the tracked release source tree")
    try:
        decoded = result.stdout.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ReleaseApkVerificationError("tracked source path is not UTF-8") from error
    entries = tuple(Path(value) for value in decoded.split("\0") if value)
    if not entries:
        raise ReleaseApkVerificationError("tracked release source tree is empty")
    return entries


def verify_repository_release_contracts(
    root: Path = ROOT,
    tracked_files: tuple[Path, ...] | None = None,
) -> None:
    tracked = tracked_files if tracked_files is not None else tracked_repository_files(root)
    native_binaries = sorted(
        str(path) for path in tracked if path.suffix.lower() in TRACKED_NATIVE_BINARY_SUFFIXES
    )
    if native_binaries:
        raise ReleaseApkVerificationError(
            f"tracked native binary is forbidden: {native_binaries}",
        )

    engine_path = root / "native/traffic-shaping/engine.go"
    try:
        engine = engine_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise ReleaseApkVerificationError("cannot inspect the native traffic-shaping engine") from error
    logger_call = engine.find("installSilentLogger()")
    network_calls = [
        engine.find("iobased.New("),
        engine.find("tunnel.New("),
        engine.find("core.CreateStack("),
    ]
    if logger_call < 0 or any(index < 0 or logger_call >= index for index in network_calls):
        raise ReleaseApkVerificationError(
            "native forwarding must install the silent upstream logger before network setup",
        )

    native_sources = sorted((root / "native/traffic-shaping").glob("*.go"))
    android_sources = sorted((root / "actuator/traffic-shaping/src/main").rglob("*.kt"))
    if not native_sources or not android_sources:
        raise ReleaseApkVerificationError("traffic-shaping production sources are missing")
    for source in native_sources:
        if source.name.endswith("_test.go"):
            continue
        text = source.read_text(encoding="utf-8")
        if FORBIDDEN_NATIVE_LOG_CALL.search(text):
            raise ReleaseApkVerificationError(
                f"native traffic-shaping production logging is forbidden: {source.relative_to(root)}",
            )
    for source in android_sources:
        text = source.read_text(encoding="utf-8")
        if FORBIDDEN_ANDROID_LOG_CALL.search(text):
            raise ReleaseApkVerificationError(
                f"Android traffic-shaping production logging is forbidden: {source.relative_to(root)}",
            )


def read_expected_certificate_sha256(path: Path = DEFAULT_CERTIFICATE_SHA256_PATH) -> str:
    value = path.read_bytes()
    if EXPECTED_SHA256_PATTERN.fullmatch(value) is None:
        raise ReleaseApkVerificationError(
            f"{path} must contain exactly 64 lowercase hexadecimal characters and one newline",
        )
    return value[:-1].decode("ascii")


def extract_single_signer_certificate_sha256(apksigner_output: str) -> str:
    signer_counts = SIGNER_COUNT_PATTERN.findall(apksigner_output)
    if signer_counts != ["1"]:
        raise ReleaseApkVerificationError(
            f"expected exactly one APK signer, found reports {signer_counts or 'none'}",
        )

    certificate_lines = [
        line
        for line in apksigner_output.splitlines()
        if CERTIFICATE_SHA256_LABEL in line
    ]
    if len(certificate_lines) != 1:
        raise ReleaseApkVerificationError(
            f"expected exactly one signer certificate SHA-256, found {len(certificate_lines)}",
        )
    actual = certificate_lines[0].split(CERTIFICATE_SHA256_LABEL, maxsplit=1)[1]
    if ACTUAL_SHA256_PATTERN.fullmatch(actual) is None:
        raise ReleaseApkVerificationError("apksigner returned a malformed certificate SHA-256")
    return actual.lower()


def require_expected_signer(apksigner_output: str, expected_sha256: str) -> str:
    actual_sha256 = extract_single_signer_certificate_sha256(apksigner_output)
    if actual_sha256 != expected_sha256:
        raise ReleaseApkVerificationError(
            "release APK signer certificate SHA-256 mismatch: "
            f"expected {expected_sha256}, found {actual_sha256}",
        )
    return actual_sha256


def _elf_load_alignments(encoded: bytes, member: str) -> tuple[int, ...]:
    if encoded[:4] != b"\x7fELF" or len(encoded) < 64:
        raise ReleaseApkVerificationError(f"{member} is not a complete ELF image")
    elf_class = encoded[4]
    byte_order = encoded[5]
    if byte_order not in (1, 2):
        raise ReleaseApkVerificationError(f"{member} has an unsupported ELF byte order")
    order = "<" if byte_order == 1 else ">"
    if elf_class == 1:
        phoff = struct.unpack_from(f"{order}I", encoded, 28)[0]
        phentsize = struct.unpack_from(f"{order}H", encoded, 42)[0]
        phnum = struct.unpack_from(f"{order}H", encoded, 44)[0]
        minimum_entry_size = 32
        alignment_offset = 28
    elif elf_class == 2:
        phoff = struct.unpack_from(f"{order}Q", encoded, 32)[0]
        phentsize = struct.unpack_from(f"{order}H", encoded, 54)[0]
        phnum = struct.unpack_from(f"{order}H", encoded, 56)[0]
        minimum_entry_size = 56
        alignment_offset = 48
    else:
        raise ReleaseApkVerificationError(f"{member} has an unsupported ELF class")
    if phnum == 0 or phentsize < minimum_entry_size or phoff + phentsize * phnum > len(encoded):
        raise ReleaseApkVerificationError(f"{member} has an invalid ELF program-header table")
    alignments: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if struct.unpack_from(f"{order}I", encoded, offset)[0] != 1:
            continue
        format_code = "I" if elf_class == 1 else "Q"
        alignments.append(struct.unpack_from(f"{order}{format_code}", encoded, offset + alignment_offset)[0])
    if not alignments:
        raise ReleaseApkVerificationError(f"{member} has no loadable ELF segments")
    return tuple(alignments)


def expected_supply_chain_evidence() -> dict[str, bytes]:
    return {
        REGISTRY_DIGEST_ASSET: (ROOT / "protocol/v1/generated/event-source-registry.sha256").read_bytes(),
        NATIVE_SBOM_ASSET: (ROOT / "native/traffic-shaping/sbom-input.json").read_bytes(),
        NATIVE_NOTICES_ASSET: (ROOT / "native/traffic-shaping/THIRD_PARTY_NOTICES.md").read_bytes(),
        TUN2SOCKS_NOTICE_ASSET: (
            ROOT / "native/traffic-shaping/assets/licenses/tun2socks-MIT.txt"
        ).read_bytes(),
    }


def _module_license_assets(sbom: bytes) -> dict[str, str]:
    try:
        document = json.loads(sbom)
        components = document["components"]
    except (KeyError, TypeError, json.JSONDecodeError) as error:
        raise ReleaseApkVerificationError("native SBOM is malformed") from error
    if document.get("format") != 1 or not isinstance(components, list) or not components:
        raise ReleaseApkVerificationError("native SBOM has an unsupported component contract")
    expected: dict[str, str] = {}
    for component in components:
        try:
            module = component["module"]
            version = component["version"]
            license_file = component["license_file"]
            license_sha256 = component["license_sha256"]
        except (KeyError, TypeError) as error:
            raise ReleaseApkVerificationError("native SBOM has an incomplete license record") from error
        if (
            not isinstance(module, str)
            or not module
            or not isinstance(version, str)
            or not version
            or not isinstance(license_file, str)
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", license_file) is None
            or not isinstance(license_sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", license_sha256) is None
        ):
            raise ReleaseApkVerificationError("native SBOM has a noncanonical license record")
        normalized = re.sub(r"[^A-Za-z0-9._-]", "_", module)
        asset = f"{MODULE_LICENSE_ASSET_PREFIX}{normalized}.txt"
        if asset in expected:
            raise ReleaseApkVerificationError("native SBOM license asset names collide")
        expected[asset] = license_sha256
    return expected


def verify_apk_contents(
    apk: Path,
    expected_evidence: Mapping[str, bytes] | None = None,
) -> None:
    evidence = dict(expected_evidence or expected_supply_chain_evidence())
    expected_licenses = _module_license_assets(evidence[NATIVE_SBOM_ASSET])
    try:
        with zipfile.ZipFile(apk) as archive:
            members = archive.namelist()
            native_members = sorted(
                member for member in members if member.endswith(f"/{NATIVE_LIBRARY}")
            )
            expected_native = sorted(f"lib/{abi}/{NATIVE_LIBRARY}" for abi in EXPECTED_ABIS)
            if native_members != expected_native:
                raise ReleaseApkVerificationError(
                    f"release APK native ABI set mismatch: expected {expected_native}, found {native_members}",
                )
            for member in expected_native:
                abi = member.split("/", maxsplit=2)[1]
                minimum_alignment = MINIMUM_ELF_PAGE_ALIGNMENT_BY_ABI[abi]
                alignments = _elf_load_alignments(archive.read(member), member)
                if any(
                    alignment < minimum_alignment
                    or alignment & (alignment - 1) != 0
                    for alignment in alignments
                ):
                    raise ReleaseApkVerificationError(
                        f"{member} has a LOAD segment below {minimum_alignment // 1024} KiB "
                        f"alignment: {alignments}",
                    )
            for member, expected in evidence.items():
                if member not in members:
                    raise ReleaseApkVerificationError(f"release APK is missing {member}")
                if archive.read(member) != expected:
                    raise ReleaseApkVerificationError(f"release APK carries stale {member}")
            actual_license_assets = {
                member for member in members if member.startswith(MODULE_LICENSE_ASSET_PREFIX)
            }
            if actual_license_assets != expected_licenses.keys():
                raise ReleaseApkVerificationError(
                    "release APK module-license set does not exactly match its SBOM",
                )
            for member, expected_sha256 in expected_licenses.items():
                actual_sha256 = hashlib.sha256(archive.read(member)).hexdigest()
                if actual_sha256 != expected_sha256:
                    raise ReleaseApkVerificationError(
                        f"release APK carries a stale module license: {member}",
                    )
    except zipfile.BadZipFile as error:
        raise ReleaseApkVerificationError("release APK is not a valid ZIP archive") from error


def verify_manifest(aapt2: Path, apk: Path) -> None:
    result = subprocess.run(
        [str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ReleaseApkVerificationError(f"aapt2 manifest inspection exited with {result.returncode}")
    manifest = result.stdout
    for permission in REQUIRED_PERMISSIONS:
        if permission not in manifest:
            raise ReleaseApkVerificationError(f"release manifest is missing {permission}")

    service_blocks = [
        block
        for block in _aapt2_element_blocks(manifest, "service")
        if f'="{VPN_SERVICE}"' in block
    ]
    if len(service_blocks) != 1:
        raise ReleaseApkVerificationError("release manifest must declare exactly one Particeps VPN service")
    service = service_blocks[0]
    required_fragments = (
        "android.permission.BIND_VPN_SERVICE",
        "android.net.VpnService",
        "android.net.VpnService.SUPPORTS_ALWAYS_ON",
    )
    for fragment in required_fragments:
        if fragment not in service:
            raise ReleaseApkVerificationError(f"release VPN manifest is missing {fragment}")
    if re.search(r":exported\([^)]*\)=false(?:\s|$)", service) is None:
        raise ReleaseApkVerificationError("release VPN service must be exported=false")
    foreground_type = re.search(
        r":foregroundServiceType\([^)]*\)=(0x[0-9a-fA-F]+|[0-9]+)(?:\s|$)",
        service,
    )
    if foreground_type is None or int(foreground_type.group(1), 0) != 0x400:
        raise ReleaseApkVerificationError("release VPN service must use systemExempted foreground service type")
    always_on = re.compile(
        rf':name\([^)]*\)="{re.escape("android.net.VpnService.SUPPORTS_ALWAYS_ON")}".*\n'
        r"\s*A: .*:value\([^)]*\)=false(?:\s|$)",
    )
    if always_on.search(service) is None:
        raise ReleaseApkVerificationError("release VPN service must opt out of always-on")


def _aapt2_element_blocks(xmltree: str, element: str) -> tuple[str, ...]:
    header = re.compile(rf"^(?P<indent>\s*)E: {re.escape(element)}(?:\s|$)")
    lines = xmltree.splitlines()
    blocks: list[str] = []
    for index, line in enumerate(lines):
        match = header.match(line)
        if match is None:
            continue
        indentation = len(match.group("indent"))
        end = index + 1
        while end < len(lines):
            candidate = lines[end]
            if candidate.strip() and len(candidate) - len(candidate.lstrip()) <= indentation:
                break
            end += 1
        blocks.append("\n".join(lines[index:end]))
    return tuple(blocks)


def verify_zip_alignment(zipalign: Path, apk: Path) -> None:
    result = subprocess.run(
        [str(zipalign), "-c", "-P", "16", "4", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ReleaseApkVerificationError(f"zipalign 16 KiB verification exited with {result.returncode}")


def verify_release_apk(
    apksigner: Path,
    apk: Path,
    certificate_sha256_path: Path = DEFAULT_CERTIFICATE_SHA256_PATH,
) -> str:
    expected_sha256 = read_expected_certificate_sha256(certificate_sha256_path)
    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    if result.stderr:
        print(result.stderr, end="" if result.stderr.endswith("\n") else "\n", file=sys.stderr)
    if result.returncode != 0:
        raise ReleaseApkVerificationError(f"apksigner verify exited with {result.returncode}")

    actual_sha256 = require_expected_signer(result.stdout, expected_sha256)
    verify_repository_release_contracts()
    verify_apk_contents(apk)
    verify_manifest(apksigner.with_name("aapt2"), apk)
    verify_zip_alignment(apksigner.with_name("zipalign"), apk)
    print(f"Release APK signer certificate SHA-256 matches repository anchor: {actual_sha256}")
    return actual_sha256


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify an APK and require the repository-pinned production signing certificate.",
    )
    parser.add_argument("apksigner", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--certificate-sha256",
        type=Path,
        default=DEFAULT_CERTIFICATE_SHA256_PATH,
    )
    arguments = parser.parse_args()
    try:
        verify_release_apk(
            arguments.apksigner,
            arguments.apk,
            arguments.certificate_sha256,
        )
    except (OSError, ReleaseApkVerificationError) as failure:
        parser.exit(1, f"Release APK verification failed: {failure}\n")


if __name__ == "__main__":
    main()
