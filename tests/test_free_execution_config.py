import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FreeExecutionConfigTests(unittest.TestCase):
    def test_zero_charge_manifest(self):
        policy = json.loads((ROOT / "environment-manifest.json").read_text())["execution_cost_policy"]
        self.assertEqual(policy["spend_policy"], "FREE_ONLY")
        self.assertEqual(policy["maximum_new_service_charge"], 0)
        self.assertIs(policy["billing_activation_allowed"], False)
        self.assertIs(policy["paid_overage_authorized"], False)

    def test_monthly_tier_is_not_selected_as_permanent_solution(self):
        policy = json.loads((ROOT / "environment-manifest.json").read_text())["execution_cost_policy"]
        self.assertIs(policy["monthly_free_tier_is_permanent_solution"], False)
        self.assertEqual(policy["quota_free_cloud_candidate_status"], "REQUIRES_EXPLICIT_SOURCE_PUBLICATION_APPROVAL")
        self.assertFalse((ROOT / "codemagic.yaml").exists())

    def test_every_hosted_job_is_guarded_before_runner_allocation(self):
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            text = path.read_text().split("\njobs:\n", 1)[1]
            jobs = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", text)[1:]
            self.assertTrue(jobs)
            for job in jobs:
                header = job.split("    steps:", 1)[0]
                self.assertIn("APPFUSION_FREE_APPROVED_SHA == github.sha", header, path.name)
