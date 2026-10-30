# Knowledge base

Fictional support content for the demo store, searched by the reply-drafting agent (stage 4, RAG).
This README is not indexed.

- `policies/`: returns, refunds, shipping, warranty, account security, support hours
- `products/`: one guide per product in the seed data
- `faq/`: orders and payment, international shipping, account and data, gift cards
- `troubleshooting/`: cross-product guides (Bluetooth, firmware updates)

## Format
```
# Document title
Intro paragraph (becomes the "Overview" chunk)

## Section heading
One self-contained answer per section. Each section becomes one chunk.
```

Each chunk is cited by its id: `<folder>/<file>#<section-slug>`, e.g. `products/aurora-headphones#crackling-popping-or-distorted-sound`.

## Editing
Add or edit a file and restart ai-service, or run `python -m scripts.index_knowledge`. Only new or
changed sections are embedded again, and deleted ones are removed from the index.
