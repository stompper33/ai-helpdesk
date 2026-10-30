import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useActingAgent } from "../lib/agent";
import { href, type Route } from "../lib/router";

export function Header({ route, onNewTicket }: { route: Route; onNewTicket: () => void }) {
  const { agents, current, select } = useActingAgent();
  const pending = usePendingCount(route);

  return (
    <header className="header">
      <div className="header-inner">
        <a className="brand" href={href.queue()}>
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 32 32" width="22" height="22"><rect width="32" height="32" rx="7" fill="currentColor" />
              <path d="M9 10h14v9H15l-4 3v-3H9z" fill="#fff" /></svg>
          </span>
          Helpdesk
        </a>
        <nav className="nav" aria-label="Main">
          <a href={href.queue()} aria-current={route.page !== "approvals" ? "page" : undefined}>Tickets</a>
          <a href={href.approvals()} aria-current={route.page === "approvals" ? "page" : undefined}>
            Approvals
            {pending > 0 && <span className="count" aria-label={`${pending} pending`}>{pending}</span>}
          </a>
        </nav>
        <div className="header-tools">
          <button className="button button-primary" onClick={onNewTicket}>New ticket</button>
          <label className="acting">
            <span>Acting as</span>
            <select value={current?.id ?? ""} onChange={(e) => select(Number(e.target.value))}
                    disabled={agents.length === 0}>
              {agents.length === 0 && <option value="">No agents</option>}
              {agents.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.team})</option>)}
            </select>
          </label>
        </div>
      </div>
    </header>
  );
}

/** Pending approvals badge; refreshed when the page changes and every 30 seconds. */
function usePendingCount(route: Route): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    let active = true;
    const load = () => api.listActions("PENDING").then((a) => active && setCount(a.length)).catch(() => {});
    load();
    const timer = window.setInterval(load, 30_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [route]);
  return count;
}
