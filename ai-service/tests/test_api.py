"""HTTP-level tests with FastAPI's TestClient. No network, no OpenAI."""

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.schemas import Category, Priority, Sentiment, TriageDecision, TriageRequest, Usage
from app.triage.classifiers import Classification, ClassifierError

TICKET = {
    "ticketId": 1,
    "subject": "Keyboard arrived with broken keys",
    "description": "The E and R keys don't register. I'd like a replacement or a refund.",
    "customerTier": "STANDARD",
    "currentPriority": "MEDIUM",
    "order": {"orderNumber": "ORD-10003", "productName": "Mechanical keyboard", "status": "DELIVERED", "amount": 89.5},
}


class FakeClassifier:
    name = "fake"
    model = "fake-model"

    def __init__(self, decision: TriageDecision | None = None, error: Exception | None = None):
        self.decision = decision
        self.error = error
        self.last_request: TriageRequest | None = None

    async def classify(self, req: TriageRequest) -> Classification:
        self.last_request = req
        if self.error:
            raise self.error
        return Classification(decision=self.decision, model=self.model, usage=Usage(input_tokens=120, output_tokens=40))


def client(classifier=None, **settings) -> TestClient:
    s = Settings(_env_file=None, ai_provider="rules", **settings)
    return TestClient(create_app(settings=s, classifier=classifier))


def test_health():
    r = client().get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP", "provider": "rules", "model": "keyword-rules-v1"}


def test_triage_with_rules_returns_camel_case_contract():
    r = client().post("/v1/triage", json=TICKET)
    assert r.status_code == 200
    body = r.json()
    assert body["category"] == "RETURNS"
    assert body["provider"] == "rules"
    assert {"latencyMs", "confidence", "summary", "reasoning", "sentiment", "priority"} <= body.keys()


def test_triage_passes_request_to_classifier_and_clamps_confidence():
    fake = FakeClassifier(TriageDecision(category=Category.SHIPPING, priority=Priority.HIGH,
                                         sentiment=Sentiment.NEGATIVE, summary=" Late dock. ",
                                         reasoning="Tracking stalled.", confidence=1.7))
    r = client(fake).post("/v1/triage", json=TICKET)
    body = r.json()
    assert r.status_code == 200
    assert fake.last_request.order.order_number == "ORD-10003"
    assert body["confidence"] == 1.0
    assert body["summary"] == "Late dock."
    assert body["usage"] == {"inputTokens": 120, "outputTokens": 40}


def test_classifier_failure_maps_to_502():
    r = client(FakeClassifier(error=ClassifierError("boom"))).post("/v1/triage", json=TICKET)
    assert r.status_code == 502
    assert r.json()["code"] == "LLM_ERROR"


def test_validation_error_is_422():
    r = client().post("/v1/triage", json={"subject": ""})
    assert r.status_code == 422


def test_api_key_enforced_when_configured():
    c = client(service_api_key="s3cret")
    assert c.post("/v1/triage", json=TICKET).status_code == 401
    assert c.post("/v1/triage", json=TICKET, headers={"X-Api-Key": "wrong"}).status_code == 401
    assert c.post("/v1/triage", json=TICKET, headers={"X-Api-Key": "s3cret"}).status_code == 200
    assert c.get("/health").status_code == 200  # health stays open for monitoring
