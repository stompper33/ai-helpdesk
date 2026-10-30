"""FastAPI entry point. Run with:  uvicorn app.main:app --reload --port 8000"""

from __future__ import annotations

import asyncio
import logging
import secrets
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app.agent.helpdesk_client import HelpdeskApiError, HttpHelpdeskApi
from app.agent.models import AgentError, AgentModel, OpenAIAgentModel, ScriptedAgentModel
from app.agent.policies import PolicyLibrary
from app.agent.runner import DraftAgent, TicketNotFound
from app.agent.schemas import DraftReplyRequest, DraftReplyResponse
from app.config import Settings, get_settings
from app.schemas import HealthResponse, TriageRequest, TriageResponse
from app.triage.classifiers import ClassifierError, OpenAIClassifier, RuleBasedClassifier, TicketClassifier
from app.triage.service import TriageService
from app.worker.consumer import TriageWorker


def _require_key(settings: Settings) -> str:
    if not settings.openai_api_key or not settings.openai_api_key.get_secret_value():
        raise RuntimeError("AI_PROVIDER=openai but OPENAI_API_KEY is not set (see .env.example)")
    return settings.openai_api_key.get_secret_value()


def build_classifier(settings: Settings) -> TicketClassifier:
    if settings.resolved_provider() == "openai":
        return OpenAIClassifier(
            api_key=_require_key(settings),
            model=settings.openai_model,
            timeout_seconds=settings.openai_timeout_seconds,
        )
    return RuleBasedClassifier()


def build_agent_model(settings: Settings) -> AgentModel:
    if settings.resolved_provider() == "openai":
        return OpenAIAgentModel(
            api_key=_require_key(settings),
            model=settings.agent_model or settings.openai_model,
            timeout_seconds=settings.agent_timeout_seconds,
        )
    return ScriptedAgentModel()


def create_app(settings: Settings | None = None, classifier: TicketClassifier | None = None,
               agent: DraftAgent | None = None) -> FastAPI:
    """App factory. Tests pass their own settings/classifier/agent; production uses env config."""
    settings = settings or get_settings()
    logging.basicConfig(level=settings.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    log = logging.getLogger("ai_service")

    triage_service = TriageService(classifier or build_classifier(settings))

    helpdesk_api = None
    if agent is None:
        helpdesk_api = HttpHelpdeskApi(settings.helpdesk_api_url, settings.helpdesk_api_timeout_seconds,
                                       api_key=settings.helpdesk_api_key.get_secret_value())
        agent = DraftAgent(build_agent_model(settings), helpdesk_api, PolicyLibrary.from_markdown(),
                           max_steps=settings.agent_max_steps)

    log.info("ai-service starting: triage provider=%s model=%s | agent provider=%s model=%s | helpdesk-api=%s",
             triage_service.classifier.name, triage_service.classifier.model,
             agent.model.name, agent.model.model, settings.helpdesk_api_url)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        worker_task = None
        if settings.auto_triage_worker_enabled:
            worker = TriageWorker(settings.rabbitmq_url, triage_service,
                                  prefetch=settings.worker_prefetch, max_attempts=settings.worker_max_attempts)
            worker_task = asyncio.create_task(worker.run_forever(), name="triage-worker")
            log.info("auto-triage worker enabled")
        yield
        if worker_task is not None:
            worker_task.cancel()
            try:
                await worker_task
            except asyncio.CancelledError:
                pass
        if helpdesk_api is not None:
            await helpdesk_api.aclose()

    app = FastAPI(
        title="Helpdesk AI Service",
        version="0.4.0",
        description="AI triage and reply drafting for helpdesk-api. Called by Spring Boot; returns schema-validated JSON.",
        lifespan=lifespan,
    )

    def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
        expected = settings.service_api_key.get_secret_value() if settings.service_api_key else ""
        if expected and not (x_api_key and secrets.compare_digest(x_api_key, expected)):
            raise HTTPException(status_code=401, detail="Missing or invalid X-Api-Key")

    @app.exception_handler(ClassifierError)
    async def classifier_error_handler(_: Request, exc: ClassifierError) -> JSONResponse:
        log.warning("classifier failed: %s", exc)
        return JSONResponse(status_code=502, content={"code": "LLM_ERROR", "detail": str(exc)})

    @app.exception_handler(AgentError)
    async def agent_error_handler(_: Request, exc: AgentError) -> JSONResponse:
        log.warning("agent failed: %s", exc)
        return JSONResponse(status_code=502, content={"code": "AGENT_ERROR", "detail": str(exc)})

    @app.exception_handler(TicketNotFound)
    async def ticket_not_found_handler(_: Request, exc: TicketNotFound) -> JSONResponse:
        return JSONResponse(status_code=404, content={"code": "TICKET_NOT_FOUND", "detail": str(exc)})

    @app.exception_handler(HelpdeskApiError)
    async def helpdesk_error_handler(_: Request, exc: HelpdeskApiError) -> JSONResponse:
        log.warning("helpdesk-api call failed: %s", exc)
        return JSONResponse(status_code=502, content={"code": "HELPDESK_API_ERROR", "detail": str(exc)})

    @app.get("/health", response_model=HealthResponse, tags=["ops"])
    async def health() -> HealthResponse:
        c = triage_service.classifier
        return HealthResponse(status="UP", provider=c.name, model=c.model)

    @app.post("/v1/triage", response_model=TriageResponse, tags=["triage"],
              dependencies=[Depends(require_api_key)])
    async def triage(req: TriageRequest) -> TriageResponse:
        """Classify a ticket: category, priority, sentiment, summary, reasoning and confidence."""
        return await triage_service.triage(req)

    @app.post("/v1/agent/draft-reply", response_model=DraftReplyResponse, tags=["agent"],
              dependencies=[Depends(require_api_key)])
    async def draft_reply(req: DraftReplyRequest) -> DraftReplyResponse:
        """Run the agent: it reads the ticket and calls helpdesk-api tools, then drafts a reply for human review."""
        return await agent.draft(req.ticket_id)

    return app


app = create_app()
