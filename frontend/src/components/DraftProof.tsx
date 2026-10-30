// The AI's draft reply, shown as a "proof": provisional amber paper, serif letter text, and the
// knowledge base passages it relied on listed in the margin. Nothing here reaches the customer
// until a support agent uses the draft and sends their own reply.

import { useState } from "react";
import type { Lookup } from "../lib/aiComments";
import { describeSource } from "../lib/format";

interface Props {
  reply: string;
  sources: string[];
  notes?: string;
  suggestedActions?: string[];
  lookups?: Lookup[];
  meta?: string;
  onUse?: (reply: string) => void;
}

export function DraftProof({ reply, sources, notes, suggestedActions, lookups, meta, onUse }: Props) {
  const [showTrace, setShowTrace] = useState(false);
  const searches = lookups?.filter((t) => t.name !== "submit_reply") ?? [];

  return (
    <section className="proof" aria-label="AI draft reply">
      <div className="proof-top">
        <span className="proof-tag">AI draft, not sent</span>
        {meta && <span className="proof-meta">{meta}</span>}
      </div>

      <div className="proof-body">
        <div className="proof-letter">
          {reply.split(/\n{2,}/).map((p, i) => <p key={i}>{p}</p>)}
        </div>

        <aside className="proof-margin" aria-label="Sources">
          <p className="margin-title">Based on</p>
          {sources.length === 0 ? (
            <p className="margin-empty">No knowledge base sources. Check facts before sending.</p>
          ) : (
            <ol className="sources">
              {sources.map((s) => {
                const d = describeSource(s);
                return (
                  <li key={s} title={s}>
                    <span className="source-doc">{d.document}</span>
                    {d.section && <span className="source-section">{d.section}</span>}
                  </li>
                );
              })}
            </ol>
          )}
        </aside>
      </div>

      {(notes || (suggestedActions && suggestedActions.length > 0)) && (
        <dl className="proof-notes">
          {notes && (<><dt>Notes for you</dt><dd>{notes}</dd></>)}
          {suggestedActions && suggestedActions.length > 0 && (
            <><dt>Suggested</dt><dd>{suggestedActions.join(", ")}</dd></>
          )}
        </dl>
      )}

      <div className="proof-actions">
        {onUse && <button className="button button-primary" onClick={() => onUse(reply)}>Use this draft</button>}
        {searches.length > 0 && (
          <button className="button button-quiet" aria-expanded={showTrace} onClick={() => setShowTrace(!showTrace)}>
            {showTrace ? "Hide what the AI looked up" : `See what the AI looked up (${searches.length})`}
          </button>
        )}
      </div>

      {showTrace && (
        <ol className="trace">
          {searches.map((t, i) => (
            <li key={i} className={t.ok ? undefined : "trace-failed"}>
              <span className="mono">{t.name}</span>
              <span className="trace-args" title={t.args}>{t.args}</span>
              <span className="trace-time">{!t.ok ? "failed" : t.durationMs !== undefined ? `${t.durationMs} ms` : ""}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
