"""HTTP-level tests for /v1/agent/draft-reply (FastAPI TestClient, no network)."""

from fastapi.testclient import TestClient

from app.agent.models import ScriptedAgentModel
from app.agent.runner import DraftAgent
from app.config import Settings
from app.main import create_app
from tests.test_agent import FakeHelpdeskApi, make_kb


def client() -> TestClient:
    agent = DraftAgent(ScriptedAgentModel(), FakeHelpdeskApi(), make_kb())
    return TestClient(create_app(settings=Settings(_env_file=None, ai_provider="rules"), agent=agent))


def test_draft_reply_returns_camel_case_contract():
    r = client().post("/v1/agent/draft-reply", json={"ticketId": 1})
    assert r.status_code == 200
    body = r.json()
    assert {"ticketId", "customerReply", "internalNote", "suggestedActions", "confidence", "steps",
            "toolCalls", "provider", "model", "latencyMs"} <= body.keys()
    assert body["toolCalls"][0] == {"name": "get_customer_orders", "arguments": {}, "ok": True,
                                    "durationMs": body["toolCalls"][0]["durationMs"]}


def test_unknown_ticket_is_404():
    r = client().post("/v1/agent/draft-reply", json={"ticketId": 99})
    assert r.status_code == 404
    assert r.json()["code"] == "TICKET_NOT_FOUND"


def test_knowledge_search_endpoint():
    r = client().get("/v1/knowledge/search", params={"q": "parcel says delivered but missing", "k": 2})
    assert r.status_code == 200
    body = r.json()
    assert body["mode"] == "keyword" and len(body["results"]) <= 2
    assert {"source", "title", "section", "score", "text"} <= body["results"][0].keys()


def test_knowledge_stats_endpoint():
    body = client().get("/v1/knowledge/stats").json()
    assert body["synced"] is True and body["chunks"] > 0
