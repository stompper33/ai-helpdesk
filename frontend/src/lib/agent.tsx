// "Acting as": there are no logins yet, so the person using the console picks which support agent
// they are. Their id is sent with approvals and assignments; their name signs replies.
// React context makes the choice available to every component without passing props down.

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api } from "../api/client";
import type { Agent } from "../api/types";

interface AgentContextValue {
  agents: Agent[];
  current: Agent | undefined;
  select: (id: number) => void;
}

const AgentContext = createContext<AgentContextValue>({ agents: [], current: undefined, select: () => {} });

const STORAGE_KEY = "helpdesk.actingAgentId";

function readStoredId(): number | undefined {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value ? Number(value) : undefined;
  } catch {
    return undefined; // storage can be blocked (private mode); just don't remember
  }
}

export function AgentProvider({ children }: { children: ReactNode }) {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [currentId, setCurrentId] = useState<number | undefined>(readStoredId);

  useEffect(() => {
    api.listAgents().then(setAgents).catch(() => setAgents([]));
  }, []);

  const select = (id: number) => {
    setCurrentId(id);
    try {
      window.localStorage.setItem(STORAGE_KEY, String(id));
    } catch {
      // ignore
    }
  };

  const current = agents.find((a) => a.id === currentId) ?? agents[0];
  return <AgentContext.Provider value={{ agents, current, select }}>{children}</AgentContext.Provider>;
}

export function useActingAgent(): AgentContextValue {
  return useContext(AgentContext);
}
