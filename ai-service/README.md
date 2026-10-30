# ai-service

A small Python service (FastAPI) that triages helpdesk tickets with OpenAI. It's a separate application from `helpdesk-api`, with its own process, dependencies and port. Spring Boot calls it over HTTP.

```
Postman ──► helpdesk-api :8080 ──POST /v1/triage──► ai-service :8000 ──► OpenAI
                (Java)          ◄──── JSON ──────       (Python)
```

## Run it locally

You need **Python 3.11 or newer**. Check with `python --version` (on macOS/Linux it may be `python3`).

### 1. Create a virtual environment (one time)

A virtual environment (`.venv`) is Python's version of a project-local Maven repository: the dependencies are installed for this project only.

```bash
cd ai-service
python -m venv .venv
```

### 2. Activate it (every new terminal)

| OS | Command |
|---|---|
| macOS / Linux | `source .venv/bin/activate` |
| Windows PowerShell | `.venv\Scripts\Activate.ps1` |
| Windows cmd | `.venv\Scripts\activate.bat` |

Your prompt now starts with `(.venv)`. If PowerShell blocks the script, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once.

### 3. Install dependencies (one time, or when requirements change)

```bash
pip install -r requirements-dev.txt
```

`requirements.txt` plays the role of your `pom.xml` dependencies. The `-dev` file adds the test tools.

### 4. Add your OpenAI key

```bash
cp .env.example .env        # Windows: copy .env.example .env
```

Open `.env` and replace `sk-your-key-here` with your key from https://platform.openai.com/api-keys. `.env` is git-ignored, so the key stays on your machine.

With no key, the service still runs. It uses offline keyword rules instead, which is handy for testing the plumbing without spending anything.

### 5. Start the service

```bash
uvicorn app.main:app --reload --port 8000
```

`--reload` restarts the server when you save a file, much like Spring DevTools. The startup log shows which provider it picked:

```
ai-service starting with provider=openai model=gpt-5-mini
```

| What | Where |
|---|---|
| Health | http://localhost:8000/health |
| Swagger UI (interactive docs) | http://localhost:8000/docs |
| Triage endpoint | `POST http://localhost:8000/v1/triage` |

### 6. Run it with Spring Boot

In a second terminal, start `helpdesk-api` as usual (`mvn spring-boot:run`). It expects ai-service at `http://localhost:8000`; override that with the `AI_SERVICE_URL` environment variable. Then use the **Helpdesk AI (stage 2)** Postman collection.

## Tests and evaluation

```bash
pytest                                      # unit + API tests, never call OpenAI
python -m scripts.eval_triage --provider rules   # keyword baseline
python -m scripts.eval_triage                    # your configured model (uses your key, ~14 small calls)
python -m scripts.eval_triage --runs 3           # repeat and average; LLM output varies run to run
python -m scripts.eval_retrieval --mode both     # stage 4: keyword vs embedding search, recall@k + MRR
python -m scripts.index_knowledge                # re-index after editing knowledge/ (also runs at startup)
```

The eval runs 14 labelled tickets (`evals/triage_cases.json`) and reports category accuracy, priority accuracy and "priority within one level". Results so far (prompt v2, 3 runs): the LLM scores 100% on category, 79% exact priority and 100% within one level, against 86% / 43% / 93% for the keyword baseline, and it resists the prompt-injection case (e13), which fools the keyword rules. Re-run it after any prompt change to see whether you made things better or worse.

The retrieval eval (`scripts.eval_retrieval`) scores the knowledge base search on 27 labelled questions: recall@1 96% with embeddings vs. 59% with keyword search.

## Configuration (`.env`)

