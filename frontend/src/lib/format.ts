import type { ActionType, TicketCategory, TicketPriority, TicketStatus } from "../api/types";

export const statusLabel: Record<TicketStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  WAITING_ON_CUSTOMER: "Waiting on customer",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

export const priorityLabel: Record<TicketPriority, string> = {
  LOW: "Low", MEDIUM: "Medium", HIGH: "High", URGENT: "Urgent",
};

export const categoryLabel: Record<TicketCategory, string> = {
  BILLING: "Billing", SHIPPING: "Shipping", RETURNS: "Returns", TECHNICAL: "Technical",
  ACCOUNT: "Account", OTHER: "Other",
};

export const actionLabel: Record<ActionType, string> = {
  REFUND: "Refund the order",
  REPLACEMENT: "Send a replacement",
  ESCALATE: "Escalate priority",
  REQUEST_MORE_INFO: "Ask the customer for details",
};

const dateTime = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

export function timeAgo(iso: string, now = Date.now()): string {
  const minutes = Math.round((now - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
}

function duration(ms: number): string {
  const minutes = Math.max(1, Math.round(Math.abs(ms) / 60000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ${minutes % 60}m`;
  return `${Math.round(hours / 24)}d`;
}

export type SlaState = { text: string; tone: "ok" | "soon" | "late" | "done" };

/** "3h 12m left", "Overdue 2h 5m", or done for resolved/closed tickets. */
export function sla(dueIso: string, status: TicketStatus, now = Date.now()): SlaState {
  if (status === "RESOLVED" || status === "CLOSED") return { text: "Done", tone: "done" };
  const left = new Date(dueIso).getTime() - now;
  if (left < 0) return { text: `Overdue ${duration(left)}`, tone: "late" };
  return { text: `${duration(left)} left`, tone: left < 2 * 3600_000 ? "soon" : "ok" };
}

export function money(amount: number): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(amount);
}

/** "products/aurora-headphones#crackling-popping-or-distorted-sound" -> readable parts. */
export function describeSource(source: string): { document: string; section: string } {
  const [doc, section = ""] = source.split("#");
  const name = (doc.split("/").pop() ?? doc).replace(/\.(md|markdown|txt|pdf)$/i, "");
  const words = (s: string) => s.replace(/-p\d+$/, "").replace(/-/g, " ");
  const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
  return { document: capital(words(name)), section: capital(words(section)) };
}
