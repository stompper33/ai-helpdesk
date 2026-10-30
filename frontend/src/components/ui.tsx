// Small presentational pieces shared by the pages.

import type { ReactNode } from "react";
import type { TicketCategory, TicketPriority, TicketStatus } from "../api/types";
import { ApiError } from "../api/client";
import { categoryLabel, priorityLabel, sla, statusLabel } from "../lib/format";

export function StatusPill({ status }: { status: TicketStatus }) {
  return <span className={`pill pill-${status.toLowerCase()}`}>{statusLabel[status]}</span>;
}

export function PriorityMark({ priority }: { priority: TicketPriority }) {
  return (
    <span className={`priority priority-${priority.toLowerCase()}`}>
      <span className="priority-dot" aria-hidden="true" />
      {priorityLabel[priority]}
    </span>
  );
}

export function CategoryText({ category }: { category: TicketCategory | null }) {
  if (!category) return <span className="untriaged">Not triaged</span>;
  return <span>{categoryLabel[category]}</span>;
}

export function SlaClock({ dueAt, status }: { dueAt: string; status: TicketStatus }) {
  const state = sla(dueAt, status);
  return <span className={`sla sla-${state.tone}`}>{state.text}</span>;
}

export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  const e = error as Error;
  const code = e instanceof ApiError ? e.code : undefined;
  return (
    <div className="notice notice-error" role="alert">
      <div>
        <strong>{headline(e)}</strong>
        <p>{e.message}</p>
        {code && <p className="notice-code">Code: <code>{code}</code></p>}
      </div>
      {onRetry && <button className="button button-quiet" onClick={onRetry}>Try again</button>}
    </div>
  );
}

function headline(e: Error): string {
  if (!(e instanceof ApiError)) return "Something went wrong";
  if (e.status === 0) return "No connection";
  if (e.status === 409) return "Not allowed right now";
  if (e.status === 503) return "The AI service is unavailable";
  if (e.status === 404) return "Not found";
  if (e.status === 400) return "Check the form";
  return "The request failed";
}

export function Spinner({ label }: { label: string }) {
  return (
    <span className="spinner" role="status">
      <span className="spinner-ring" aria-hidden="true" />
      {label}
    </span>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <div className="empty-body">{children}</div>}
    </div>
  );
}
