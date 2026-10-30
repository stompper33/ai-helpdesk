// Raise a ticket on behalf of a customer. In Docker mode it is then triaged automatically over
// RabbitMQ; the ticket page shows "Waiting for automatic triage" until the result arrives.

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import type { Customer, Order, TicketPriority } from "../api/types";
import { PRIORITIES } from "../api/types";
import { priorityLabel } from "../lib/format";
import { href, navigate } from "../lib/router";
import { ErrorNotice } from "./ui";

export function NewTicketDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [customerId, setCustomerId] = useState<number>();
  const [orderNumber, setOrderNumber] = useState("");
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<TicketPriority>("MEDIUM");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();

  // <dialog> gives focus trapping and Escape-to-close for free
  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  useEffect(() => {
    if (!open || customers.length) return;
    api.listCustomers().then((c) => {
      setCustomers(c);
      setCustomerId((id) => id ?? c[0]?.id);
    }).catch(setError);
  }, [open, customers.length]);

  useEffect(() => {
    setOrderNumber("");
    if (customerId === undefined) return;
    api.customerOrders(customerId).then(setOrders).catch(() => setOrders([]));
  }, [customerId]);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (customerId === undefined) return;
    setBusy(true);
    setError(undefined);
    try {
      const created = await api.createTicket({
        customerId, subject: subject.trim(), description: description.trim(), priority,
        orderNumber: orderNumber || undefined,
      });
      setSubject("");
      setDescription("");
      onClose();
      navigate(href.ticket(created.id));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const fieldError = (name: string) => (error instanceof ApiError ? error.fieldErrors?.[name] : undefined);

  return (
    <dialog ref={dialog} className="dialog" onClose={onClose} aria-labelledby="new-ticket-title">
      <form onSubmit={(e) => void submit(e)}>
        <h2 id="new-ticket-title">New ticket</h2>
        <p className="small muted">Raise a ticket on a customer's behalf. It is triaged automatically when messaging is on.</p>

        <div className="field-row">
          <label className="field">
            <span>Customer</span>
            <select value={customerId ?? ""} onChange={(e) => setCustomerId(Number(e.target.value))} required>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name}{c.tier === "PREMIUM" ? " (Premium)" : ""}</option>)}
            </select>
          </label>
          <label className="field">
            <span>Order (optional)</span>
            <select value={orderNumber} onChange={(e) => setOrderNumber(e.target.value)}>
              <option value="">No order</option>
              {orders.map((o) => <option key={o.id} value={o.orderNumber}>{o.orderNumber}, {o.productName}</option>)}
            </select>
          </label>
        </div>

        <label className="field">
          <span>Subject</span>
          <input value={subject} onChange={(e) => setSubject(e.target.value)} required maxLength={200}
                 placeholder="e.g. Watch stuck on the logo after an update" aria-invalid={!!fieldError("subject")} />
          {fieldError("subject") && <small className="field-error">{fieldError("subject")}</small>}
        </label>

        <label className="field">
          <span>What the customer wrote</span>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} required rows={5}
                    maxLength={4000} aria-invalid={!!fieldError("description")} />
          {fieldError("description") && <small className="field-error">{fieldError("description")}</small>}
        </label>

        <label className="field field-short">
          <span>Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value as TicketPriority)}>
            {PRIORITIES.map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
          </select>
        </label>

        <ErrorNotice error={error} />
        <div className="dialog-bar">
          <button type="button" className="button" onClick={onClose}>Cancel</button>
          <button className="button button-primary" disabled={busy}>{busy ? "Creating…" : "Create ticket"}</button>
        </div>
      </form>
    </dialog>
  );
}
