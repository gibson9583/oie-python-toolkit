# SPDX-License-Identifier: MPL-2.0
"""Regenerate shared/src/test/resources/golden/spec-digests.json.

An independent encoder for the canonical execution-spec digest. Java and the
web administrator must both reproduce these digests.
"""
import hashlib
import json
import os

CASES = [
    {"name": "default step", "pythonScript": "pass\n", "environmentProfileId": "", "timeoutMillis": 30000, "contractVersion": 1},
    {"name": "crlf and cr", "pythonScript": "x = 1\r\nmsg = msg\rpass\r\n", "environmentProfileId": "6f1c1a5e-3c51-4f3e-9d1f-0d3a1b2c4d5e", "timeoutMillis": 5000, "contractVersion": 1},
    {"name": "unicode and controls", "pythonScript": "msg['n'] = 'café   \U0001F600 \t\b\f\x01\"\\\\'\n", "environmentProfileId": "p", "timeoutMillis": 0, "contractVersion": 0},
    {"name": "built-in standard library", "pythonScript": "pass\n", "environmentProfileId": "232030e4-aa0d-39fc-a889-fed1318ef412", "timeoutMillis": 30000, "contractVersion": 1},
]


def spec(case):
    source = case["pythonScript"].replace("\r\n", "\n").replace("\r", "\n")
    return {
        "canonicalization": 1,
        "contractVersion": case["contractVersion"] if case["contractVersion"] > 0 else 1,
        "profileId": case["environmentProfileId"],
        "timeoutMillis": case["timeoutMillis"] if case["timeoutMillis"] > 0 else 30000,
        "source": source,
    }


def main():
    out = []
    for case in CASES:
        canonical = json.dumps(spec(case), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        out.append(dict(case, canonical=canonical, digest=hashlib.sha256(canonical.encode("utf-8")).hexdigest()))
    path = os.path.join(os.path.dirname(__file__), "..", "shared", "src", "test", "resources", "golden", "spec-digests.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
