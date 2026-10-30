import { useEffect, useRef, useState, type FormEvent, type ReactNode, type RefObject } from "react";
import { api } from "../api/client";
import type { AiDraftResponse, ProposedAction, TicketCategory, TicketDetail, TicketPriority, TicketStatus } from "../api/types";
import { CATEGORIES, PRIORITIES } from "../api/types";
import { ActionItem } from "../components/ActionItem";
import { Thread } from "../components/Thread";
import { CategoryText, ErrorNotice, PriorityMark, SlaClock, Spinner, StatusPill } from "../components/ui";
import { useActingAgent } from "../lib/agent";
import { categoryLabel, formatDateTime, money, priorityLabel, statusLabel } from "../lib/format";
import { href } from "../lib/router";
import { useLoad } from "../lib/useLoad";

export function TicketPage({ id }: { id: number }) {
  const ticket = useLoad(() => api.getTicket(id), [id]);
  const actions = useLoad(() => api.ticketActions(id), [id]);
  const [replyText, setReplyText] = useState("");
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const waitingForAutoTriage = useAutoTriageWait(ticket.data, ticket.reload);

  const applyDraft = (text: string) => {
    setReplyText(text);
    composerRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    composerRef.current?.focus({ preventScroll: true });
  };

  const refresh = async (updated?: TicketDetail) => {
    if (updated) ticket.setData(updated);
    else await ticket.reload();
    await actions.reload();
  };

  if (ticket.loading && !ticket.data) {
    return <main className="page"><div className="panel panel-pad"><Spinner label="Loading ticket" /></div></main>;
  }
  if (!ticket.data) {
    return <main className="page"><a className="back" href={href.queue()}>Back to tickets</a>
      <ErrorNotice error={ticket.error} onRetry={() => void ticket.reload()} /></main>;
  }

  const t = ticket.data;
  const closed = t.status === "CLOSED";
  return (
    <main className="page">
      <a className="back" href={href.queue()}>Back to tickets</a>

      <div className="ticket-head">
        <h1>{t.subject}</h1>
        <div className="ticket-facts">
          <StatusPill status={t.status} />
          <PriorityMark priority={t.priority} />
          <CategoryText category={t.category} />
          <SlaClock dueAt={t.slaDueAt} status={t.status} />
          <span className="mono muted">#{t.id}</span>
        </div>
      </div>

      <div className="ticket-layout">
        <section className="ticket-main" aria-label="Conversation">
          <Thread ticket={t} onUseDraft={applyDraft} />
          {!closed && (
            <ReplyComposer ticket={t} text={replyText} setText={setReplyText} textareaRef={composerRef}
                           onSent={() => void refresh()} />
          )}
        </section>

        <aside className="workbench" aria-label="Ticket tools">
          <CustomerCard ticket={t} />
          <WorkflowCard ticket={t} onChanged={refresh} />
          <TriageCard ticket={t} waiting={waitingForAutoTriage} onChanged={refresh} />
          {!closed && <DraftCard ticket={t} onUse={applyDraft} onDrafted={(r) => void refresh(r.ticket)} />}
          <ActionsCard actions={actions.data ?? []} onDecided={() => void refresh()} />
        </aside>
      </div>
    </main>
  );
}

/** A brand-new untriaged ticket is triaged by the RabbitMQ worker within seconds: poll briefly to show it. */
function useAutoTriageWait(ticket: TicketDetail | undefined, reload: () => Promise<void>): boolean {
  const fresh = !!ticket && !ticket.category && Date.now() - new Date(ticket.createdAt).getTime() < 120_000;
  const [polls, setPolls] = useState(0);
  useEffect(() => {
    if (!fresh || polls >= 10) return;
    const timer = window.setTimeout(() => {
      setPolls((n) => n + 1);
      void reload();
    }, 3000);
    return () => window.clearTimeout(timer);
  }, [fresh, polls, reload]);
  return fresh && polls < 10;
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card">
      <h2>{title}</h2>
      {children}
    </section>
  );
}

