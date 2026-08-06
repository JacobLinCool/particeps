#!/usr/bin/env python3
"""Prove the catalog's implemented Android/Web projection matches checked-in code."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

if __package__:
    from tools import catalog as catalog_tool
else:
    import catalog as catalog_tool


ROOT = Path(__file__).resolve().parents[1]
KOTLIN_MODEL = ROOT / "core/study-definition/src/main/kotlin/cool/linc/particeps/core/definition/StudyConfiguration.kt"
KOTLIN_CODEC = ROOT / "core/study-definition/src/main/kotlin/cool/linc/particeps/core/definition/StudyConfigurationCodec.kt"
WEB_TYPES = ROOT / "web/src/lib/particeps/types.ts"
WEB_PARSE = ROOT / "web/src/routes/researcher/parse.ts"
RUNTIME = ROOT / "core/experiment-runtime/src/main/kotlin/cool/linc/particeps/core/runtime/ExperimentRuntime.kt"
KOTLIN_EVENT_CONTRACT = (
    ROOT
    / "core/collector-api/src/main/kotlin/cool/linc/particeps/core/collector/ProtocolEventContracts.kt"
)


class ParityError(ValueError):
    """A platform copy has drifted from the language-neutral catalog."""


def _balanced(text: str, start: int, opening: str, closing: str) -> str:
    if start < 0 or text[start] != opening:
        raise ParityError(f"cannot locate balanced {opening}{closing} block")
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(start, len(text)):
        character = text[index]
        if quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {'"', "'"}:
            quote = character
        elif character == opening:
            depth += 1
        elif character == closing:
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    raise ParityError(f"unterminated {opening}{closing} block")


def _number(text: str) -> int:
    return int(text.replace("_", ""))


def _snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def _implemented(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        collector
        for collector in catalog["collectors"]
        if collector["implementation"]["status"] == "implemented"
        and collector["selectable"]
        and "android" in collector["platforms"]
    ]


def _configuration_classes(model: str) -> dict[str, dict[str, Any]]:
    starts = list(re.finditer(r"^data class (\w+Configuration)\s*\(", model, re.MULTILINE))
    result: dict[str, dict[str, Any]] = {}
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(model)
        section = model[match.start() : end]
        identifier = re.search(r'const val ID = "([a-z][a-z0-9_.-]+\.v1)"', section)
        if not identifier:
            continue
        parameters = _balanced(model, match.end() - 1, "(", ")")
        fields = {
            _snake(name): kind.strip()
            for name, kind in re.findall(
                r"(?:override\s+)?val\s+(\w+)\s*:\s*([^,\n=]+)", parameters
            )
            if name != "required"
        }
        result[identifier.group(1)] = {
            "class": match.group(1),
            "fields": fields,
            "section": section,
        }
    return result


def _codec_fields(codec: str, class_name: str) -> set[str]:
    marker = re.search(rf"{re.escape(class_name)}\.ID\s*->\s*\{{", codec)
    if not marker:
        raise ParityError(f"Kotlin codec has no {class_name} closed-world branch")
    block = _balanced(codec, marker.end() - 1, "{", "}")
    marker = block.find("config.requireExactKeys(")
    if marker < 0:
        raise ParityError(f"Kotlin codec {class_name} branch has no exact config key set")
    arguments = _balanced(block, marker + len("config.requireExactKeys"), "(", ")")
    if "emptySet()" not in arguments and "setOf(" not in arguments:
        raise ParityError(f"Kotlin codec {class_name} key set is not statically auditable")
    return set(re.findall(r'"([a-z][a-z0-9_]*)"', arguments))


def _enum_values(model: str, name: str) -> set[str]:
    match = re.search(rf"enum class {re.escape(name)}\s*\{{([^}}]+)\}}", model)
    if not match:
        raise ParityError(f"Kotlin enum {name} is absent")
    return set(re.findall(r"\b[A-Z][A-Z0-9_]*\b", match.group(1)))


def _check_kotlin_configuration(
    collectors: list[dict[str, Any]], model: str, codec: str
) -> None:
    classes = _configuration_classes(model)
    expected_ids = {collector["id"] for collector in collectors}
    if set(classes) != expected_ids:
        raise ParityError(
            f"Kotlin CollectorConfiguration IDs differ: catalog={sorted(expected_ids)}, "
            f"Kotlin={sorted(classes)}"
        )
    codec_classes = set(re.findall(r"(\w+Configuration)\.ID\s*->", codec))
    expected_classes = {value["class"] for value in classes.values()}
    if codec_classes != expected_classes:
        raise ParityError("Kotlin codec collector branches differ from CollectorConfiguration classes")
    by_id = {collector["id"]: collector for collector in collectors}
    for identifier, implementation in classes.items():
        configuration = by_id[identifier]["configuration"]
        fields = configuration["fields"]
        if set(implementation["fields"]) != set(fields):
            raise ParityError(f"{identifier} Kotlin constructor fields differ from catalog")
        if set(configuration["required"]) != set(fields):
            raise ParityError(f"{identifier} catalog must mark every Kotlin constructor field required")
        if _codec_fields(codec, implementation["class"]) != set(fields):
            raise ParityError(f"{identifier} Kotlin codec key set differs from catalog")
        section = implementation["section"]
        for name, field in fields.items():
            kotlin_type = implementation["fields"][name]
            property_name = re.sub(r"_([a-z])", lambda match: match.group(1).upper(), name)
            if field["type"] == "integer":
                if kotlin_type not in {"Int", "Long"}:
                    raise ParityError(f"{identifier}.{name} is not an integer in Kotlin")
                bound = re.search(
                    rf"require\(\s*{re.escape(property_name)}\s+in\s+([0-9_]+)\.\.([A-Za-z0-9_]+)",
                    section,
                )
                if not bound or _number(bound.group(1)) != field["minimum"]:
                    raise ParityError(f"{identifier}.{name} Kotlin minimum differs from catalog")
                maximum = bound.group(2)
                if maximum[0].isdigit():
                    resolved_maximum = _number(maximum)
                    if "maximum_field" in field:
                        raise ParityError(f"{identifier}.{name} lacks its catalog cross-field bound")
                else:
                    referenced = _snake(maximum)
                    if referenced not in fields:
                        raise ParityError(f"{identifier}.{name} has an unmodeled Kotlin cross-field bound")
                    if field.get("maximum_field") != referenced:
                        raise ParityError(f"{identifier}.{name} Kotlin cross-field bound differs from catalog")
                    resolved_maximum = fields[referenced]["maximum"]
                if resolved_maximum != field["maximum"]:
                    raise ParityError(f"{identifier}.{name} Kotlin maximum differs from catalog")
            elif field["type"] == "boolean":
                if kotlin_type != "Boolean":
                    raise ParityError(f"{identifier}.{name} is not Boolean in Kotlin")
            elif field["type"] == "enum":
                if _enum_values(model, kotlin_type) != set(field["enum"]):
                    raise ParityError(f"{identifier}.{name} Kotlin enum differs from catalog")
            elif field["type"] == "enum_array":
                element = re.fullmatch(r"Set<(\w+)>", kotlin_type)
                if not element or {item.lower() for item in _enum_values(model, element.group(1))} != set(field["items_enum"]):
                    raise ParityError(f"{identifier}.{name} Kotlin enum set differs from catalog")


def _descriptor_block(source: str) -> str:
    marker = source.find("CollectorDescriptor(")
    if marker < 0 or source.find("CollectorDescriptor(", marker + 1) >= 0:
        raise ParityError("collector module must declare exactly one CollectorDescriptor")
    return _balanced(source, marker + len("CollectorDescriptor"), "(", ")")


def _check_kotlin_descriptors(
    collectors: list[dict[str, Any]], root: Path, model: str
) -> None:
    classes = _configuration_classes(model)
    for collector in collectors:
        module = root.joinpath(*collector["implementation"]["android_module"].lstrip(":").split(":"))
        source = "\n".join(
            path.read_text(encoding="utf-8") for path in sorted((module / "src/main").rglob("*.kt"))
        )
        identifiers = set(re.findall(r'"([a-z][a-z0-9_.-]+\.v1)"', source))
        class_name = classes[collector["id"]]["class"]
        if identifiers - {collector["id"]} or (
            collector["id"] not in identifiers and f"{class_name}.ID" not in source
        ):
            raise ParityError(f"{module} wire IDs differ from catalog: {sorted(identifiers)}")
        block = _descriptor_block(source)
        event_contract = re.search(
            r"eventContract\s*=\s*requireNotNull\(ProtocolEventContracts\[([^\]]+)]\)",
            block,
        )
        privacy = re.search(r"privacyClass\s*=\s*PrivacyClass\.(\w+)", block)
        if not event_contract or not privacy:
            raise ParityError(f"{module} descriptor is not statically auditable")
        contract_id = event_contract.group(1).strip()
        if contract_id == f"{class_name}.ID":
            pass
        elif re.fullmatch(r"[A-Z][A-Z0-9_]*", contract_id):
            definition = re.search(
                rf"const val {re.escape(contract_id)}\s*=\s*\"([^\"]+)\"",
                source,
            )
            if not definition or definition.group(1) != collector["id"]:
                raise ParityError(f"{collector['id']} descriptor contract ID differs")
        elif contract_id != f'"{collector["id"]}"':
            raise ParityError(f"{collector['id']} descriptor contract ID differs")
        if "payloadSchemaVersion" in block or "maximumEncodedEventBytes" in block:
            raise ParityError(f"{collector['id']} descriptor duplicates generated contract metadata")
        if privacy.group(1) != collector["privacy_class"]:
            raise ParityError(f"{collector['id']} privacy class differs")
        expected_access = {
            item["kind"] for item in collector["access"] if item["mode"] != "install_permission"
        }
        actual_access = set(re.findall(r"AccessKind\.([A-Z][A-Z0-9_]*)", source))
        if actual_access != expected_access:
            raise ParityError(f"{collector['id']} runtime access requirements differ")
        manifest = (module / "src/main/AndroidManifest.xml")
        manifest_text = manifest.read_text(encoding="utf-8") if manifest.is_file() else ""
        for access in collector["access"]:
            if access["mode"] == "install_permission" and f"android.permission.{access['kind']}" not in manifest_text:
                raise ParityError(f"{collector['id']} install permission {access['kind']} is absent")


def _quoted(block: str, suffix: str = "") -> set[str]:
    return set(re.findall(rf"'([a-z][a-z0-9_.-]+{re.escape(suffix)})'", block))


def _web_case_fields(parse_source: str, identifier: str) -> set[str]:
    switch_start = parse_source.index("switch (source.id)")
    switch = _balanced(parse_source, parse_source.index("{", switch_start), "{", "}")
    marker = re.search(rf"case '{re.escape(identifier)}':(?:\s*\{{)?", switch)
    if not marker:
        raise ParityError(f"Web structural parser has no {identifier} branch")
    tail = switch[marker.end() :]
    next_case = re.search(r"\n\s*case '[^']+':", tail)
    block = tail[: next_case.start()] if next_case else tail
    exact = re.search(r"requireExactKeys\(config,\s*\[(.*?)\]\)", block, re.DOTALL)
    if not exact:
        raise ParityError(f"Web structural parser {identifier} branch lacks exact keys")
    return set(re.findall(r"'([a-z][a-z0-9_]*)'", exact.group(1)))


def _check_web(collectors: list[dict[str, Any]], types_source: str, parse_source: str) -> None:
    expected_ids = {collector["id"] for collector in collectors}
    union = types_source[types_source.index("export type CollectorId") : types_source.index("export const COLLECTOR_ORDER")]
    order_match = re.search(r"export const COLLECTOR_ORDER[^=]*=\s*\[(.*?)\]", types_source, re.DOTALL)
    if not order_match:
        raise ParityError("Web COLLECTOR_ORDER is absent")
    if _quoted(union, ".v1") != expected_ids or _quoted(order_match.group(1), ".v1") != expected_ids:
        raise ParityError("Web CollectorId/COLLECTOR_ORDER differs from implemented catalog IDs")
    bounds_block = re.search(r"export const BOUNDS = \{(.*?)\}\s+as const", types_source, re.DOTALL)
    if not bounds_block:
        raise ParityError("Web BOUNDS metadata is absent")
    bounds = {
        name: (_number(low), _number(high))
        for name, low, high in re.findall(
            r"^\s*(\w+):\s*\[([0-9_]+),\s*([0-9_]+)\]", bounds_block.group(1), re.MULTILINE
        )
    }
    for collector in collectors:
        fields = collector["configuration"]["fields"]
        if _web_case_fields(parse_source, collector["id"]) != set(fields):
            raise ParityError(f"{collector['id']} Web structural key set differs from catalog")
        for name, field in fields.items():
            if field["type"] != "integer":
                continue
            camel = re.sub(r"_([a-z])", lambda match: match.group(1).upper(), name)
            expected = (field["minimum"], field["maximum"])
            collector_prefix = re.sub(
                r"_([a-z])",
                lambda match: match.group(1).upper(),
                collector["id"].split(".", 1)[0],
            )
            specific = collector_prefix + camel[0].upper() + camel[1:]
            if bounds.get(camel) != expected and bounds.get(specific) != expected:
                raise ParityError(f"{collector['id']}.{name} Web bounds differ from catalog")
        for name, field in fields.items():
            if field["type"] == "enum":
                values = re.search(
                    r"export const LOCATION_PRIORITIES[^=]*=\s*\[(.*?)\]",
                    types_source,
                    re.DOTALL,
                )
                if not values or set(field["enum"]) != set(re.findall(r"'([A-Z][A-Z_]*)'", values.group(1))):
                    raise ParityError(f"{collector['id']}.{name} Web enum differs from catalog")
            if field["type"] == "enum_array":
                values = re.search(
                    r"export const NETWORK_TRANSPORTS[^=]*=\s*\[(.*?)\]",
                    types_source,
                    re.DOTALL,
                )
                if not values or set(field["items_enum"]) != set(re.findall(r"'([a-z]+)'", values.group(1))):
                    raise ParityError(f"{collector['id']}.{name} Web enum array differs from catalog")


def _check_interventions(catalog: dict[str, Any], root: Path) -> None:
    source = RUNTIME.read_text(encoding="utf-8")
    if "descriptor.eventContract.accepts(event, Long.MAX_VALUE)" not in source:
        raise ParityError("collector runtime does not enforce the generated typed event contract")
    intervention_contract = re.search(
        r"ProtocolEventContracts\[([A-Z][A-Z0-9_]*)]\)\.accepts\(\s*"
        r"draft,\s*metadataAfterState\.nextSequenceNumber,?\s*\)",
        source,
    )
    if not intervention_contract:
        raise ParityError("intervention runtime does not consume its generated event contract")
    identifier = intervention_contract.group(1)
    definition = re.search(
        rf'const val {re.escape(identifier)}\s*=\s*"([^"]+)"',
        source,
    )
    if not definition or definition.group(1) != "interventions.v1":
        raise ParityError("intervention runtime uses the wrong generated event contract")
    contract = (root / "core/collector-api/src/main/kotlin/cool/linc/particeps/core/collector/CollectorContracts.kt").read_text(encoding="utf-8")
    if "maximumEncodedEventBytes in 128..65_536" not in contract:
        raise ParityError("CollectorEventContract maximumEncodedEventBytes bounds drifted")


def check(value: dict[str, Any], root: Path = ROOT) -> None:
    catalog_tool.validate(value, root)
    expected_contract = catalog_tool.render_kotlin_contract(value)
    actual_contract = KOTLIN_EVENT_CONTRACT.read_text(encoding="utf-8")
    if actual_contract != expected_contract:
        raise ParityError("generated Kotlin event contract differs from the catalog")
    collectors = _implemented(value)
    model = KOTLIN_MODEL.read_text(encoding="utf-8")
    codec = KOTLIN_CODEC.read_text(encoding="utf-8")
    _check_kotlin_configuration(collectors, model, codec)
    _check_kotlin_descriptors(collectors, root, model)
    _check_web(
        collectors,
        WEB_TYPES.read_text(encoding="utf-8"),
        WEB_PARSE.read_text(encoding="utf-8"),
    )
    _check_interventions(value, root)


def main() -> int:
    try:
        value = catalog_tool.load(catalog_tool.DEFAULT_CATALOG)
        check(value)
    except (OSError, UnicodeError, ParityError, catalog_tool.CatalogError) as error:
        print(f"catalog parity error: {error}", file=sys.stderr)
        return 1
    print("catalog parity passed: Kotlin descriptors/config codec and Web metadata")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
