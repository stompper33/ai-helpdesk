// One AI-proposed action with Approve / Reject. Approval makes Java execute it; if a business
// rule blocks it (e.g. the order can't be refunded), the API's explanation is shown and the
// action stays pending, so it can be rejected instead.

import { useState } from "react";
import { api } from "../api/client";
import type { ActionType, ProposedAction } from "../api/types";
import { useActingAgent } from "../lib/agent";
import { actionLabel, formatDateTime } from "../lib/format";
import { href } from "../lib/router";
import { ErrorNotice } from "./ui";

interface Props {
  action: ProposedAction;
  ticketSubject?: string;
  onDecided: (updated: ProposedAction) => void;
}

const EFFECT: Record<ActionType, string> = {
  REFUND: "Marks the order as refunded.",
  REPLACEMENT: "Creates a replacement order.",
  ESCALATE: "Raises the ticket priority by one level.",
  REQUEST_MORE_INFO: "Assigns the ticket to you if unassigned and waits for the customer.",
};

export function ActionItem({ action, ticketSubject, onDecided }: Props) {
  const { current } = useActingAgent();
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState<"approve" | "reject" | null>(null);
  const [error, setError] = useState<unknown>();

  const decide = async (decision: "approve" | "reject") => {
    if (!current) return;
    setBusy(decision);
    setError(undefined);
    try {
      const updated = decision === "approve"
        ? await api.approve(action.id, current.id, note || undefined)
        : await api.reject(action.id, current.id, note || undefined);
      onDecided(updated);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  };

  const pending = action.status === "PENDING";
  return (
    <article className={`action action-${action.status.toLowerCase()}`}>
      <div className="action-head">
        <h3>{actionLabel[action.type]}</h3>
        <span className={`action-status status-${action.status.toLowerCase()}`}>
          {pending ? "Waiting for approval" : action.status === "EXECUTED" ? "Approved" : "Rejected"}
        </span>
      </div>
      {ticketSubject && (
        <p className="action-ticket"><a href={href.ticket(action.ticketId)}>#{action.ticketId} {ticketSubject}</a></p>
      )}
      {pending && <p className="action-effect">{EFFECT[action.type]}</p>}
      {action.reason && <p className="action-reason">{action.reason}</p>}

      {pending ? (
        <>
          <label className="field">
            <span>Note (optional)</span>
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Why you decided" maxLength={1000} />
          </label>
          <ErrorNotice error={error} />
          <div className="action-buttons">
            <button className="button button-primary" disabled={!current || busy !== null} onClick={() => void decide("approve")}>
              {busy === "approve" ? "Approving…" : "Approve"}
            </button>
            <button className="button" disabled={!current || busy !== null} onClick={() => void decide("reject")}>
              {busy === "reject" ? "Rejecting…" : "Reject"}
            </button>
          </div>
        </>
      ) : (
        <p className="action-decision">
          {action.decidedByName ?? "Someone"}, {action.decidedAt ? formatDateTime(action.decidedAt) : ""}
          {action.executionResult && <>: {action.executionResult}</>}
          {action.decisionNote && <span className="muted"> ({action.decisionNote})</span>}
        </p>
      )}
    </article>
  );
}
