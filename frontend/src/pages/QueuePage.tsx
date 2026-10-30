import { useState } from "react";
import { api, type TicketFilters } from "../api/client";
import type { TicketSummary } from "../api/types";
import { CategoryText, Empty, ErrorNotice, PriorityMark, SlaClock, Spinner, StatusPill } from "../components/ui";
import { useActingAgent } from "../lib/agent";
import { timeAgo } from "../lib/format";
import { href, navigate } from "../lib/router";
import { useLoad } from "../lib/useLoad";

type View = "active" | "untriaged" | "unassigned" | "breached" | "mine" | "all";

const VIEWS: { id: View; label: string }[] = [
  { id: "active", label: "Active" },
  { id: "untriaged", label: "Needs triage" },
  { id: "unassigned", label: "Unassigned" },
  { id: "breached", label: "Overdue" },
  { id: "mine", label: "Assigned to me" },
  { id: "all", label: "All" },
];

const ACTIVE = new Set(["OPEN", "IN_PROGRESS", "WAITING_ON_CUSTOMER"]);
const PRIORITY_ORDER = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 } as const;

export function QueuePage() {
  const [view, setView] = useState<View>("active");
  const { current } = useActingAgent();

  const filters: TicketFilters = {
    untriaged: view === "untriaged",
    unassigned: view === "unassigned",
    slaBreached: view === "breached",
    assigneeId: view === "mine" ? current?.id : undefined,
  };
  const tickets = useLoad(() => api.listTickets(filters), [view, current?.id]);

  const rows = sortForWork((tickets.data?.content ?? []).filter((t) => view === "all" || ACTIVE.has(t.status)));

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <h1>Tickets</h1>
          <p className="page-sub">Most urgent first: priority, then the SLA deadline.</p>
        </div>
        <button className="button button-quiet" onClick={() => void tickets.reload()} disabled={tickets.loading}>
          Refresh
        </button>
      </div>

      <div className="tabs" role="tablist" aria-label="Ticket views">
        {VIEWS.map((v) => (
          <button key={v.id} role="tab" aria-selected={view === v.id}
                  className={view === v.id ? "tab tab-active" : "tab"} onClick={() => setView(v.id)}>
            {v.label}
          </button>
        ))}
      </div>

      <ErrorNotice error={tickets.error} onRetry={() => void tickets.reload()} />

      {tickets.loading && !tickets.data ? (
        <div className="panel panel-pad"><Spinner label="Loading tickets" /></div>
      ) : rows.length === 0 && !tickets.error ? (
        <div className="panel"><Empty title="No tickets here">Try another view, or create a ticket.</Empty></div>
      ) : (
        <div className="panel table-wrap">
          <table className="queue">
            <thead>
              <tr>
                <th scope="col">Ticket</th>
                <th scope="col">Customer</th>
                <th scope="col">Priority</th>
                <th scope="col">Category</th>
                <th scope="col">Status</th>
                <th scope="col">Assignee</th>
                <th scope="col" className="align-end">SLA</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.id} className={`row row-${t.priority.toLowerCase()}`}
                    onClick={() => navigate(href.ticket(t.id))}>
                  <td>
                    <a className="row-link" href={href.ticket(t.id)} onClick={(e) => e.stopPropagation()}>
                      {t.subject}
                    </a>
                    <div className="row-meta">
                      <span className="mono">#{t.id}</span>
                      {t.orderNumber && <span className="mono">{t.orderNumber}</span>}
                      <span>{timeAgo(t.createdAt)}</span>
                    </div>
                  </td>
                  <td>{t.customerName}</td>
                  <td><PriorityMark priority={t.priority} /></td>
                  <td><CategoryText category={t.category} /></td>
                  <td><StatusPill status={t.status} /></td>
                  <td className={t.assigneeName ? undefined : "muted"}>{t.assigneeName ?? "Nobody"}</td>
                  <td className="align-end"><SlaClock dueAt={t.slaDueAt} status={t.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

function sortForWork(tickets: TicketSummary[]): TicketSummary[] {
  return [...tickets].sort((a, b) =>
    PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority]
    || new Date(a.slaDueAt).getTime() - new Date(b.slaDueAt).getTime());
}
