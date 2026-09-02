from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def walk(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def verify(blueprint: dict[str, Any], attestation: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    policy = json.loads((ROOT / "policies/product-boundary.json").read_text(encoding="utf-8"))
    forbidden = {item.casefold() for item in policy["forbidden_keys"]}
    observed_keys = {key.casefold() for key, _ in walk(blueprint)}
    contaminated = sorted(observed_keys & forbidden)
    if contaminated:
        errors.append("Forbidden clean-room keys: " + ", ".join(contaminated))
    if blueprint.get("schema_version") != "1.0.0":
        errors.append("Unsupported ProductBlueprint schema version")
    if attestation.get("approval_status") != "APPROVED":
        errors.append("Product approval attestation is not APPROVED")
    expected = canonical_hash(blueprint)
    if attestation.get("product_blueprint_sha256", "").casefold() != expected:
        errors.append("Product Blueprint hash does not match approval attestation")
    targets = blueprint.get("target_platforms", {})
    if targets.get("android") and targets.get("ios") and blueprint.get("release_scope") == "EXPERIMENTAL_ANDROID_ONLY_PILOT":
        errors.append("Cross-platform target cannot use Android-only pilot release scope")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("blueprint", type=Path)
    parser.add_argument("attestation", type=Path)
    args = parser.parse_args()
    blueprint = json.loads(args.blueprint.read_text(encoding="utf-8-sig"))
    attestation = json.loads(args.attestation.read_text(encoding="utf-8-sig"))
    errors = verify(blueprint, attestation)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print("Clean-room transfer: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
