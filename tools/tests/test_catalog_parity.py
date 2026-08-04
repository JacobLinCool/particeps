from __future__ import annotations

import copy
import unittest

from tools import catalog, catalog_parity


class CatalogParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.valid = catalog.load(catalog.DEFAULT_CATALOG)

    def test_repository_platform_projections_match(self) -> None:
        catalog_parity.check(self.valid)

    def test_generated_event_contract_drift_is_rejected(self) -> None:
        hostile = copy.deepcopy(self.valid)
        collector = next(item for item in hostile["collectors"] if item["id"] == "accelerometer.v1")
        collector["maximum_encoded_event_bytes"] += 1
        with self.assertRaisesRegex(catalog_parity.ParityError, "generated Kotlin event contract"):
            catalog_parity.check(hostile)


if __name__ == "__main__":
    unittest.main()
