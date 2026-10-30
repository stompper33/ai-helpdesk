"""Measure retrieval quality: does the search find the right document?

    python -m scripts.eval_retrieval                  # keyword (BM25), offline and free
    python -m scripts.eval_retrieval --mode vector    # OpenAI embeddings (needs OPENAI_API_KEY)
    python -m scripts.eval_retrieval --mode both      # side by side

This tests retrieval on its own, separately from the LLM: if the right passage isn't
retrieved, no prompt can produce a grounded answer.

Metrics:
  recall@k  share of questions whose expected document appears in the top k results
  MRR       mean reciprocal rank: 1.0 if always first, 0.5 if always second, ...
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from app.knowledge.base import KnowledgeBase, in_memory_keyword_base
from app.knowledge.stores import InMemoryStore

CASES = Path(__file__).resolve().parent.parent / "evals" / "retrieval_cases.json"
KS = (1, 3, 5)


async def build(mode: str) -> KnowledgeBase:
    if mode == "keyword":
        kb = in_memory_keyword_base()
    else:
        from app.config import get_settings
        from app.knowledge.embeddings import OpenAIEmbedder

        settings = get_settings()
        if not settings.openai_api_key or not settings.openai_api_key.get_secret_value():
            raise SystemExit("--mode vector needs OPENAI_API_KEY (in .env)")
        kb = KnowledgeBase(InMemoryStore(), OpenAIEmbedder(settings.openai_api_key.get_secret_value(),
                                                           settings.embedding_model))
    await kb.sync()
    return kb


async def evaluate(mode: str) -> dict:
    kb = await build(mode)
    cases = json.loads(CASES.read_text())
    ranks: list[int | None] = []
    print(f"\nmode={mode} chunks={kb.last_sync.chunks}\n")
    print(f"{'id':<5}{'rank':<6}{'expected':<36}top result")
    for case in cases:
        hits = await kb.search(case["query"], max(KS))
        docs = [h.source.split("#")[0] for h in hits]
        rank = docs.index(case["expectedDoc"]) + 1 if case["expectedDoc"] in docs else None
        ranks.append(rank)
        print(f"{case['id']:<5}{(str(rank) if rank else '-'):<6}{case['expectedDoc']:<36}{hits[0].source if hits else '-'}")

    n = len(cases)
    summary = {"mode": mode, "cases": n}
    for k in KS:
        summary[f"recall@{k}"] = round(sum(1 for r in ranks if r and r <= k) / n, 3)
    summary["mrr"] = round(sum(1 / r for r in ranks if r) / n, 3)
    print("\n" + json.dumps(summary, indent=2))
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["keyword", "vector", "both"], default="keyword")
    mode = parser.parse_args().mode
    modes = ["keyword", "vector"] if mode == "both" else [mode]

    async def run_all():
        return [await evaluate(m) for m in modes]

    results = asyncio.run(run_all())
    if len(results) == 2:
        print("\n" + " " * 12 + "".join(f"{m:>10}" for m in ("keyword", "vector")))
        for metric in [f"recall@{k}" for k in KS] + ["mrr"]:
            print(f"{metric:<12}" + "".join(f"{r[metric]:>10}" for r in results))


if __name__ == "__main__":
    main()