function CustomerCard({ ticket: t }: { ticket: TicketDetail }) {
  return (
    <Card title="Customer">
      <p className="strong">{t.customer.name}{t.customer.tier === "PREMIUM" && <span className="tier">Premium</span>}</p>
      <p className="muted">{t.customer.email}</p>
      {t.order ? (
        <dl className="facts">
          <dt>Order</dt><dd className="mono">{t.order.orderNumber}</dd>
          <dt>Product</dt><dd>{t.order.productName}</dd>
          <dt>Amount</dt><dd>{money(t.order.amount)}</dd>
          <dt>Order status</dt><dd>{t.order.status.charAt(0) + t.order.status.slice(1).toLowerCase()}</dd>
          {t.order.trackingNumber && (<><dt>Tracking</dt><dd className="mono">{t.order.trackingNumber}</dd></>)}
        </dl>
      ) : (
        <p className="muted small">No order linked to this ticket.</p>
      )}
    </Card>
  );
}

function WorkflowCard({ ticket: t, onChanged }: { ticket: TicketDetail; onChanged: (t?: TicketDetail) => Promise<void> }) {
  const { current } = useActingAgent();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();

  const run = async (call: () => Promise<TicketDetail>) => {
    setBusy(true);
    setError(undefined);
    try {
      await onChanged(await call());
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const mine = t.assignee && current && t.assignee.id === current.id;
  return (
    <Card title="Workflow">
      <dl className="facts">
        <dt>Assignee</dt><dd>{t.assignee ? t.assignee.name : <span className="muted">Nobody</span>}</dd>
        <dt>SLA due</dt><dd>{formatDateTime(t.slaDueAt)}</dd>
      </dl>
      {t.status !== "CLOSED" && (
        <div className="stack">
          {!mine && current && (
            <button className="button" disabled={busy} onClick={() => void run(() => api.assign(t.id, current.id))}>
              Assign to me
            </button>
          )}
          {t.allowedNextStatuses.length > 0 && (
            <label className="field">
              <span>Move to</span>
              <select value="" disabled={busy}
                      onChange={(e) => e.target.value && void run(() => api.changeStatus(t.id, e.target.value as TicketStatus))}>
                <option value="">Choose a status</option>
                {t.allowedNextStatuses.map((s) => <option key={s} value={s}>{statusLabel[s]}</option>)}
              </select>
            </label>
          )}
        </div>
      )}
      <ErrorNotice error={error} />
    </Card>
  );
}

function TriageCard({ ticket: t, waiting, onChanged }:
  { ticket: TicketDetail; waiting: boolean; onChanged: (t?: TicketDetail) => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [outcome, setOutcome] = useState<string>();

  const aiTriage = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.aiTriage(t.id);
      setOutcome(r.outcome === "APPLIED"
        ? `Applied with ${Math.round(r.suggestion.confidence * 100)}% confidence.`
        : `Not applied: confidence ${Math.round(r.suggestion.confidence * 100)}% is below the threshold. See the note in the conversation.`);
      await onChanged(r.ticket);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const setManually = async (body: { category?: TicketCategory; priority?: TicketPriority }) => {
    setError(undefined);
    try {
      await onChanged(await api.triage(t.id, body));
    } catch (e) {
      setError(e);
    }
  };

  const closed = t.status === "CLOSED";
  return (
    <Card title="Triage">
      {waiting && <p className="waiting"><Spinner label="Waiting for automatic triage" /></p>}
      <div className="field-row">
        <label className="field">
          <span>Category</span>
          <select value={t.category ?? ""} disabled={closed}
                  onChange={(e) => void setManually({ category: e.target.value as TicketCategory })}>
            {!t.category && <option value="">Not triaged</option>}
            {CATEGORIES.map((c) => <option key={c} value={c}>{categoryLabel[c]}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Priority</span>
          <select value={t.priority} disabled={closed}
                  onChange={(e) => void setManually({ priority: e.target.value as TicketPriority })}>
            {PRIORITIES.map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
          </select>
        </label>
      </div>
      {!closed && (
        <button className="button" disabled={busy} onClick={() => void aiTriage()}>
          {busy ? <Spinner label="Asking the AI" /> : "Triage with AI"}
        </button>
      )}
      {outcome && <p className="small">{outcome}</p>}
      <ErrorNotice error={error} />
    </Card>
  );
}

function DraftCard({ ticket: t, onUse, onDrafted }:
  { ticket: TicketDetail; onUse: (reply: string) => void; onDrafted: (r: AiDraftResponse) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [result, setResult] = useState<AiDraftResponse>();

  const draft = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.aiDraft(t.id);
      setResult(r);
      onDrafted(r);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Reply draft">
      <p className="small muted">
        The AI checks the customer's orders and the knowledge base, then drafts a reply for you to edit.
        It's saved as an internal note; the customer sees nothing until you send a reply.
      </p>
      <button className="button button-primary" disabled={busy} onClick={() => void draft()}>
        {busy ? <Spinner label="Drafting, usually 5-20 seconds" /> : result ? "Draft again" : "Draft reply with AI"}
      </button>
      <ErrorNotice error={error} />
      {result && <DraftReady result={result} onUse={onUse} />}
    </Card>
  );
}

// The full draft lives in the conversation (it is saved there as an internal note), so the
// workbench only confirms it arrived and links to it, instead of showing the same letter twice.
function DraftReady({ result, onUse }: { result: AiDraftResponse; onUse: (reply: string) => void }) {
  const d = result.draft;
  const newest = [...result.ticket.comments].reverse().find((c) => c.authorType === "AI");
  const lookups = (d.toolCalls ?? []).filter((c) => c.name !== "submit_reply").length;
  const goTo = () => {
    const el = newest && document.getElementById(`comment-${newest.id}`);
    el?.scrollIntoView({ behavior: "smooth", block: "start" });
    el?.focus({ preventScroll: true });
  };
  return (
    <div className="draft-ready">
      <p className="strong">Draft added to the conversation</p>
      <p className="small">
        {Math.round(d.confidence * 100)}% confident, {(d.latencyMs / 1000).toFixed(1)} s,{" "}
        {d.sources?.length ? `${d.sources.length} knowledge base source${d.sources.length > 1 ? "s" : ""}` : "no knowledge base sources"},{" "}
        {lookups} lookup{lookups === 1 ? "" : "s"}
        {result.proposedActions.length > 0 && `, ${result.proposedActions.length} action${result.proposedActions.length > 1 ? "s" : ""} to approve`}.
      </p>
      <div className="proof-actions">
        <button className="button" onClick={goTo}>Read the draft</button>
        <button className="button button-quiet" onClick={() => onUse(d.customerReply)}>Use it in my reply</button>
      </div>
    </div>
  );
}

function ActionsCard({ actions, onDecided }: { actions: ProposedAction[]; onDecided: (a: ProposedAction) => void }) {
  if (actions.length === 0) return null;
  const pending = actions.filter((a) => a.status === "PENDING");
  return (
    <Card title={pending.length ? `Proposed actions (${pending.length} waiting)` : "Proposed actions"}>
      <div className="stack">
        {actions.map((a) => <ActionItem key={a.id} action={a} onDecided={onDecided} />)}
      </div>
    </Card>
  );
}

function ReplyComposer({ ticket, text, setText, textareaRef, onSent }: {
  ticket: TicketDetail; text: string; setText: (s: string) => void;
  textareaRef: RefObject<HTMLTextAreaElement | null>; onSent: () => void;
}) {
  const { current } = useActingAgent();
  const [internal, setInternal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();

  const send = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!current || !text.trim()) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.addComment(ticket.id, { authorType: "AGENT", authorName: current.name, body: text.trim(), internal });
      setText("");
      setInternal(false);
      onSent();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="composer" onSubmit={(e) => void send(e)}>
      <label htmlFor="reply" className="composer-label">
        {internal ? "Internal note (only support staff see this)" : `Reply to ${ticket.customer.name.split(" ")[0]}`}
      </label>
      <textarea id="reply" ref={textareaRef} value={text} onChange={(e) => setText(e.target.value)} rows={6}
                placeholder="Write a reply, or use an AI draft and edit it here." maxLength={4000} />
      <ErrorNotice error={error} />
      <div className="composer-bar">
        <label className="check">
          <input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} />
          Internal note
        </label>
        <button className="button button-primary" disabled={busy || !text.trim() || !current}>
          {busy ? "Sending…" : internal ? "Add note" : "Send reply"}
        </button>
      </div>
    </form>
  );
}
