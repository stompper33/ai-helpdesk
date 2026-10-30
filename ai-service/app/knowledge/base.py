"""The knowledge base the agent searches: indexing (sync) and retrieval (search).

    sync():   load markdown -> chunks -> embed only new/changed chunks -> store; remove deleted ones
    search(): embed the query -> nearest chunks by cosine similarity   (vector mode)
              or BM25 keyword ranking when no embedding model is available  (keyword mode)
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path

from app.knowledge.documents import DEFAULT_DIR, load_chunks
from app.knowledge.embeddings import Embedder
from app.knowledge.stores import InMemoryStore, KnowledgeStore

log = logging.getLogger("ai_service.knowledge")


@dataclass(frozen=True)
class Hit:
    source: str     # chunk id, used as the citation
    title: str
    section: str
    text: str
    score: float


@dataclass(frozen=True)
class IndexStats:
    mode: str
    store: str
    chunks: int
    embedded: int
    unchanged: int
    deleted: int
    duration_ms: int


class KnowledgeBase:
    def __init__(self, store: KnowledgeStore, embedder: Embedder | None = None, directory: Path = DEFAULT_DIR):
        self.store = store
        self.embedder = embedder
        self.directory = directory
        self.last_sync: IndexStats | None = None

    @property
    def mode(self) -> str:
        return "vector" if self.embedder else "keyword"

    async def sync(self) -> IndexStats:
        started = time.perf_counter()
        chunks = load_chunks(self.directory)
        model = self.embedder.model if self.embedder else "keyword"
        existing = await self.store.existing()

        changed = [c for c in chunks
                   if (s := existing.get(c.id)) is None or s.content_hash != c.content_hash or s.embedding_model != model]
        stale = sorted(set(existing) - {c.id for c in chunks})

        vectors = await self.embedder.embed([c.embed_text for c in changed]) if (self.embedder and changed) else None
        await self.store.upsert(changed, vectors, model)
        await self.store.delete(stale)

        self.last_sync = IndexStats(mode=self.mode, store=self.store.name, chunks=len(chunks), embedded=len(changed),
                                    unchanged=len(chunks) - len(changed), deleted=len(stale),
                                    duration_ms=int((time.perf_counter() - started) * 1000))
        log.info("knowledge index synced: %s", self.last_sync)
        return self.last_sync

    async def fall_back_to_keyword(self) -> IndexStats:
        """Used when the embedding API or the knowledge database is unavailable at startup."""
        try:
            await self.store.close()
        except Exception:
            pass
        self.store, self.embedder = InMemoryStore(), None
        return await self.sync()

    async def search(self, query: str, k: int = 4) -> list[Hit]:
        if self.embedder:
            [query_vector] = await self.embedder.embed([query])
            pairs = await self.store.vector_search(query_vector, k)
        else:
            pairs = await self.store.keyword_search(query, k)
        return [Hit(source=c.id, title=c.title, section=c.heading, text=c.text, score=round(score, 3))
                for c, score in pairs]


def in_memory_keyword_base(directory: Path = DEFAULT_DIR) -> KnowledgeBase:
    """Offline knowledge base (no API key, no database): used by tests and the rules provider."""
    return KnowledgeBase(InMemoryStore(), embedder=None, directory=directory)
