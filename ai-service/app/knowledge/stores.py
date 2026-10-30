"""Where chunks (and their vectors) live, and how they are searched.

- PgVectorStore: PostgreSQL + pgvector in the separate "knowledge" database (Docker).
  Survives restarts, so only new or edited chunks are embedded again.
- InMemoryStore: plain Python, for tests and local runs. Supports vector search (when
  embeddings are available) and keyword search (BM25) for the offline mode.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass
from typing import Protocol

from app.knowledge.documents import Chunk
from app.knowledge.embeddings import EMBEDDING_DIMENSIONS


@dataclass(frozen=True)
class StoredChunk:
    content_hash: str
    embedding_model: str


class KnowledgeStore(Protocol):
    name: str

    async def open(self) -> None: ...

    async def close(self) -> None: ...

    async def existing(self) -> dict[str, StoredChunk]: ...

    async def upsert(self, chunks: list[Chunk], vectors: list[list[float]] | None, embedding_model: str) -> None: ...

    async def delete(self, chunk_ids: list[str]) -> None: ...

    async def vector_search(self, query_vector: list[float], k: int) -> list[tuple[Chunk, float]]: ...

    async def keyword_search(self, query: str, k: int) -> list[tuple[Chunk, float]]: ...

    async def count(self) -> int: ...


# ---- in memory ---------------------------------------------------------------------

class InMemoryStore:
    name = "memory"

    def __init__(self) -> None:
        self._chunks: dict[str, Chunk] = {}
        self._vectors: dict[str, list[float]] = {}
        self._meta: dict[str, StoredChunk] = {}
        self._bm25: _Bm25 | None = None

    async def open(self) -> None:
        pass

    async def close(self) -> None:
        pass

    async def existing(self) -> dict[str, StoredChunk]:
        return dict(self._meta)

    async def upsert(self, chunks, vectors, embedding_model) -> None:
        for i, chunk in enumerate(chunks):
            self._chunks[chunk.id] = chunk
            self._meta[chunk.id] = StoredChunk(chunk.content_hash, embedding_model)
            if vectors is not None:
                self._vectors[chunk.id] = vectors[i]
        self._bm25 = None

    async def delete(self, chunk_ids) -> None:
        for cid in chunk_ids:
            self._chunks.pop(cid, None)
            self._vectors.pop(cid, None)
            self._meta.pop(cid, None)
        self._bm25 = None

    async def vector_search(self, query_vector, k):
        scored = [(self._chunks[cid], _cosine(query_vector, vec)) for cid, vec in self._vectors.items()]
        return sorted(scored, key=lambda pair: pair[1], reverse=True)[:k]

    async def keyword_search(self, query, k):
        if self._bm25 is None:
            self._bm25 = _Bm25(list(self._chunks.values()))
        return self._bm25.search(query, k)

    async def count(self) -> int:
        return len(self._chunks)


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    norm = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b))
    return dot / norm if norm else 0.0


_STOPWORDS = set("""a an and are as at be but by can do does for from has have how i if in is it its me my
no not of on or our so that the their them then there this to too was we were what when where which who
why will with you your yours please hi hello thanks""".split())


def tokenize(text: str) -> list[str]:
    return [t for t in re.findall(r"[a-z0-9]+", text.lower()) if t not in _STOPWORDS and len(t) > 1]


class _Bm25:
    """Classic keyword ranking: words that are rare across the knowledge base count more."""

    def __init__(self, chunks: list[Chunk], k1: float = 1.5, b: float = 0.75):
        self.chunks = chunks
        self.k1, self.b = k1, b
        self.docs = [Counter(tokenize(c.embed_text)) for c in chunks]
        self.lengths = [sum(d.values()) for d in self.docs]
        self.avg_len = (sum(self.lengths) / len(self.lengths)) if self.lengths else 0
        df = Counter(term for d in self.docs for term in d)
        n = len(chunks)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def search(self, query: str, k: int) -> list[tuple[Chunk, float]]:
        terms = tokenize(query)
        results = []
        for chunk, tf, length in zip(self.chunks, self.docs, self.lengths):
            score = 0.0
            for t in terms:
                if t in tf:
                    f = tf[t]
                    score += self.idf[t] * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * length / self.avg_len))
            if score > 0:
                results.append((chunk, score))
        return sorted(results, key=lambda pair: pair[1], reverse=True)[:k]


# ---- PostgreSQL + pgvector ---------------------------------------------------------

_SCHEMA = f"""
CREATE TABLE IF NOT EXISTS kb_chunks (
    id               TEXT PRIMARY KEY,
    doc_id           TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    heading          TEXT        NOT NULL,
    content          TEXT        NOT NULL,
    content_hash     TEXT        NOT NULL,
    embedding_model  TEXT        NOT NULL,
    embedding        vector({EMBEDDING_DIMENSIONS}) NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- HNSW: an approximate nearest-neighbour index, so search stays fast as the knowledge base grows
CREATE INDEX IF NOT EXISTS kb_chunks_embedding_hnsw ON kb_chunks USING hnsw (embedding vector_cosine_ops);
"""


class PgVectorStore:
    """Owned by ai-service only (its own database and user, created by postgres/init/)."""

    name = "pgvector"

    def __init__(self, url: str):
        self._url = url
        self._pool = None

    async def open(self) -> None:
        from psycopg_pool import AsyncConnectionPool

        self._pool = AsyncConnectionPool(self._url, min_size=1, max_size=4, open=False)
        await self._pool.open(wait=True, timeout=30)
        async with self._pool.connection() as conn:
            await conn.execute(_SCHEMA)  # the vector extension itself is created by the init script

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()

    async def existing(self) -> dict[str, StoredChunk]:
        async with self._pool.connection() as conn:
            cur = await conn.execute("SELECT id, content_hash, embedding_model FROM kb_chunks")
            return {row[0]: StoredChunk(row[1], row[2]) for row in await cur.fetchall()}

    async def upsert(self, chunks, vectors, embedding_model) -> None:
        if not chunks:
            return
        if vectors is None:
            raise ValueError("PgVectorStore needs embeddings")
        async with self._pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.executemany(
                    """INSERT INTO kb_chunks (id, doc_id, title, heading, content, content_hash, embedding_model, embedding)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector)
                       ON CONFLICT (id) DO UPDATE SET
                         doc_id = EXCLUDED.doc_id, title = EXCLUDED.title, heading = EXCLUDED.heading,
                         content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,
                         embedding_model = EXCLUDED.embedding_model, embedding = EXCLUDED.embedding,
                         updated_at = now()""",
                    [(c.id, c.doc_id, c.title, c.heading, c.text, c.content_hash, embedding_model, _vector_literal(v))
                     for c, v in zip(chunks, vectors)])

    async def delete(self, chunk_ids) -> None:
        if chunk_ids:
            async with self._pool.connection() as conn:
                await conn.execute("DELETE FROM kb_chunks WHERE id = ANY(%s)", (list(chunk_ids),))

    async def vector_search(self, query_vector, k):
        vec = _vector_literal(query_vector)
        async with self._pool.connection() as conn:
            # <=> is pgvector's cosine DISTANCE (0 = same direction); similarity = 1 - distance
            cur = await conn.execute(
                """SELECT id, doc_id, title, heading, content, 1 - (embedding <=> %s::vector) AS score
                   FROM kb_chunks ORDER BY embedding <=> %s::vector LIMIT %s""", (vec, vec, k))
            rows = await cur.fetchall()
        return [(Chunk(id=r[0], doc_id=r[1], title=r[2], heading=r[3], text=r[4]), float(r[5])) for r in rows]

    async def keyword_search(self, query, k):
        raise NotImplementedError("PgVectorStore is only used together with embeddings")

    async def count(self) -> int:
        async with self._pool.connection() as conn:
            cur = await conn.execute("SELECT count(*) FROM kb_chunks")
            return (await cur.fetchone())[0]


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{x:.7f}" for x in vector) + "]"
