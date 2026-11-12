"""A process exit or expected-looking log alone must never certify the model."""
import subprocess
import unittest

from tools.check_storage_model import verify_result


class StorageModelResultTest(unittest.TestCase):
    def test_incomplete_or_crashed_search_is_rejected(self):
        complete = ("Model checking completed. No error has been found.\n"
                    "12 states generated, 8 distinct states found, 0 states left on queue.")
        self.assertEqual(8, verify_result(subprocess.CompletedProcess([], 0, complete), None)["distinct_states"])
        for status, log in ((1, complete), (0, complete.replace("0 states left", "1 states left")), (0, "")):
            with self.subTest(status=status, log=log), self.assertRaises(RuntimeError):
                verify_result(subprocess.CompletedProcess([], status, log), None)

    def test_mutant_requires_the_expected_invariant_and_failure_exit(self):
        log = "Error: Invariant AcknowledgedDurable is violated."
        self.assertEqual("AcknowledgedDurable", verify_result(
            subprocess.CompletedProcess([], 12, log), "AcknowledgedDurable")["counterexample"])
        for status, output in ((0, log), (12, log.replace("AcknowledgedDurable", "TypeOK")), (1, "parse error")):
            with self.subTest(status=status, output=output), self.assertRaises(RuntimeError):
                verify_result(subprocess.CompletedProcess([], status, output), "AcknowledgedDurable")
