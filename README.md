# AI-Assisted Helpdesk

A customer-support helpdesk built as two cooperating services:

- **`helpdesk-api/`** (Java 21, Spring Boot): the system of record. It owns customers, orders, tickets, workflow rules and SLAs.
- **`ai-service/`** (Python, FastAPI + OpenAI): triages tickets, and runs an agent that looks up context through the Java API and drafts replies for a human to approve.

The design idea: **Java owns the business, Python owns the intelligence.** The AI never writes to the database directly. It goes through the same REST API and business rules as a human agent.

```
                    ┌──────────────────────────────┐
  Postman / UI ───► │  helpdesk-api (Spring Boot)  │ ◄── agent tools call these endpoints
                    │  rules · workflow · SLA · DB │
                    └──────────────┬──────▲────────┘
                 stage 2: "triage" │      │  stage 3: agent tools call
                  stage 3: "draft" ▼      │  read-only Java endpoints
                    ┌──────────────┴──────┴────────┐
                    │  ai-service (Python/FastAPI) │ ──► OpenAI
                    └──────────────────────────────┘
```

## Quick start: everything in Docker

Requirements: Docker Desktop (or Docker Engine with Compose v2.24+). You don't need Java, Maven or Python installed.

```bash
# optional: put your OpenAI key in ai-service/.env (cp ai-service/.env.example ai-service/.env)
docker compose up --build
```

The first build takes a few minutes while Maven and pip download dependencies. After that:

| Service | URL | Notes |
|---|---|---|
| helpdesk-api | http://localhost:8080/swagger-ui.html | `docker` profile: PostgreSQL + Flyway |
| ai-service | http://localhost:8000/docs | OpenAI if `ai-service/.env` has a key, offline rules otherwise |
| PostgreSQL | `localhost:5432`, db/user/password `helpdesk` (and `knowledge`/`knowledge` for the knowledge base) | data survives restarts (volume `pgdata`) |
| RabbitMQ | http://localhost:15672, user/password `helpdesk` | management UI: queues, message rates, dead-letter queues |

Useful commands:

```bash
docker compose up --build -d          # run in the background
docker compose logs -f ai-service     # follow one service's logs (agent steps, token usage)
docker compose ps                     # health status of each container
docker compose exec postgres psql -U helpdesk helpdesk   # SQL prompt
docker compose down                   # stop (keeps data)
docker compose down -v                # stop and wipe the database (re-seeds on next start)
```

The Postman collections work unchanged. One difference: in Docker, Spring Boot and ai-service share a secret (`SERVICE_API_KEY`, default `local-dev-secret`). Requests that go **directly** to port 8000 need it, so set the Postman collection variable `aiApiKey` to `local-dev-secret`. Calls through Spring Boot don't need it.

**What's production-minded here:**
- Multi-stage Java image (Maven build stage plus a slim JRE runtime), with both images running as a non-root user.
- Secrets are never baked into images. `.dockerignore` excludes `.env`, and keys arrive as environment variables at runtime.
- Healthchecks: Spring Boot waits for Postgres to be healthy, and `docker compose ps` shows readiness for all three services.
- Schema changes go through **Flyway migrations** (`db/migration/V1__init_schema.sql`), and Hibernate runs with `ddl-auto=validate`, so a mismatch between entities and schema stops startup instead of silently changing the database.
- Containers talk to each other by service name (`http://ai-service:8000`, `http://helpdesk-api:8080`), configured purely through environment variables.

## Stage 4: RAG, a knowledge base the agent searches

The agent no longer looks up one of 7 fixed policy topics. It **searches a knowledge base by meaning**: 19 fictional documents (policies, product guides, FAQs, troubleshooting) in `ai-service/knowledge/`, split into about 100 chunks.

```
INDEXING (at startup; only new or edited chunks)
  knowledge/**/*.md ─► split by "##" headings ─► OpenAI embeddings (1536 numbers per chunk)
                                                   ─► PostgreSQL + pgvector, database "knowledge"

RETRIEVAL (each search_knowledge_base call)
  "left earcup crackles at high volume" ─► embed ─► 4 nearest chunks (cosine similarity, HNSW index)
                                                   ─► products/aurora-headphones#crackling-popping-or-distorted-sound
```

