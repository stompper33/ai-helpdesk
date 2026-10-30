// A tiny hash router (#/tickets/12), about 30 lines instead of a dependency.
// Hash URLs need no server configuration: refreshing any page always loads index.html.
// In a bigger app you would use React Router; the idea (URL -> which page to render) is the same.

import { useEffect, useState } from "react";

export type Route =
  | { page: "queue" }
  | { page: "ticket"; id: number }
  | { page: "approvals" };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, "") || "/";
  const ticket = path.match(/^\/tickets\/(\d+)$/);
  if (ticket) return { page: "ticket", id: Number(ticket[1]) };
  if (path === "/approvals") return { page: "approvals" };
  return { page: "queue" };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => {
      setRoute(parseRoute(window.location.hash));
      window.scrollTo(0, 0);
    };
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export const href = {
  queue: () => "#/",
  ticket: (id: number) => `#/tickets/${id}`,
  approvals: () => "#/approvals",
};

export function navigate(to: string) {
  window.location.hash = to.replace(/^#/, "");
}
