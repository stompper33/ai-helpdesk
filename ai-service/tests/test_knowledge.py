"""Stage 4: chunking, keyword retrieval and incremental indexing. No network, no database."""

import asyncio

from app.knowledge.base import KnowledgeBase, in_memory_keyword_base
from app.knowledge.documents import load_chunks, split_document
from app.knowledge.stores import InMemoryStore

DOC = """# Aurora headphones guide
Wireless noise-cancelling headphones.

## Pairing
Hold the power button for 5 seconds until the light flashes blue.

## Crackling audio
Update to firmware 2.1. If crackling continues after a reset it is a hardware fault.

## Pairing
A second section with the same heading.
"""


def test_split_by_headings_with_stable_unique_ids():
    chunks = split_document("products/aurora", DOC)
    assert [c.id for c in chunks] == ["products/aurora#overview", "products/aurora#pairing",
                                      "products/aurora#crackling-audio", "products/aurora#pairing-2"]
    assert chunks[2].title == "Aurora headphones guide" and chunks[2].heading == "Crackling audio"
    assert chunks[2].embed_text.startswith("Aurora headphones guide - Crackling audio\n")


def test_long_sections_are_split_on_paragraphs():
    body = "\n\n".join(f"Paragraph {i} " + "word " * 60 for i in range(10))
    chunks = split_document("faq/long", f"# Long\n## Big section\n{body}", max_chars=800)
    assert len(chunks) > 1 and all(len(c.text) <= 800 for c in chunks)
    assert chunks[1].id == "faq/long#big-section-p2"


def test_content_hash_changes_only_when_text_changes():
    a = split_document("d", DOC)[1]
    b = split_document("d", DOC)[1]
    c = split_document("d", DOC.replace("5 seconds", "8 seconds"))[1]
    assert a.content_hash == b.content_hash != c.content_hash


def test_whole_knowledge_folder_loads():
    chunks = load_chunks()
    assert len(chunks) >= 7
    assert len({c.id for c in chunks}) == len(chunks)


def test_keyword_search_finds_the_relevant_policy():
    kb = in_memory_keyword_base()
    asyncio.run(kb.sync())
    hits = asyncio.run(kb.search("password reset email never arrives", 3))
    assert hits[0].source.startswith("policies/account-security")


class CountingEmbedder:
    """Fake embedding model: counts how many texts it was asked to embed."""

    model = "fake-embedder"

    def __init__(self):
        self.embedded = 0

    async def embed(self, texts):
        self.embedded += len(texts)
        return [[float(len(t)), 1.0, float(t.count("a"))] for t in texts]


def test_sync_only_embeds_new_or_changed_chunks(tmp_path):
    (tmp_path / "guide.md").write_text(DOC)
    store, embedder = InMemoryStore(), CountingEmbedder()
    kb = KnowledgeBase(store, embedder, directory=tmp_path)

    first = asyncio.run(kb.sync())
    assert (first.embedded, first.unchanged) == (4, 0) and embedder.embedded == 4

    second = asyncio.run(kb.sync())
    assert (second.embedded, second.unchanged) == (0, 4) and embedder.embedded == 4  # nothing re-embedded

    (tmp_path / "guide.md").write_text(DOC.replace("firmware 2.1", "firmware 2.2").replace(
        "## Pairing\nA second section with the same heading.\n", ""))
    third = asyncio.run(kb.sync())
    assert (third.embedded, third.deleted) == (1, 1)  # one edited, one removed
    assert asyncio.run(store.count()) == 3
