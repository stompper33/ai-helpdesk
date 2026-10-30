"""Client for calling back into helpdesk-api (Spring Boot). These calls are the agent's tools.

Stage 5c: the agent uses helpdesk-api's INTERNAL agent API (/internal/agent/tickets/{id}/...),
not the public /api. It requires the service key (X-Api-Key), is read-only, and every call is
scoped to the one ticket being worked on, so Java limits the data to that ticket's customer.

HelpdeskApi is a Protocol so tests can use an in-memory fake instead of HTTP.
"""

from __future__ import annotations

from typing import Any, Protocol


class HelpdeskApiError(Exception):
    def __init__(self, status: int | None, message: str):
        super().__init__(message)
        self.status = status


class HelpdeskApi(Protocol):
    async def get_ticket(self, ticket_id: int) -> dict[str, Any]: ...

    async def get_orders(self, ticket_id: int) -> list[dict[str, Any]]: ...

    async def get_order(self, ticket_id: int, order_number: str) -> dict[str, Any]: ...

    async def get_ticket_history(self, ticket_id: int) -> list[dict[str, Any]]: ...


class HttpHelpdeskApi:
    """Read-only HTTP client. The agent is never given an endpoint that changes data."""

    def __init__(self, base_url: str, timeout_seconds: float = 10.0, api_key: str = ""):
        import httpx  # lazy, so the in-memory tests don't need it

        self._httpx = httpx
        headers = {"X-Api-Key": api_key} if api_key else {}
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout_seconds, headers=headers)

    async def get_ticket(self, ticket_id: int) -> dict[str, Any]:
        return await self._get(f"/internal/agent/tickets/{ticket_id}")

    async def get_orders(self, ticket_id: int) -> list[dict[str, Any]]:
        return await self._get(f"/internal/agent/tickets/{ticket_id}/orders")

    async def get_order(self, ticket_id: int, order_number: str) -> dict[str, Any]:
        return await self._get(f"/internal/agent/tickets/{ticket_id}/orders/{order_number}")

    async def get_ticket_history(self, ticket_id: int) -> list[dict[str, Any]]:
        return await self._get(f"/internal/agent/tickets/{ticket_id}/history")

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _get(self, path: str) -> Any:
        try:
            response = await self._client.get(path)
        except self._httpx.HTTPError as exc:
            raise HelpdeskApiError(None, f"helpdesk-api not reachable: {exc}") from exc
        if response.status_code >= 400:
            detail = response.text[:300]
            try:
                detail = response.json().get("detail", detail)
            except ValueError:
                pass
            raise HelpdeskApiError(response.status_code, f"helpdesk-api {response.status_code}: {detail}")
        return response.json()
