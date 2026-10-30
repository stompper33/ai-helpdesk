# frontend: the helpdesk console (stage 6)

A small React + TypeScript app for support agents: work the ticket queue, read a ticket's conversation,
ask the AI for triage and a draft reply, and approve or reject the actions the AI proposes.

It is just another client of `helpdesk-api`. It only calls the public `/api/**` endpoints, the same ones
as the Postman collection, and never calls `ai-service` or `/internal/**`. That keeps the architecture
intact: Java owns the business rules, Python owns the intelligence, and the UI only displays them.

## Run it

You need **Node 20.19+ or 22.12+** (Vite 7 requires it). Check with `node -v`.

**While developing** (hot reload, with helpdesk-api running on port 8080 from IntelliJ or Docker):

```bash
cd frontend
npm install        # first time only; creates node_modules/ and package-lock.json (commit the lock file)
npm run dev        # http://localhost:5173
```

The Vite dev server forwards every `/api` request to `http://localhost:8080` (see `vite.config.ts`), so
the browser sees one origin and the Java app needs no CORS configuration. To point it somewhere else:
`API_URL=http://localhost:9090 npm run dev`.

**With the rest of the stack** (no Node needed on your machine):

```bash
docker compose up --build    # from the repo root, then open http://localhost:3000
```

In Docker, the `frontend` image is built in two stages: Node compiles and bundles the app, then the
static files are copied into an nginx image. nginx serves them and forwards `/api` to `helpdesk-api`
inside the Docker network (see `nginx.conf`), which plays the same role as the Vite proxy above.

Other scripts:

| Command             | What it does                                                   |
|---------------------|----------------------------------------------------------------|
| `npm run typecheck` | Type-check only (`tsc --noEmit`), like a compile without output |
| `npm run build`     | Type-check, then bundle into `dist/` for production            |
| `npm run preview`   | Serve the `dist/` build locally to try it                      |

## What's in it

| Screen     | What you can do                                                                        |
|------------|----------------------------------------------------------------------------------------|
| Tickets    | Queue sorted by priority, then SLA deadline. Views: active, needs triage, unassigned, overdue, assigned to me |
| Ticket     | Conversation (customer, agents, internal notes, AI notes, system events), reply composer, and a side panel for customer/order facts, assignment and status, manual or AI triage, AI draft reply, and proposed actions |
| Approvals  | Every action the AI proposed, waiting or decided; approve or reject with a note        |
| New ticket | Raise a ticket for a customer; in Docker it is auto-triaged over RabbitMQ and the ticket page waits for the result |

"Acting as" in the top bar picks which support agent you are. It is sent as `agentId` on approvals and
used for "Assign to me" and reply signatures. There is no login: authentication is out of scope for this
project, the same as for the Postman collection.

Anything the AI produced is shown on amber paper and labelled as not sent. A draft reply lists the
knowledge base passages it relied on beside the letter, and "See what the AI looked up" shows every
tool call the agent made. Nothing reaches the customer until an agent sends a reply themselves.

## How the code is organised

```
src/
  main.tsx              entry point: mounts <App/> into index.html
  App.tsx               top bar + the page for the current route
  styles.css            all styling: design tokens (colours, type) at the top, then one section per area
  api/
    types.ts            TypeScript mirrors of the Java DTOs (records -> interfaces, enums -> string unions)
    client.ts           the only code that does HTTP; turns Problem Details errors into ApiError
  lib/
    router.ts           tiny hash router: #/, #/tickets/42, #/approvals
    useLoad.ts          hook: load data, track loading/error, reload
    agent.tsx           "acting as" agent, shared through React context
    format.ts           labels, dates, SLA clock, money
    aiComments.ts       turns AI audit comments back into structured data for display
  components/           reusable pieces: Header, Thread, DraftProof, ActionItem, NewTicketDialog, ui.tsx
  pages/                one file per screen: QueuePage, TicketPage, ApprovalsPage
```

There are only two runtime dependencies, `react` and `react-dom`. No router library, state library or
UI kit, so everything that happens is in these files. It's a deliberate choice for a small app and for
learning; a bigger app would likely add React Router and TanStack Query.

### Coming from Java: a few mappings

| Java / Spring                     | Here                                                                    |
|-----------------------------------|-------------------------------------------------------------------------|
| `record TicketSummaryResponse`    | `interface TicketSummary` in `api/types.ts` (types only, gone at runtime) |
| `enum TicketStatus`               | `type TicketStatus = "OPEN" \| "IN_PROGRESS" \| ...` (a string union)   |
| A `RestClient` wrapper class       | `api` object in `api/client.ts`; each method returns a `Promise<T>`    |
| Exception with an error code       | `ApiError` with `status`, `code` and `fieldErrors` from Problem Details |
| `@Component` holding state         | A component function plus `useState`; React re-renders when state changes |
| A request-scoped bean              | React context (`agent.tsx`), available to every component below the provider |
| `mvn compile`                      | `npm run typecheck`                                                    |
| Fat jar                            | `dist/`: static HTML, JS and CSS, served by nginx                       |

Two things worth knowing when reading the components:

- A component is a function that returns markup (JSX). It runs again every time its state or props
  change, so values like `const pending = actions.filter(...)` are simply recomputed; there is nothing to
  keep in sync by hand.
- `useEffect` runs code after rendering, for example loading data when the ticket id changes. In
  development, React's `StrictMode` runs effects twice on purpose to catch bugs, so you'll see some
  requests twice in the browser's network tab. That doesn't happen in the production build.

If the API changes, update `api/types.ts` first. The compiler then points at every place that needs
to change, just like changing a Java record.

## Design notes

- Built for scanning: a dense queue table with a coloured left edge on urgent and high rows, an SLA
  clock that turns orange under 2 hours and red when overdue, and quiet one-line system events.
- One visual idea carries the AI story: provisional material (drafts, triage notes, proposed actions)
  sits on amber, and the drafted letter is set in a serif so it reads as a letter, not interface text.
- Type: IBM Plex Sans for the interface, IBM Plex Serif for draft letters, IBM Plex Mono for order
  numbers and tool names (loaded from Google Fonts; system fonts are used offline).
- Accessible basics: keyboard focus is always visible, the dialog is a native `<dialog>` (focus trap and
  Escape for free), tabs and nav expose their current state, motion respects "reduce motion", and the
  layout works down to phone width.
