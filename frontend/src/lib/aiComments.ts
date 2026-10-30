// helpdesk-api stores AI output as formatted internal comments (see AiDraftService.describe and
// AiTriageService.describe). This turns them back into structured data for display; anything that
// doesn't match is shown as plain text, so a format change never breaks the page.

export interface ParsedDraft {
  kind: "draft";
  reply: string;
  suggestedActions: string;
  notes: string;
  sources: string[];
  lookups: Lookup[];
  meta: string;
}

/** One tool call the agent made, as recorded in the comment's "Tools used:" line. */
export interface Lookup {
  name: string;
  args: string;
  ok: boolean;
  durationMs?: number;
}

export interface ParsedTriage {
  kind: "triage";
  headline: string;
  applied: boolean;
  summary: string;
  reasoning: string;
  meta: string;
}

export function parseAiComment(body: string): ParsedDraft | ParsedTriage | null {
  const text = body.replace(/\r\n/g, "\n");

  if (text.startsWith("AI DRAFT REPLY")) {
    const parts = text.split("\n---\n");
    if (parts.length < 3) return null;
    const rest = parts.slice(2).join("\n---\n").split("\n");
    const field = (prefix: string) => rest.find((l) => l.startsWith(prefix))?.slice(prefix.length).trim() ?? "";
    const sources = field("Sources:");
    const tools = field("Tools used:");
    // entries look like name(arg, arg) or name(arg) [failed]
    const lookups = tools === "none" ? [] : [...tools.matchAll(/(\w+)\(([^)]*)\)( \[failed\])?/g)]
      .map((m) => ({ name: m[1], args: m[2], ok: !m[3] }));
    return {
      kind: "draft",
      reply: parts[1].trim(),
      suggestedActions: field("Suggested actions:").replace(/\s*\(each one waits.*\)$/, ""),
      notes: field("Agent notes:"),
      sources: sources && sources !== "none" ? sources.split(",").map((s) => s.trim()) : [],
      lookups,
      meta: rest.find((l) => l.startsWith("(")) ?? "",
    };
  }

  const triage = text.match(/^(AI (?:auto-)?triage)( applied| suggestion NOT applied[^:]*): (.*)$/m);
  if (triage) {
    const line = (prefix: string) =>
      text.split("\n").find((l) => l.startsWith(prefix))?.slice(prefix.length).trim() ?? "";
    return {
      kind: "triage",
      headline: humanize(triage[3]),
      applied: triage[2] === " applied",
      summary: line("Summary:"),
      reasoning: line("Reasoning:"),
      meta: text.split("\n").find((l) => l.startsWith("(")) ?? "",
    };
  }
  return null;
}

// "category TECHNICAL, priority HIGH, sentiment FRUSTRATED" -> "category technical, priority high, ..."
function humanize(text: string): string {
  return text.replace(/\b[A-Z][A-Z_]{2,}\b/g, (w) => w.replace(/_/g, " ").toLowerCase());
}
