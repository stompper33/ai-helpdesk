// The ticket's conversation. Each kind of author looks different:
// customers and agents as messages, internal notes tinted, AI output on provisional amber,
// and system audit events as single quiet lines.

import type { Comment, TicketDetail } from "../api/types";
import { parseAiComment } from "../lib/aiComments";
import type { ActionType } from "../api/types";
import { actionLabel, formatDateTime } from "../lib/format";
import { DraftProof } from "./DraftProof";

export function Thread({ ticket, onUseDraft }: { ticket: TicketDetail; onUseDraft: (reply: string) => void }) {
  return (
    <ol className="thread">
      <li className="msg msg-customer">
        <MessageHead name={ticket.customer.name} role="Customer" at={ticket.createdAt} />
        <div className="msg-body">{ticket.description}</div>
      </li>
      {ticket.comments.map((c) => <Entry key={c.id} comment={c} onUseDraft={onUseDraft} />)}
    </ol>
  );
}

function Entry({ comment: c, onUseDraft }: { comment: Comment; onUseDraft: (reply: string) => void }) {
  if (c.authorType === "SYSTEM") {
    return (
      <li className="event">
        <span>{c.body}</span>
        <time dateTime={c.createdAt}>{formatDateTime(c.createdAt)}</time>
      </li>
    );
  }

  if (c.authorType === "AI") {
    const parsed = parseAiComment(c.body);
    if (parsed?.kind === "draft") {
      return (
        <li className="msg msg-ai" id={`comment-${c.id}`} tabIndex={-1}>
          <MessageHead name={c.authorName} role="AI, internal" at={c.createdAt} />
          <DraftProof reply={parsed.reply} sources={parsed.sources} notes={parsed.notes}
                      suggestedActions={suggested(parsed.suggestedActions)} lookups={parsed.lookups}
                      meta={parsed.meta.replace(/^\(|\)$/g, "")} onUse={onUseDraft} />
        </li>
      );
    }
    return (
      <li className="msg msg-ai">
        <MessageHead name={c.authorName} role="AI, internal" at={c.createdAt} />
        {parsed?.kind === "triage" ? (
          <div className="ai-note">
            <p className="ai-note-head">
              {parsed.applied ? "Triaged" : "Triage suggestion, not applied"}: {parsed.headline}
            </p>
            {parsed.summary && <p>{parsed.summary}</p>}
            {parsed.reasoning && <p className="muted">{parsed.reasoning}</p>}
          </div>
        ) : (
          <div className="ai-note"><p className="pre">{c.body}</p></div>
        )}
      </li>
    );
  }

  const role = c.authorType === "CUSTOMER" ? "Customer" : c.internal ? "Internal note" : "Agent reply";
  const kind = c.authorType === "CUSTOMER" ? "msg-customer" : c.internal ? "msg-internal" : "msg-agent";
  return (
    <li className={`msg ${kind}`}>
      <MessageHead name={c.authorName} role={role} at={c.createdAt} />
      <div className="msg-body">{c.body}</div>
    </li>
  );
}

function MessageHead({ name, role, at }: { name: string; role: string; at: string }) {
  return (
    <div className="msg-head">
      <span className="msg-name">{name}</span>
      <span className="msg-role">{role}</span>
      <time dateTime={at}>{formatDateTime(at)}</time>
    </div>
  );
}

// "REFUND, REQUEST_MORE_INFO" -> ["Refund the order", "Ask the customer for details"]
function suggested(text: string): string[] {
  if (!text || text === "none") return [];
  return text.split(",").map((s) => s.trim()).map((s) => actionLabel[s as ActionType] ?? s);
}