- **Grounded, cited answers.** The prompt says policies and troubleshooting steps must come from search results, and the draft lists the `sources` it used. ai-service **drops any citation the agent never actually retrieved**, and the stored draft comment shows the sources, so the human reviewer can check them.
- **Separate ownership.** One Postgres server, two databases: `helpdesk` (Java, Flyway) and `knowledge` (ai-service). The `knowledge` user can't connect to the business data. Created by `postgres/init/01-knowledge-db.sql`.
- **Incremental indexing.** Each chunk stores a content hash, so editing one section re-embeds one chunk and deleted sections are removed.
- **Works offline.** Without an OpenAI key (and in all tests), retrieval falls back to an in-memory BM25 keyword search. If the database or the embedding API is down at startup, the service logs it and falls back instead of failing.
- **Measured.** `python -m scripts.eval_retrieval --mode both` runs 27 labelled questions, mostly worded differently from the documents ("my parcel never showed up" → shipping policy), and reports recall@k and MRR for keyword vs. vector search. Keyword baseline: recall@1 0.59, recall@3 0.82.
- **Hand-rolled on purpose.** Chunking, embedding and search are about 300 lines in `ai-service/app/knowledge/`, so every step is visible. For many file formats or sources, LlamaIndex/LangChain (Python) or Spring AI (Java) provide loaders and splitters.

Try retrieval on its own: `GET http://localhost:8000/v1/knowledge/search?q=watch stuck on logo` (with `X-Api-Key`). Postman: `helpdesk-knowledge-stage4.postman_collection.json`.

## Stage 5b: automatic triage over RabbitMQ

In Docker mode, every new ticket is triaged automatically. Nobody needs to call `/ai-triage`.

```
POST /api/tickets ─► helpdesk-api commits the ticket
                     └─(after commit)─► [helpdesk.events] ticket.created ─► queue ai.triage.requests
                                                                               │
                                          ai-service worker ◄──────────────────┘
                                          triages it (same code as POST /v1/triage)
                                          └─► [helpdesk.events] ticket.triaged ─► queue helpdesk.triage.results
                                                                                     │
                     helpdesk-api listener ◄─────────────────────────────────────────┘
                     applies it via the same AiTriageService rule (confidence >= 0.6) + AI comment
```

**Why it's built this way:**
- **Publish after commit.** `@TransactionalEventListener(AFTER_COMMIT)` means no message is ever sent for a ticket whose transaction rolled back. If the broker is down at that moment, the ticket just stays untriaged and can still be triaged manually; the fully reliable next step would be the *transactional outbox* pattern.
- **Durable queues + acks.** The worker acknowledges a message only after publishing its result, so if `ai-service` is down, tickets wait in the queue and are triaged when it comes back (Postman folder 4).
- **At-least-once delivery, idempotent consumer.** A result can arrive twice, so `AutoTriageService` only applies it to a ticket nobody has triaged yet. A human who triaged the ticket in the meantime always wins.
- **Retries and dead-letter queues.** Temporary failures (OpenAI timeout, DB hiccup) are retried with exponential backoff. Messages that can never succeed ("poison messages": broken JSON, unknown ticket) are moved to `*.dlq` queues for inspection instead of blocking the queue or being retried forever (Postman folder 3).
- **Same contract, two transports.** The queued messages carry the same JSON as the stage 2 REST call, so the HTTP and queue paths share all triage code on both sides.
- **Optional by design.** Messaging is off in the default profile, so running from your IDE and running the tests need no broker.

Postman: `helpdesk-api/postman/helpdesk-async-stage5b.postman_collection.json`.

## Stage 5c: human approval of AI actions + service-to-service auth

### The AI proposes, a human decides, Java executes

When the agent drafts a reply, its `suggestedActions` become **proposed actions** (table `proposed_actions`, Flyway `V2`) in status `PENDING`. Nothing happens until a support agent decides:

