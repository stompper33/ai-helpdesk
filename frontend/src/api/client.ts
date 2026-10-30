// The only place that talks HTTP. Every call goes to helpdesk-api's public /api: the UI is just
// another client of the system of record, and never calls ai-service or /internal directly.

import type {
  Agent, AiDraftResponse, AiTriageResponse, Comment, Customer, Order, Page, Problem, ProposedAction,
  ActionStatus, TicketCategory, TicketDetail, TicketPriority, TicketStatus, TicketSummary,
} from "./types";

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string,
              public fieldErrors?: Record<string, string>) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the helpdesk API. Check that helpdesk-api is running on port 8080.");
  }
  if (!response.ok) {
    let problem: Problem = {};
    try {
      problem = (await response.json()) as Problem;
    } catch {
      // not JSON (e.g. a proxy error page)
    }
    throw new ApiError(response.status, problem.detail ?? `${response.status} ${response.statusText}`,
      problem.code, problem.errors);
  }
  return (await response.json()) as T;
}

export interface TicketFilters {
  status?: TicketStatus;
  untriaged?: boolean;
  unassigned?: boolean;
  slaBreached?: boolean;
  assigneeId?: number;
  size?: number;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== false) search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

export const api = {
  listTickets: (filters: TicketFilters = {}) =>
    request<Page<TicketSummary>>("GET", `/tickets${query({ size: 100, ...filters })}`),
  getTicket: (id: number) => request<TicketDetail>("GET", `/tickets/${id}`),
  createTicket: (body: { customerId: number; orderNumber?: string; subject: string; description: string;
                         priority?: TicketPriority }) =>
    request<TicketDetail>("POST", "/tickets", body),
  triage: (id: number, body: { category?: TicketCategory; priority?: TicketPriority }) =>
    request<TicketDetail>("PATCH", `/tickets/${id}/triage`, body),
  assign: (id: number, agentId: number) => request<TicketDetail>("POST", `/tickets/${id}/assign`, { agentId }),
  changeStatus: (id: number, status: TicketStatus, note?: string) =>
    request<TicketDetail>("POST", `/tickets/${id}/status`, { status, note }),
  addComment: (id: number, body: { authorType: "AGENT" | "CUSTOMER"; authorName: string; body: string;
                                   internal: boolean }) =>
    request<Comment>("POST", `/tickets/${id}/comments`, body),

  aiTriage: (id: number, apply = true) =>
    request<AiTriageResponse>("POST", `/tickets/${id}/ai-triage?apply=${apply}`),
  aiDraft: (id: number) => request<AiDraftResponse>("POST", `/tickets/${id}/ai-draft`),

  listActions: (status: ActionStatus = "PENDING") => request<ProposedAction[]>("GET", `/actions?status=${status}`),
  ticketActions: (ticketId: number) => request<ProposedAction[]>("GET", `/tickets/${ticketId}/actions`),
  approve: (actionId: number, agentId: number, note?: string) =>
    request<ProposedAction>("POST", `/actions/${actionId}/approve`, { agentId, note }),
  reject: (actionId: number, agentId: number, note?: string) =>
    request<ProposedAction>("POST", `/actions/${actionId}/reject`, { agentId, note }),

  listAgents: () => request<Agent[]>("GET", "/agents"),
  listCustomers: () => request<Customer[]>("GET", "/customers"),
  customerOrders: (customerId: number) => request<Order[]>("GET", `/customers/${customerId}/orders`),
};
