"""Load the markdown knowledge base and split it into chunks.

Document format (one topic per file, anywhere under knowledge/):

    # Title of the document
    Optional intro paragraph(s)            -> chunk "Overview"
    ## Section heading
    Section text                           -> one chunk per section

Why split by headings: a section is usually one self-contained answer ("Pairing",
"Crackling audio"), which is exactly the unit the agent should get back. Sections
longer than max_chars are split further on paragraph boundaries.

Each chunk's text for embedding starts with "<title> - <heading>", so a short section
like "Battery" still carries which product it belongs to (a "contextual chunk header").
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DIR = Path(__file__).resolve().parents[2] / "knowledge"


@dataclass(frozen=True)
class Chunk:
    id: str          # stable citation id, e.g. "products/aurora-headphones#crackling-audio"
    doc_id: str      # file path without .md, e.g. "products/aurora-headphones"
    title: str
    heading: str
    text: str        # the section body (what the agent reads)

    @property
    def embed_text(self) -> str:
        return f"{self.title} - {self.heading}\n{self.text}"

    @property
    def content_hash(self) -> str:
        """Changes whenever the text that gets embedded changes, so only edited chunks are re-embedded."""
        return hashlib.sha256(self.embed_text.encode("utf-8")).hexdigest()[:16]


def load_chunks(directory: Path = DEFAULT_DIR, max_chars: int = 1500) -> list[Chunk]:
    chunks: list[Chunk] = []
    for path in sorted(directory.rglob("*.md")):
        if path.name.lower() == "readme.md":
            continue
        doc_id = path.relative_to(directory).with_suffix("").as_posix()
        chunks.extend(split_document(doc_id, path.read_text(encoding="utf-8"), max_chars))
    return chunks


def split_document(doc_id: str, markdown: str, max_chars: int = 1500) -> list[Chunk]:
    title = doc_id
    sections: list[tuple[str, list[str]]] = [("Overview", [])]
    for line in markdown.splitlines():
        if line.startswith("# ") and title == doc_id:
            title = line[2:].strip()
        elif line.startswith("## "):
            sections.append((line[3:].strip(), []))
        else:
            sections[-1][1].append(line)

    chunks: list[Chunk] = []
    used_ids: set[str] = set()
    for heading, lines in sections:
        body = "\n".join(lines).strip()
        if not body:
            continue
        for part_no, part in enumerate(_split_long(body, max_chars), start=1):
            base = f"{doc_id}#{_slug(heading)}" + (f"-p{part_no}" if part_no > 1 else "")
            chunk_id, n = base, 2
            while chunk_id in used_ids:  # two sections with the same heading
                chunk_id, n = f"{base}-{n}", n + 1
            used_ids.add(chunk_id)
            chunks.append(Chunk(id=chunk_id, doc_id=doc_id, title=title, heading=heading, text=part))
    return chunks


def _split_long(body: str, max_chars: int) -> list[str]:
    if len(body) <= max_chars:
        return [body]
    parts, current = [], ""
    for paragraph in re.split(r"\n\s*\n", body):
        if current and len(current) + len(paragraph) + 2 > max_chars:
            parts.append(current.strip())
            current = ""
        current += paragraph + "\n\n"
    if current.strip():
        parts.append(current.strip())
    return parts


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "section"