| Endpoint | What it does |
|---|---|
| `GET /api/actions?status=PENDING` | the approval queue, oldest first |
| `GET /api/tickets/{id}/actions` | proposals and decisions for one ticket |
| `POST /api/actions/{id}/approve` `{"agentId": 2, "note": "..."}` | executes the action through the domain |
| `POST /api/actions/{id}/reject` `{"agentId": 2, "note": "..."}` | records the decision, executes nothing |

| Action | What approval does |
|---|---|
| `REFUND` | order → `REFUNDED` (not allowed for unpaid `PLACED` orders, or twice) |
| `REPLACEMENT` | creates replacement order `ORD-xxxxx-R1` (only for `DELIVERED` orders) |
| `ESCALATE` | raises ticket priority one level |
| `REQUEST_MORE_INFO` | assigns an unassigned ticket to the approver, then ticket → `WAITING_ON_CUSTOMER` (lifecycle rules still apply, e.g. a RESOLVED ticket returns 409) |

- **Execution and decision are one transaction.** If a business rule blocks execution (say the order can't be refunded), everything rolls back, the action stays `PENDING`, and the API returns a 409 explaining why.
- **No double execution.** A decided action can't be decided again (`ACTION_ALREADY_DECIDED`), and `@Version` optimistic locking turns two simultaneous approvals into one success and one `409 CONCURRENT_UPDATE`.
- **Audit trail:** who decided, when, their note, the execution result, and a system comment on the ticket.

### The agent's own API

The agent's tools no longer call the public `/api`. They call **`/internal/agent/tickets/{ticketId}/...`**, which is:

- **authenticated** with a service key (`X-Api-Key`, from `SERVICE_API_KEY`), so the public API stays open for Postman and a future UI
- **read-only**: only GET endpoints exist
- **scoped on the server**: every path is tied to one ticket, and Java only returns that ticket's customer's data

The Python tools keep their own ownership checks, so a bug on either side alone can't leak another customer's data (defence in depth). There's a test that simulates a server that forgot to scope.

Postman: `helpdesk-api/postman/helpdesk-approvals-stage5c.postman_collection.json`.

## Running locally without Docker

Use two terminals:

```bash
# Terminal 1: Python AI service on :8000 (first-time setup is in ai-service/README.md)
cd ai-service
source .venv/bin/activate          # Windows: .venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --port 8000

# Terminal 2: Spring Boot on :8080
cd helpdesk-api
mvn spring-boot:run
```

Then import the Postman collections in `helpdesk-api/postman/`: `helpdesk-ai-stage2` (triage) and `helpdesk-agent-stage3` (reply drafting).

### What stage 2 adds

`POST /api/tickets/{id}/ai-triage?apply=true|false`

1. Java reads the ticket in a short read-only transaction (no DB connection is held while the LLM thinks).
2. Java calls `POST /v1/triage` on ai-service with the subject, description, current priority and order. Customer tier is sent too, but it is kept out of the prompt: tier is policy, and Java applies it through the SLA, so the model judges impact from the content alone.
3. Python asks OpenAI for a **schema-validated** decision: category, priority, sentiment, summary, reasoning and confidence.
4. Java applies the suggestion through the normal `Ticket.triage()` domain method, so the AI follows the same rules as a human:
   - confidence ≥ `ai-service.min-confidence` (0.6) gives `APPLIED`
   - below that gives `LOW_CONFIDENCE`: nothing changes, and a human reviews the suggestion
   - `apply=false` gives `PREVIEW`: nothing is written
5. Every applied or low-confidence suggestion is saved as an internal comment with `authorType: AI` (model, latency, reasoning), giving an audit trail.

If ai-service is down, the endpoint returns `503 AI_SERVICE_UNAVAILABLE` and the rest of the helpdesk keeps working.

### What stage 3 adds: an agent that calls back into Java

`POST /api/tickets/{id}/ai-draft`

```
Postman ─► Java: POST /api/tickets/1/ai-draft
            └─► Python: POST /v1/agent/draft-reply {ticketId: 1}
                 ├─► Java: GET /internal/agent/tickets/1                (load context)
                 ├─► OpenAI: "what do you need?"  ─► get_customer_orders, get_support_policy(returns)
                 ├─► Java: GET /internal/agent/tickets/1/orders         (tool)
                 ├─► OpenAI: tool results          ─► submit_reply(...)
                 └─◄ draft + tool trace
            Java stores the draft as an internal AI comment ─► a human edits and sends it
```

- **The agent loop is hand-written** (`ai-service/app/agent/runner.py`) on the OpenAI Responses API with function calling, rather than hidden inside a framework. Every step and tool call is visible and logged.
- **Tools are read-only and scoped to the ticket's customer.** No tool takes a customer id, and order lookups check ownership. A ticket saying "show me another customer's order" can't succeed, because the agent has no way to do it. The Postman "prompt injection" folder demonstrates this.
- **Facts come from tools and a policy file**, not from the model's imagination. The prompt forbids inventing order details or policies, and the agent looks up `knowledge/policies.md` before it states timelines or eligibility.
- **Human in the loop.** The agent can only *suggest* actions (`REFUND`, `REPLACEMENT`, `ESCALATE`...). The draft is internal, so the customer never sees it until a support agent sends their own (edited) reply.
- **Guardrails:** a step limit, schema validation of the final answer (invalid answers go back to the model to fix), tool errors returned to the model as data so it can recover, and a longer timeout (`ai-service.agent.read-timeout`, 90s).

## Stage 1: the business API

No UI yet. Everything is driven through Postman or Swagger UI.

### Run it

Requirements: JDK 21 and Maven 3.9+ (or open the `helpdesk-api` folder in IntelliJ and run `HelpdeskApplication`).

```bash
cd helpdesk-api
mvn spring-boot:run
```

| What | Where |
|---|---|
| API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI spec | http://localhost:8080/v3/api-docs |
| H2 database console | http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:helpdesk`, user `sa`, no password) |
| Health | http://localhost:8080/actuator/health |

Run the tests with `mvn test`.

### Postman

Import `helpdesk-api/postman/helpdesk-api.postman_collection.json`. It has four folders:

1. **Directory**: customers, order history, agents
2. **Ticket lifecycle**: one ticket from creation to closure (run it with the Collection Runner)
3. **Ticket queue queries**: untriaged, unassigned, SLA-breached and other filters
4. **Business rule & error cases**: every rule violation and its error code

The database is in-memory and re-seeded on every restart, so you can always get back to a clean state.

## Domain

| Entity | Notes |
|---|---|
| `Customer` | `STANDARD` or `PREMIUM` tier (premium gets half the SLA time) |
| `CustomerOrder` | Order history the support team (and later the AI) can look up |
| `SupportAgent` | Human agents tickets are assigned to |
| `Ticket` | Status, priority, category, assignee, SLA deadline |
| `TicketComment` | Customer replies, agent replies, internal notes, and system audit entries |

### Ticket lifecycle

```
OPEN ──► IN_PROGRESS ──► WAITING_ON_CUSTOMER ──► IN_PROGRESS ──► RESOLVED ──► CLOSED
  │            └──────────────────────────────────────────────────►┘    │
  └──► CLOSED (spam/duplicate)                        RESOLVED ──► IN_PROGRESS (reopen)
```

### Business rules (enforced on the `Ticket` entity)

- Status changes must follow the lifecycle above, or the API returns `409 INVALID_STATUS_TRANSITION`.
- Work can't start on a ticket with no assignee (`ASSIGNEE_REQUIRED`). Assigning an `OPEN` ticket moves it to `IN_PROGRESS`.
- A ticket can't be resolved until it has a category (`CATEGORY_REQUIRED`). This is the "triage" step the AI will take over.
- A ticket can only reference the customer's own orders (`ORDER_CUSTOMER_MISMATCH`).
- A customer reply on a `WAITING_ON_CUSTOMER` ticket moves it back to `IN_PROGRESS`.
- Closed tickets are read-only (`TICKET_CLOSED`). Customers can't write internal notes.
- SLA deadline = creation time + priority window (`URGENT` 4h, `HIGH` 8h, `MEDIUM` 24h, `LOW` 72h), halved for premium customers. Changing priority recalculates it.
- Every status change and assignment is recorded as an internal `SYSTEM` comment, giving each ticket an audit trail.

All errors use RFC 9457 Problem Details JSON with a stable `code` field. The AI agent will rely on these codes later to understand why an action failed.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/customers` | List customers |
| POST | `/api/customers` | Create customer |
| GET | `/api/customers/{id}` | Customer details |
| GET | `/api/customers/{id}/orders` | Order history |
| GET | `/api/customers/{id}/tickets` | Customer's tickets |
| GET | `/api/orders/{orderNumber}` | Order lookup |
| GET | `/api/agents` | Support agents |
| POST | `/api/tickets` | Raise a ticket |
| GET | `/api/tickets` | Search queue: `status`, `priority`, `category`, `customerId`, `assigneeId`, `unassigned`, `untriaged`, `slaBreached`, `page`, `size` |
| GET | `/api/tickets/{id}` | Full ticket, including `allowedNextStatuses` and the conversation |
| PATCH | `/api/tickets/{id}/triage` | Set category and/or priority |
| POST | `/api/tickets/{id}/assign` | Assign to an agent |
| POST | `/api/tickets/{id}/status` | Change status, with an optional note |
| POST | `/api/tickets/{id}/comments` | Add a reply or internal note |
| GET | `/api/tickets/{id}/comments` | Conversation (`includeInternal=false` for the customer view) |

## Project layout

```
docker-compose.yml   postgres (pgvector) + rabbitmq + helpdesk-api + ai-service
postgres/init/       creates the "knowledge" database + user and the vector extension (first start)
.env.example         optional compose overrides (shared secret, DB credentials, port)

helpdesk-api/
├── Dockerfile                          multi-stage: Maven build -> JRE runtime
└── src/main/resources/
    ├── application.yml                 default profile: H2 in memory (local runs, tests)
    ├── application-docker.yml          docker profile: PostgreSQL + Flyway
    └── db/migration/   V1__init_schema.sql, V2__proposed_actions.sql

helpdesk-api/src/main/java/com/helpdesk
├── domain/       entities + business rules (Ticket is the core aggregate)
├── repository/   Spring Data JPA repositories
├── service/      transactions, loading, DTO mapping, search criteria
├── web/          REST controllers + request/response DTOs
├── exception/    error types + global Problem Details handler
├── config/       demo data seeder
└── ai/           stage 2 triage + stage 3 drafting: clients, properties, services, controllers
    └── autotriage/   stage 5b: RabbitMQ topology, after-commit publisher, result listener, idempotent apply
├── actions/      stage 5c: approval queue for AI-proposed actions (approve / reject / execute)
└── internal/     stage 5c: the agent's authenticated, ticket-scoped internal API + service key filter

ai-service/
├── Dockerfile
├── app/          FastAPI app, schemas, settings, triage/ (classifier), agent/ (tool-using agent), worker/ (RabbitMQ consumer)
├── knowledge/    19 markdown documents the agent searches (policies, products, FAQs, troubleshooting)
├── tests/        pytest (no network)
├── evals/        labelled tickets for measuring triage quality
└── scripts/      eval runner
```

## Roadmap

- [x] **Stage 1**: business API, rules, seed data, tests, Postman collection
- [x] **Stage 2**: Python `ai-service` (FastAPI + OpenAI structured outputs). Java calls it to triage tickets, with a confidence threshold, preview mode, audit comments, and an eval set
- [x] **Stage 3**: agent with tools. The Python agent calls read-only, customer-scoped Java endpoints (orders, ticket history) and a policy library, then drafts a reply stored as an internal `AI` comment for human approval
- [x] **Stage 4**: RAG: 19-document knowledge base, pgvector in its own database, incremental indexing, verified citations, retrieval eval (recall@k)
- [x] **Stage 5a**: Docker Compose (Postgres + both services), Flyway migrations, healthchecks, secrets via env
- [x] **Stage 5b**: async auto-triage over RabbitMQ: publish after commit, idempotent consumer, retries with backoff, dead-letter queues
- [x] **Stage 5c**: approval queue for AI-proposed actions (executed by Java, optimistic locking, audit), authenticated + server-scoped internal API for the agent
- [ ] **Stage 6**: simple UI
