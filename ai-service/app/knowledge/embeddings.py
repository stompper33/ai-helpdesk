"""Turning text into vectors.

An embedding model maps a text to a fixed-length list of numbers (here 1536) such that
texts with similar MEANING get vectors pointing in similar directions. "Headphones crackle"
and "audio distortion on the left side" end up close together even with no words in common.
"""

from __future__ import annotations

from typing import Protocol

EMBEDDING_DIMENSIONS = 1536  # must match the vector(1536) column in the knowledge database


class Embedder(Protocol):
    model: str

    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class OpenAIEmbedder:
    def __init__(self, api_key: str, model: str = "text-embedding-3-small", timeout_seconds: float = 30.0,
                 batch_size: int = 100):
        from openai import AsyncOpenAI

        self.model = model
        self._batch_size = batch_size
        self._client = AsyncOpenAI(api_key=api_key, timeout=timeout_seconds, max_retries=2)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self._batch_size):
            batch = texts[start:start + self._batch_size]
            # text-embedding-3 models accept "dimensions", so the column size stays fixed
            response = await self._client.embeddings.create(model=self.model, input=batch,
                                                            dimensions=EMBEDDING_DIMENSIONS)
            vectors.extend(item.embedding for item in sorted(response.data, key=lambda d: d.index))
        return vectors
