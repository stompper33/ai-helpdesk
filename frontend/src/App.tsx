import { useState } from "react";
import { Header } from "./components/Header";
import { NewTicketDialog } from "./components/NewTicketDialog";
import { AgentProvider } from "./lib/agent";
import { useRoute } from "./lib/router";
import { ApprovalsPage } from "./pages/ApprovalsPage";
import { QueuePage } from "./pages/QueuePage";
import { TicketPage } from "./pages/TicketPage";

export function App() {
  const route = useRoute();
  const [creating, setCreating] = useState(false);

  return (
    <AgentProvider>
      <Header route={route} onNewTicket={() => setCreating(true)} />
      {route.page === "queue" && <QueuePage />}
      {route.page === "ticket" && <TicketPage key={route.id} id={route.id} />}
      {route.page === "approvals" && <ApprovalsPage />}
      <NewTicketDialog open={creating} onClose={() => setCreating(false)} />
    </AgentProvider>
  );
}
