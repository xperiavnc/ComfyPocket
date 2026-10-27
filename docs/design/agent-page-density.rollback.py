"""Restore a selected preview copy; real repository rollbacks require a new commit."""

from pathlib import Path
import argparse
import hashlib
import json
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--target", type=Path, required=True)
parser.add_argument("--apply", action="store_true")
args = parser.parse_args()
directory = Path(__file__).resolve().parent
repository = directory.parents[1]
record = json.loads(
    (directory / "agent-page-density-loading-verification.json").read_text(encoding="utf-8")
)
target = args.target.resolve(strict=True)
if not target.is_file() or target.is_symlink():
    raise SystemExit("Target must be a regular preview file")
current_sha = hashlib.sha256(target.read_bytes()).hexdigest()
if current_sha != record["modified_sha256"]:
    raise SystemExit("Target hash differs from the verified repaired artifact")
original = subprocess.check_output([
    "git", "-C", str(repository), "show",
    record["baseline_commit"] + ":docs/design/agent-page-density.html",
])
original_sha = hashlib.sha256(original).hexdigest()
if original_sha != record["baseline_sha256"]:
    raise SystemExit("Baseline Git object failed hash verification")
print("Current SHA256:", current_sha)
print("Rollback SHA256:", original_sha)
if args.apply:
    target.write_bytes(original)
    if hashlib.sha256(target.read_bytes()).hexdigest() != original_sha:
        raise SystemExit("Restored target failed hash verification")
    print("Rollback verified")
else:
    print("Dry run verified; add --apply to restore this exact target")
