from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tools import catalog


class CatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.valid = catalog.load(catalog.DEFAULT_CATALOG)

    def test_repository_catalog_is_valid(self) -> None:
        catalog.validate(self.valid, catalog.ROOT)

    def test_checked_in_kotlin_contract_is_generated_from_catalog(self) -> None:
        catalog.check_kotlin_contract(self.valid)

    def test_stale_kotlin_contract_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ProtocolEventContracts.kt"
            path.write_text("// stale\n", encoding="utf-8")
            with self.assertRaisesRegex(catalog.CatalogError, "stale"):
                catalog.check_kotlin_contract(self.valid, path)

    def test_duplicate_json_member_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text('{"catalog_format":"a","catalog_format":"b"}', encoding="utf-8")
            with self.assertRaisesRegex(catalog.CatalogError, "duplicate"):
                catalog.load(path)

    def test_unknown_member_is_rejected(self) -> None:
        hostile = copy.deepcopy(self.valid)
        hostile["fallback"] = True
        with self.assertRaisesRegex(catalog.CatalogError, "unknown"):
            catalog.validate(hostile)

    def test_unsorted_payload_types_are_rejected(self) -> None:
        hostile = copy.deepcopy(self.valid)
        hostile["collectors"][2]["payloads"][0]["types"].reverse()
        with self.assertRaisesRegex(catalog.CatalogError, "sorted and unique"):
            catalog.validate(hostile)

    def test_configuration_required_must_be_an_array(self) -> None:
        hostile = copy.deepcopy(self.valid)
        collector = next(item for item in hostile["collectors"] if item["id"] == "accelerometer.v1")
        collector["configuration"]["required"] = ""
        with self.assertRaisesRegex(catalog.CatalogError, "must be an array"):
            catalog.validate(hostile)

    def test_cross_field_bound_must_reference_an_integer_field(self) -> None:
        hostile = copy.deepcopy(self.valid)
        collector = next(item for item in hostile["collectors"] if item["id"] == "location.v1")
        collector["configuration"]["fields"]["minimum_interval_millis"]["maximum_field"] = "priority"
        with self.assertRaisesRegex(catalog.CatalogError, "not another integer field"):
            catalog.validate(hostile)

    def test_payload_enum_values_are_required(self) -> None:
        hostile = copy.deepcopy(self.valid)
        collector = next(item for item in hostile["collectors"] if item["id"] == "keyboard_touch.v1")
        del collector["payloads"][0]["fields"]["action"]["enum"]
        with self.assertRaisesRegex(catalog.CatalogError, "wrong members"):
            catalog.validate(hostile)

    def test_float_configuration_value_is_rejected_while_loading(self) -> None:
        encoded = json.dumps(self.valid).replace('"maximum": 60000000', '"maximum": 1.5', 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(encoded, encoding="utf-8")
            with self.assertRaisesRegex(catalog.CatalogError, "non-integral"):
                catalog.load(path)


if __name__ == "__main__":
    unittest.main()
