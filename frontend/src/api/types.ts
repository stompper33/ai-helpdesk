// TypeScript mirrors of the helpdesk-api DTOs (web/dto/Responses.java, ai/AgentDtos.java,
// actions/ProposedActionDtos.java). Like Java records: if the API changes, change these too.
// Instants arrive as ISO-8601 strings.

export type TicketStatus = "OPEN" | "IN_PROGRESS" | "WAITING_ON_CUSTOMER" | "RESOLVED" | "CLOSED";
export type TicketPriority = "LOW" | "MEDIUM" | "HIGH" | "URGENT";
export type TicketCategory = "BILLING" | "SHIPPING" | "RETURNS" | "TECHNICAL" | "ACCOUNT" | "OTHER";
export type CustomerTier = "STANDARD" | "PREMIUM";
export type OrderStatus = "PLACED" | "SHIPPED" | "DELIVERED" | "CANCELLED" | "REFUNDED";
export type AuthorType = "CUSTOMER" | "AGENT" | "SYSTEM" | "AI";

export const PRIORITIES: TicketPriority[] = ["LOW", "MEDIUM", "HIGH", "URGENT"];
export const CATEGORIES: TicketCategory[] = ["BILLING", "SHIPPING", "RETURNS", "TECHNICAL", "ACCOUNT", "OTHER"];

export interface Customer {
  id: number;
  name: string;
  email: string;
  tier: CustomerTier;
  createdAt: string;
}

export interface Order {
  id: number;
  orderNumber: string;
  customerId: number;
  productName: string;
  amount: number;
  status: OrderStatus;
  trackingNumber: string | null;
  orderedAt: string;
}

export interface Agent {
  id: number;
  name: string;
  email: string;
  team: string;
}

export interface Comment {
  id: number;
  authorType: AuthorType;
  authorName: string;
  body: string;
  internal: boolean;
  createdAt: string;
}

export interface TicketSummary {
  id: number;
  subject: string;
  status: TicketStatus;
  priority: TicketPriority;
  category: TicketCategory | null;
  customerId: number;
  customerName: string;
  orderNumber: string | null;
  assigneeId: number | null;
  assigneeName: string | null;
  createdAt: string;
  slaDueAt: string;
  slaBreached: boolean;
}

export interface TicketDetail {
  id: number;
  subject: string;
  description: string;
  status: TicketStatus;
  priority: TicketPriority;
  category: TicketCategory | null;
  customer: Customer;
  order: Order | null;
  assignee: Agent | null;
  createdAt: string;
  updatedAt: string;
  slaDueAt: string;
  resolvedAt: string | null;
  slaBreached: boolean;
  allowedNextStatuses: TicketStatus[];
  comments: Comment[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type ActionType = "REFUND" | "REPLACEMENT" | "ESCALATE" | "REQUEST_MORE_INFO";
export type ActionStatus = "PENDING" | "EXECUTED" | "REJECTED";

export interface ProposedAction {
  id: number;
  ticketId: number;
  type: ActionType;
  status: ActionStatus;
  proposedBy: string;
  reason: string | null;
  createdAt: string;
  decidedById: number | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  executionResult: string | null;
}

export interface ToolCall {
  name: string;
  arguments: Record<string, unknown>;
  ok: boolean;
  durationMs: number;
}

export interface Usage {
  inputTokens: number;
  outputTokens: number;
}

export interface AgentDraft {
  ticketId: number;
  customerReply: string;
  internalNote: string;
  suggestedActions: string[];
  confidence: number;
  steps: number;
  toolCalls: ToolCall[];
  provider: string;
  model: string;
  latencyMs: number;
  usage: Usage | null;
  sources: string[];
}

export interface AiDraftResponse {
  ticketId: number;
  draft: AgentDraft;
  proposedActions: ProposedAction[];
  ticket: TicketDetail;
}

export interface AiTriageResult {
  category: TicketCategory;
  priority: TicketPriority;
  sentiment: string;
  summary: string;
  reasoning: string;
  confidence: number;
  provider: string;
  model: string;
  latencyMs: number;
}

export interface AiTriageResponse {
  ticketId: number;
  outcome: "APPLIED" | "LOW_CONFIDENCE" | "PREVIEW";
  suggestion: AiTriageResult;
  ticket: TicketDetail;
}

/** RFC 9457 Problem Details, as returned by GlobalExceptionHandler. */
export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  errors?: Record<string, string>;
}
