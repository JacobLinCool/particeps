#!/usr/bin/env python3
"""Fail closed when an Android collector crosses its constrained capability boundary."""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_POLICY = ROOT / "assurance/collector-policy.json"
IMPORT = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)", re.MULTILINE)
PROJECT_DEPENDENCY = re.compile(r"\b(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*project\(\s*\"([^\"]+)\"")
CATALOG_DEPENDENCY = re.compile(r"\b(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*(libs\.[A-Za-z0-9_.]+)\s*\)")
TEST_CATALOG_DEPENDENCY = re.compile(
    r"\btestImplementation\s*\(\s*(libs\.[A-Za-z0-9_.]+)\s*\)"
)
STRING_DEPENDENCY = re.compile(
    r"\b(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*\"([^\"]+)\"\s*\)"
)
DEPENDENCY_DECLARATION = re.compile(
    r"\b(?:[A-Za-z][A-Za-z0-9]*(?:Implementation|Api|CompileOnly|RuntimeOnly)|"
    r"api|implementation|compileOnly|runtimeOnly)\s*\("
)
DESCRIPTOR_CLASS = re.compile(r"L([A-Za-z0-9_$/]+);")
ALLOWED_TEST_CATALOG_DEPENDENCIES = frozenset({"libs.junit4"})


class AssuranceError(ValueError):
    """An assurance input is malformed or violates policy."""


@dataclass(frozen=True, order=True)
class MemberReference:
    owner: str
    name: str


@dataclass(frozen=True)
class ClassReferences:
    classes: frozenset[str]
    methods: frozenset[MemberReference]


def load_policy(path: Path) -> dict[str, Any]:
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AssuranceError(f"cannot load policy: {error}") from error
    expected = {
        "allowed_catalog_dependencies",
        "allowed_project_dependencies",
        "forbidden_class_prefixes",
        "forbidden_classes",
        "forbidden_import_prefixes",
        "forbidden_method_names",
        "forbidden_methods",
    }
    if not isinstance(policy, dict) or set(policy) != expected:
        raise AssuranceError("collector policy has unknown or missing members")
    return policy


