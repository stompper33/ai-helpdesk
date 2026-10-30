"""Re-index the knowledge base without restarting the service.

    python -m scripts.index_knowledge

Uses the same settings as the app (.env): with OPENAI_API_KEY and KNOWLEDGE_DB_URL it embeds
new or edited chunks into pgvector and removes deleted ones. Unchanged chunks cost nothing.
(The app also does this automatically at startup.)
"""

import asyncio

from app.config import get_settings
from app.main import build_knowledge_base


async def main() -> None:
    kb = build_knowledge_base(get_settings())
    await kb.store.open()
    try:
        stats = await kb.sync()
        print(stats)
    finally:
        await kb.store.close()


if __name__ == "__main__":
    asyncio.run(main())
