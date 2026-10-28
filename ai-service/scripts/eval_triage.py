"""Measure triage quality against a small labelled set.

    python -m scripts.eval_triage                      # uses AI_PROVIDER / .env (OpenAI if a key is set)
    python -m scripts.eval_triage --runs 3             # repeat and average (LLM output varies run to run)
    python -m scripts.eval_triage --provider rules     # offline keyword baseline

Prints one row per case (the most common prediction and how many runs got it right),
then category accuracy, exact priority accuracy and "within one level" priority accuracy,
averaged over the runs. Run it after changing the prompt or model to see whether
things got better or worse.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from collections import Counter
from pathlib import Path

from app.schemas import Priority, TriageRequest
from app.triage.prompts import PROMPT_VERSION
from app.triage.service import TriageService

CASES = Path(__file__).resolve().parent.parent / "evals" / "triage_cases.json"
_LEVELS = [p.value for p in Priority]


def score(results: list[dict]) -> dict:
    n = len(results)
    cat = sum(r["category"] == r["expectedCategory"] for r in results)
    pri = sum(r["priority"] == r["expectedPriority"] for r in results)
    near = sum(abs(_LEVELS.index(r["priority"]) - _LEVELS.index(r["expectedPriority"])) <= 1 for r in results)
    return {"categoryAccuracy": cat / n, "priorityAccuracy": pri / n, "priorityWithinOne": near / n}


def build_service(provider: str | None) -> TriageService:
    from app.triage.classifiers import RuleBasedClassifier

    if provider == "rules":
        return TriageService(RuleBasedClassifier())
    from app.config import get_settings  # needs pydantic-settings
    from app.main import build_classifier

    settings = get_settings()
    if provider:
        settings = settings.model_copy(update={"ai_provider": provider})
    return TriageService(build_classifier(settings))


async def run_once(service: TriageService, cases: list[dict]) -> list[dict]:
    results = []
    for case in cases:
        out = await service.triage(TriageRequest.model_validate(case))
        results.append({**case, "category": out.category.value, "priority": out.priority.value,
                        "confidence": out.confidence})
    return results


async def run(provider: str | None, runs: int) -> dict:
    service = build_service(provider)
    cases = json.loads(CASES.read_text())
    print(f"provider={service.classifier.name} model={service.classifier.model} "
          f"prompt={PROMPT_VERSION} runs={runs}\n")

    all_runs = [await run_once(service, cases) for _ in range(runs)]

    print(f"{'id':<5}{'category':<24}{'priority':<22}expected priority")
    for i, case in enumerate(cases):
        preds = [r[i] for r in all_runs]
        cat = Counter(p["category"] for p in preds).most_common(1)[0][0]
        pri = Counter(p["priority"] for p in preds).most_common(1)[0][0]
        cat_hits = sum(p["category"] == case["expectedCategory"] for p in preds)
        pri_hits = sum(p["priority"] == case["expectedPriority"] for p in preds)
        print(f"{case['id']:<5}{f'{cat_hits}/{runs} {cat}':<24}{f'{pri_hits}/{runs} {pri}':<22}"
              f"{case['expectedPriority']}")

    per_run = [score(r) for r in all_runs]
    summary = {"cases": len(cases), "runs": runs, "prompt": PROMPT_VERSION}
    for metric in per_run[0]:
        values = [s[metric] for s in per_run]
        summary[metric] = round(sum(values) / runs, 3)
        if runs > 1:
            summary[metric + "Range"] = [round(min(values), 3), round(max(values), 3)]
    print("\n" + json.dumps(summary, indent=2))
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--provider", choices=["openai", "rules"], help="override AI_PROVIDER")
    parser.add_argument("--runs", type=int, default=1, help="repeat the whole set N times and average")
    args = parser.parse_args()
    asyncio.run(run(args.provider, max(args.runs, 1)))


if __name__ == "__main__":
    main()