def parse_class(data: bytes) -> ClassReferences:
    """Read only the JVM constant pool; executable bytecode is intentionally unnecessary."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise AssuranceError("not a JVM class file")
    count = struct.unpack_from(">H", data, 8)[0]
    pool: list[tuple[int, Any] | None] = [None] * count
    offset = 10
    index = 1
    while index < count:
        if offset >= len(data):
            raise AssuranceError("truncated constant pool")
        tag = data[offset]
        offset += 1
        if tag == 1:
            if offset + 2 > len(data):
                raise AssuranceError("truncated UTF-8 constant length")
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            if offset + length > len(data):
                raise AssuranceError("truncated UTF-8 constant")
            # JVM class files use modified UTF-8. Names relevant to this policy are ASCII;
            # replacement decoding safely ignores modified encodings in unrelated literals.
            value = data[offset : offset + length].decode("utf-8", errors="replace")
            offset += length
        elif tag in {3, 4}:
            value = data[offset : offset + 4]
            offset += 4
        elif tag in {5, 6}:
            value = data[offset : offset + 8]
            offset += 8
            pool[index] = (tag, value)
            index += 2
            continue
        elif tag in {7, 8, 16, 19, 20}:
            if offset + 2 > len(data):
                raise AssuranceError("truncated constant-pool index")
            value = struct.unpack_from(">H", data, offset)[0]
            offset += 2
        elif tag in {9, 10, 11, 12, 17, 18}:
            if offset + 4 > len(data):
                raise AssuranceError("truncated constant-pool pair")
            value = struct.unpack_from(">HH", data, offset)
            offset += 4
        elif tag == 15:
            if offset + 3 > len(data):
                raise AssuranceError("truncated method handle")
            value = (data[offset], struct.unpack_from(">H", data, offset + 1)[0])
            offset += 3
        else:
            raise AssuranceError(f"unknown constant-pool tag {tag}")
        if offset > len(data):
            raise AssuranceError("truncated constant pool")
        pool[index] = (tag, value)
        index += 1

    def entry(at: int, tag: int) -> Any:
        if at <= 0 or at >= len(pool) or pool[at] is None or pool[at][0] != tag:
            raise AssuranceError("invalid constant-pool reference")
        return pool[at][1]

    def utf8(at: int) -> str:
        return entry(at, 1)

    def class_name(at: int) -> str:
        return utf8(entry(at, 7))

    classes: set[str] = set()
    methods: set[MemberReference] = set()
    for constant in pool[1:]:
        if constant is None:
            continue
        tag, value = constant
        if tag == 7:
            classes.add(utf8(value))
        elif tag == 1:
            classes.update(DESCRIPTOR_CLASS.findall(value))
        elif tag in {10, 11}:
            class_index, name_and_type_index = value
            name_index, _descriptor_index = entry(name_and_type_index, 12)
            methods.add(MemberReference(class_name(class_index), utf8(name_index)))
    return ClassReferences(frozenset(classes), frozenset(methods))


def violations(references: ClassReferences, policy: dict[str, Any]) -> list[str]:
    class_prefixes = tuple(policy["forbidden_class_prefixes"])
    exact_classes = set(policy["forbidden_classes"])
    forbidden_methods = {
        MemberReference(item["owner"], item["name"]) for item in policy["forbidden_methods"]
    }
    forbidden_method_names = set(policy["forbidden_method_names"])
    result: list[str] = []
    for name in sorted(references.classes):
        normalized = name.lstrip("[").removeprefix("L").removesuffix(";")
        if normalized in exact_classes or normalized.startswith(class_prefixes):
            result.append(f"forbidden class reference {normalized}")
    for member in sorted(references.methods & forbidden_methods):
        result.append(f"forbidden method reference {member.owner}.{member.name}")
    for member in sorted(
        reference
        for reference in references.methods - forbidden_methods
        if reference.name in forbidden_method_names
    ):
        result.append(f"forbidden method reference {member.owner}.{member.name}")
    return result


def _production_class_files(module: Path) -> Iterable[Path]:
    build = module / "build"
    if not build.is_dir():
        return []
    return (
        path
        for path in build.rglob("*.class")
        if not any("test" in part.lower() for part in path.relative_to(build).parts)
        and (
            "kotlin-classes" in path.parts
            or "built_in_kotlinc" in path.parts
            or "javac" in path.parts
        )
    )


def _source_violations(module: Path, policy: dict[str, Any]) -> list[str]:
    forbidden = tuple(policy["forbidden_import_prefixes"])
    result: list[str] = []
    for source in sorted((module / "src/main").rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        for imported in IMPORT.findall(text):
            if imported.startswith(forbidden):
                result.append(f"{source}: forbidden import {imported}")
    build_file = module / "build.gradle.kts"
    if build_file.is_file():
        text = build_file.read_text(encoding="utf-8")
        allowed_projects = set(policy["allowed_project_dependencies"])
        allowed_catalogs = set(policy["allowed_catalog_dependencies"])
        recognized_starts: set[int] = set()
        for match in PROJECT_DEPENDENCY.finditer(text):
            recognized_starts.add(match.start())
            dependency = match.group(1)
            if dependency not in allowed_projects:
                result.append(f"{build_file}: forbidden project dependency {dependency}")
        for match in CATALOG_DEPENDENCY.finditer(text):
            recognized_starts.add(match.start())
            dependency = match.group(1)
            if dependency not in allowed_catalogs:
                result.append(f"{build_file}: forbidden catalog dependency {dependency}")
        for match in TEST_CATALOG_DEPENDENCY.finditer(text):
            recognized_starts.add(match.start())
            dependency = match.group(1)
            if dependency not in ALLOWED_TEST_CATALOG_DEPENDENCIES:
                result.append(f"{build_file}: forbidden test dependency {dependency}")
        for match in STRING_DEPENDENCY.finditer(text):
            recognized_starts.add(match.start())
            dependency = match.group(1)
            result.append(f"{build_file}: forbidden external dependency {dependency}")
        for declaration in DEPENDENCY_DECLARATION.finditer(text):
            if declaration.start() not in recognized_starts:
                result.append(
                    f"{build_file}: unrecognized dependency declaration "
                    f"{declaration.group(0).removesuffix('(').strip()}"
                )
    return result


def scan(root: Path, policy: dict[str, Any], require_classes: bool = True) -> tuple[int, list[str]]:
    collector_root = root / "collector"
    modules = sorted(path.parent for path in collector_root.glob("*/build.gradle.kts"))
    if not modules:
        raise AssuranceError(f"no collector modules found below {collector_root}")
    errors: list[str] = []
    class_count = 0
    for module in modules:
        errors.extend(_source_violations(module, policy))
        for class_file in sorted(_production_class_files(module)):
            class_count += 1
            try:
                found = violations(parse_class(class_file.read_bytes()), policy)
            except (OSError, AssuranceError) as error:
                errors.append(f"{class_file}: {error}")
                continue
            errors.extend(f"{class_file}: {item}" for item in found)
    if require_classes and class_count == 0:
        errors.append("no production collector class files found; compile collectors before assurance")
    return class_count, errors


def scan_explicit(paths: Iterable[Path], policy: dict[str, Any]) -> tuple[int, list[str]]:
    count = 0
    errors: list[str] = []
    for path in paths:
        if path.suffix == ".jar":
            with zipfile.ZipFile(path) as archive:
                for name in sorted(item for item in archive.namelist() if item.endswith(".class")):
                    count += 1
                    errors.extend(
                        f"{path}!{name}: {item}"
                        for item in violations(parse_class(archive.read(name)), policy)
                    )
        else:
            count += 1
            errors.extend(f"{path}: {item}" for item in violations(parse_class(path.read_bytes()), policy))
    return count, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="optional class or jar files")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--allow-no-classes", action="store_true")
    args = parser.parse_args(argv)
    try:
        policy = load_policy(args.policy)
        if args.paths:
            count, errors = scan_explicit(args.paths, policy)
        else:
            count, errors = scan(args.root, policy, not args.allow_no_classes)
    except (AssuranceError, OSError, zipfile.BadZipFile) as error:
        print(f"collector assurance error: {error}", file=sys.stderr)
        return 1
    if errors:
        print("collector assurance violations:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"collector assurance passed: {count} production class files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
