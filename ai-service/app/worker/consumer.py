"""RabbitMQ consumer for auto-triage (stage 5b), run as a background task inside the FastAPI app.

Delivery guarantees, briefly:
- A message is acknowledged only AFTER the reply has been published. If the worker crashes in
  between, RabbitMQ redelivers it: "at least once". helpdesk-api applies results idempotently,
  so a duplicate reply is harmless.
- Retries: a failed message is re-published with an incremented x-attempts header after a
  backoff, and the original is acked. After max attempts it is rejected, and the queue's
  dead-letter settings move it to ai.triage.requests.dlq for inspection.
"""

from __future__ import annotations

import asyncio
import json
import logging

from app.triage.service import TriageService
from app.worker import topology as t
from app.worker.handler import Decision, handle_triage_request, retry_delay_seconds

log = logging.getLogger("ai_service.worker")


class TriageWorker:
    def __init__(self, amqp_url: str, triage_service: TriageService, prefetch: int = 4, max_attempts: int = 3):
        self._url = amqp_url
        self._triage = triage_service
        self._prefetch = prefetch
        self._max_attempts = max_attempts

    async def run_forever(self) -> None:
        """Connect (waiting for the broker if needed) and consume; restart after unexpected errors."""
        while True:
            try:
                await self._consume()
            except asyncio.CancelledError:
                log.info("triage worker stopped")
                raise
            except Exception as exc:
                log.warning("triage worker error, reconnecting in 5s: %s", exc)
                await asyncio.sleep(5)

    async def _consume(self) -> None:
        import aio_pika

        connection = await aio_pika.connect_robust(self._url)  # reconnects automatically once connected
        async with connection:
            channel = await connection.channel()
            await channel.set_qos(prefetch_count=self._prefetch)

            events = await channel.declare_exchange(t.EVENTS_EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
            dlx = await channel.declare_exchange(t.DEAD_LETTER_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True)
            dlq = await channel.declare_queue(t.TRIAGE_REQUESTS_DLQ, durable=True)
            await dlq.bind(dlx, routing_key=t.TRIAGE_REQUESTS_DLQ)
            queue = await channel.declare_queue(t.TRIAGE_REQUESTS, durable=True,
                                                arguments=t.TRIAGE_REQUESTS_ARGUMENTS)
            await queue.bind(events, routing_key=t.TICKET_CREATED)

            log.info("triage worker consuming '%s' (prefetch=%d, max_attempts=%d)",
                     t.TRIAGE_REQUESTS, self._prefetch, self._max_attempts)
            async with queue.iterator() as messages:
                async for message in messages:
                    await self._process(message, channel, events)

    async def _process(self, message, channel, events) -> None:
        import aio_pika

        headers = dict(message.headers or {})
        attempt = int(headers.get(t.ATTEMPTS_HEADER, 1))
        try:
            result = await handle_triage_request(message.body, attempt, self._max_attempts, self._triage)

            if result.decision is Decision.ACK:
                reply = aio_pika.Message(
                    body=json.dumps(result.reply).encode(),
                    content_type="application/json",
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    message_id=result.reply["eventId"],
                )
                await events.publish(reply, routing_key=t.TICKET_TRIAGED)
                await message.ack()
                log.info("triaged ticket=%s -> %s/%s (attempt %d)", result.reply["ticketId"],
                         result.reply["result"]["category"], result.reply["result"]["priority"], attempt)

            elif result.decision is Decision.RETRY:
                delay = retry_delay_seconds(attempt)
                log.warning("retrying in %.0fs: %s", delay, result.reason)
                await asyncio.sleep(delay)
                retry = aio_pika.Message(
                    body=message.body,
                    content_type=message.content_type or "application/json",
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    message_id=message.message_id,
                    headers={**headers, t.ATTEMPTS_HEADER: attempt + 1},
                )
                await channel.default_exchange.publish(retry, routing_key=t.TRIAGE_REQUESTS)
                await message.ack()

            else:
                log.error("dead-lettering message %s: %s", message.message_id, result.reason)
                await message.reject(requeue=False)  # queue's x-dead-letter-* settings route it to the DLQ

        except Exception as exc:
            # e.g. connection lost while publishing: leave the message unacked so it is redelivered
            log.warning("could not finish message %s, will be redelivered: %s", message.message_id, exc)
            try:
                await message.nack(requeue=True)
            except Exception:
                pass  # channel already gone; RabbitMQ requeues unacked messages itself
