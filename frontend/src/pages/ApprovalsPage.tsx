import { useState } from "react";
import { api } from "../api/client";
import type { ActionStatus, ProposedAction } from "../api/types";
import { ActionItem } from "../components/ActionItem";
import { Empty, ErrorNotice, Spinner } from "../components/ui";
import { useLoad } from "../lib/useLoad";

const TABS: { id: ActionStatus; label: string }[] = [
  { id: "PENDING", label: "Waiting" },
  { id: "EXECUTED", label: "Approved" },
  { id: "REJECTED", label: "Rejected" },
];

export function ApprovalsPage() {
  const [status, setStatus] = useState<ActionStatus>("PENDING");
  const actions = useLoad(() => api.listActions(status), [status]);
  // ticket subjects for context (one request instead of one per action)
  const tickets = useLoad(() => api.listTickets({ size: 100 }), []);
  const subjects = new Map((tickets.data?.content ?? []).map((t) => [t.id, t.subject]));

  const onDecided = (updated: ProposedAction) => {
    // keep the decided card visible in place, showing the outcome, until the next refresh
    actions.setData((actions.data ?? []).map((a) => (a.id === updated.id ? updated : a)));
  };

  return (
    <main className="page page-narrow">
      <div className="page-head">
        <div>
          <h1>Approvals</h1>
          <p className="page-sub">
            Actions the AI suggested while drafting replies. Nothing happens until you approve; the helpdesk then
            carries out the action and records who approved it.
          </p>
        </div>
      </div>

      <div className="tabs" role="tablist" aria-label="Approval status">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={status === t.id}
                  className={status === t.id ? "tab tab-active" : "tab"} onClick={() => setStatus(t.id)}>
            {t.label}
          </button>
        ))}
      </div>

      <ErrorNotice error={actions.error} onRetry={() => void actions.reload()} />
      {actions.loading && !actions.data ? (
        <div className="panel panel-pad"><Spinner label="Loading" /></div>
      ) : (actions.data ?? []).length === 0 ? (
        <div className="panel">
          <Empty title={status === "PENDING" ? "Nothing waiting for approval" : "Nothing here yet"}>
            {status === "PENDING" && "Open a ticket and draft a reply with AI; any action it suggests appears here."}
          </Empty>
        </div>
      ) : (
        <div className="stack">
          {(actions.data ?? []).map((a) => (
            <ActionItem key={a.id} action={a} ticketSubject={subjects.get(a.ticketId)} onDecided={onDecided} />
          ))}
        </div>
      )}
    </main>
  );
}