| Variable | Default | Meaning |
|---|---|---|
| `OPENAI_API_KEY` | none | Your key. Without it, `auto` falls back to rules |
| `OPENAI_MODEL` | `gpt-5-mini` | Any model with structured-output support |
| `OPENAI_TIMEOUT_SECONDS` | `20` | Per request (the SDK also retries twice) |
| `AI_PROVIDER` | `auto` | `auto`, `openai` or `rules` |
| `SERVICE_API_KEY` | none | If set, callers must send `X-Api-Key` (set `AI_SERVICE_API_KEY` to the same value for Spring Boot) |
| `HELPDESK_API_URL` | `http://localhost:8080` | Where the agent's tools call Spring Boot |
| `HELPDESK_API_TIMEOUT_SECONDS` | `10` | Per tool call |
| `HELPDESK_API_KEY` | `local-dev-secret` | Stage 5c: key for helpdesk-api's internal agent API (must match Java's `INTERNAL_API_KEY`) |
| `AGENT_MODEL` | `OPENAI_MODEL` | Model for the agent, if you want a different one |
| `AGENT_MAX_STEPS` | `8` | Model turns before the run is stopped |
| `AGENT_TIMEOUT_SECONDS` | `30` | Per model call |
| `KNOWLEDGE_DB_URL` | none | Stage 4: pgvector database; without it, vectors are kept in memory |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model (stored as 1536 dimensions) |
| `KNOWLEDGE_TOP_K` | `4` | Passages returned per knowledge search |
| `AUTO_TRIAGE_WORKER_ENABLED` | `false` | Stage 5b: run the RabbitMQ worker inside the app (on in Docker) |
| `RABBITMQ_URL` | `amqp://helpdesk:helpdesk@localhost:5672/` | Broker connection |
| `WORKER_PREFETCH` | `4` | Messages processed concurrently |
| `WORKER_MAX_ATTEMPTS` | `3` | Tries per message before it goes to the dead-letter queue |
| `LOG_LEVEL` | `INFO` | Each triage and agent step logs provider, model, result, latency and token usage |

## How it works

| File | Role (Spring analogy) |
|---|---|
| `app/main.py` | App factory + routes (`@RestController`) |
| `app/schemas.py` | Request/response models (DTOs). Pydantic validates them, much like Bean Validation |
| `app/config.py` | Typed settings from env/.env (`@ConfigurationProperties`) |
| `app/triage/service.py` | Timing, clamping, logging (`@Service`) |
| `app/triage/classifiers.py` | `TicketClassifier` interface with OpenAI and keyword-rule implementations |
| `app/triage/prompts.py` | System prompt + how ticket context is formatted |
| `app/agent/runner.py` | The agent loop: model turn, run tools, send results back, until `submit_reply` |
| `app/agent/tools.py` | Tool definitions (JSON schemas) + handlers; read-only and customer-scoped |
| `app/agent/models.py` | `AgentModel` interface: OpenAI Responses API, or an offline scripted model |
| `app/agent/helpdesk_client.py` | HTTP client for calling back into Spring Boot (`RestClient` equivalent) |
| `app/knowledge/` | Stage 4 RAG: markdown chunker, embeddings, pgvector + in-memory stores, incremental indexing |
| `knowledge/` | The documents the agent searches (see `knowledge/README.md` for the format) |
| `app/worker/handler.py` | Stage 5b: what to do with one queued message (ack / retry / dead-letter). Pure logic, unit-tested |
| `app/worker/consumer.py` | Stage 5b: aio-pika consumer started in the FastAPI lifespan (`@RabbitListener` equivalent) |

Design points worth mentioning:

- **Structured outputs.** `TriageDecision` is sent to OpenAI as a JSON schema, and the reply is parsed straight back into a typed object. Nothing is parsed from free text, and the enum values match the Java enums exactly.
- **Provider interface.** The service depends on `TicketClassifier`, not on OpenAI. Swapping models or vendors, or using the offline rules in tests, needs no other changes.
- **Policy stays in code, judgement goes to the model.** The model rates priority from the ticket's content only. Customer tier is deliberately *not* sent to it, because tier is business policy that `helpdesk-api` already applies (premium customers get half the SLA time). Version 1 of the prompt also told the model to raise priority for premium customers, which counted tier twice and left a business rule to an LLM that applied it inconsistently. The eval made this visible.
- **Prompt-injection guard.** Ticket text is wrapped in `<ticket>` tags, and the prompt tells the model to treat it as data.
- **Stateless.** No database. Java owns all state and decides whether to apply the AI's suggestion.

### The reply-drafting agent (stage 3)

`POST /v1/agent/draft-reply` with `{"ticketId": 1}`. Spring Boot must be running, because the agent's tools call it.

1. Load the ticket from Spring Boot's internal agent API (`GET /internal/agent/tickets/{id}`, with the service key). System notes, earlier AI output and the customer's email are left out.
2. Send it to the model along with five tool definitions: `get_customer_orders`, `get_order`, `get_ticket_history`, `search_knowledge_base` and `submit_reply`.
3. Loop: the model asks for tools, we run them (HTTP calls to Spring Boot, or a policy lookup) and send back the results. `tool_choice="required"` means the only way out is `submit_reply`.
4. Validate the submission with pydantic. If it's invalid, the error goes back to the model to fix. Then return the draft plus a trace of every tool call.

Without an API key, a scripted offline model runs the same loop, so the whole Java → Python → Java path can be tested for free. `tests/test_agent.py` covers tool scoping, error recovery, invalid submissions and the step limit.
