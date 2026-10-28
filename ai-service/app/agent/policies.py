"""Support policies the agent can look up, loaded from knowledge/policies.md.

Each "## <topic>" section is one policy. Stage 4 replaces this exact-topic lookup
with semantic search (RAG) over a larger knowledge base.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.agent.schemas import PolicyTopic

DEFAULT_PATH = Path(__file__).resolve().parents[2] / "knowledge" / "policies.md"


class PolicyLibrary:
    def __init__(self, sections: dict[str, str]):
        self._sections = sections

    @classmethod
    def from_markdown(cls, path: Path = DEFAULT_PATH) -> "PolicyLibrary":
        text = path.read_text(encoding="utf-8")
        sections: dict[str, str] = {}
        for match in re.finditer(r"^## (\w+)\s*\n(.*?)(?=^## |\Z)", text, flags=re.M | re.S):
            sections[match.group(1).strip().lower()] = match.group(2).strip()
        missing = {t.value for t in PolicyTopic} - sections.keys()
        if missing:
            raise ValueError(f"{path} is missing policy sections: {sorted(missing)}")
        return cls(sections)

    def get(self, topic: PolicyTopic) -> str:
        return self._sections[topic.value]
