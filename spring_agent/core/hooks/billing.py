"""模型请求用量捕获与计费。"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import AsyncIterator, Awaitable
from contextlib import asynccontextmanager
from copy import copy
from typing import Any, TypeVar

from pydantic_ai import ModelMessage, ModelResponse, RequestUsage, RunCancelled, RunContext
from pydantic_ai.exceptions import ModelHTTPError
from pydantic_ai.models import Model, ModelRequestParameters, StreamedResponse
from pydantic_ai.models.wrapper import WrapperModel
from pydantic_ai.settings import ModelSettings

from spring_agent.core.agent.deps import RequestUsageEntry
from spring_agent.core.billing import BillingContext, BillingError
from spring_agent.core.hooks.usage import count_native_web_searches, fill_missing_usage

T = TypeVar("T")


async def finish_shielded(operation: Awaitable[T]) -> tuple[T, bool]:
    task = asyncio.ensure_future(operation)
    cancelled = False
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            cancelled = True
    return task.result(), cancelled


class RequestBillingModel(WrapperModel):
    def __init__(self, model: Model, *, model_id: str, agent_name: str,
                 usage_log: list[RequestUsageEntry], billing: BillingContext | None) -> None:
        super().__init__(model.wrapped if isinstance(model, RequestBillingModel) else model)
        self.catalog_model_id = model_id
        self.agent_name = agent_name
        self.usage_log = usage_log
        self.billing = billing

    def for_agent(self, name: str) -> RequestBillingModel:
        return RequestBillingModel(self.wrapped, model_id=self.catalog_model_id,
                                   agent_name=name, usage_log=self.usage_log, billing=self.billing)

    async def _reserve(self, request_id: str) -> None:
        if self.billing is None:
            return
        payload = {
            "requestId": request_id, "userId": self.billing.user_id,
            "sessionId": self.billing.session_id, "turnId": self.billing.turn_id,
            "modelId": self.catalog_model_id, "agentName": self.agent_name,
        }
        _, cancelled = await finish_shielded(self.billing.gateway.reserve(payload))
        if cancelled:
            await self._settle(request_id, None, asyncio.CancelledError(), issued=False)
            raise asyncio.CancelledError
        try:
            _, cancelled = await finish_shielded(self.billing.gateway.issued(request_id))
        except Exception as error:
            await self._settle(request_id, None, error, issued=False)
            raise
        if cancelled:
            await self._settle(request_id, None, asyncio.CancelledError(), issued=False)
            raise asyncio.CancelledError

    async def _settle(self, request_id: str, response: ModelResponse | None,
                      error: BaseException | None, *, issued: bool = True,
                      messages: list[ModelMessage] | None = None,
                      model_request_parameters: ModelRequestParameters | None = None) -> None:
        usage = copy(response.usage) if response is not None else RequestUsage()
        web_searches = getattr(response.usage, "web_searches", None) if response is not None else None
        if response is not None and (type(web_searches) is not int or web_searches < 0):
            web_searches = response.usage.details.get("web_search_requests")
        if type(web_searches) is not int or web_searches < 0:
            web_searches = None
        known_rejection = isinstance(error, ModelHTTPError) and 400 <= error.status_code < 500
        if response is not None and issued and not known_rejection:
            if messages is not None and model_request_parameters is not None:
                usage = fill_missing_usage(usage, response, messages, model_request_parameters)
            if web_searches is None:
                web_searches = count_native_web_searches(response)
        has_usage = any((usage.input_tokens, usage.output_tokens, usage.cache_read_tokens,
                         usage.cache_write_tokens))
        usage_known = has_usage or not issued or known_rejection
        interrupted = response is not None and response.state == "interrupted"
        cancelled_status = isinstance(error, asyncio.CancelledError | RunCancelled) or (error is None and interrupted)
        status = "cancelled" if cancelled_status else "error" if error else "finished"
        if response is not None:
            self.usage_log.append(RequestUsageEntry(model_id=self.catalog_model_id, usage=usage))
        if self.billing is None:
            return
        payload = {
            "requestId": request_id, "userId": self.billing.user_id,
            "status": status, "usageKnown": usage_known,
            "inputTokens": usage.input_tokens, "outputTokens": usage.output_tokens,
            "cacheReadTokens": usage.cache_read_tokens, "cacheWriteTokens": usage.cache_write_tokens,
            "webSearches": web_searches,
        }
        _, cancelled = await finish_shielded(self.billing.gateway.settle(payload))
        if cancelled and error is None:
            raise asyncio.CancelledError
        if not usage_known and error is None:
            raise BillingError("上游未返回 Token 用量，本次冻结积分待核对，已停止后续模型请求")

    async def request(self, messages: list[ModelMessage], model_settings: ModelSettings | None,
                      model_request_parameters: ModelRequestParameters) -> ModelResponse:
        request_id = uuid.uuid4().hex
        await self._reserve(request_id)
        response = None
        error = None
        try:
            response = await self.wrapped.request(messages, model_settings, model_request_parameters)
            return response
        except BaseException as exc:
            error = exc
            raise
        finally:
            await self._settle(
                request_id, response, error, messages=messages,
                model_request_parameters=model_request_parameters,
            )

    @asynccontextmanager
    async def request_stream(self, messages: list[ModelMessage], model_settings: ModelSettings | None,
                             model_request_parameters: ModelRequestParameters,
                             run_context: RunContext[Any] | None = None) -> AsyncIterator[StreamedResponse]:
        request_id = uuid.uuid4().hex
        await self._reserve(request_id)
        response = None
        error = None
        try:
            async with self.wrapped.request_stream(messages, model_settings, model_request_parameters,
                                                   run_context) as stream:
                try:
                    yield stream
                finally:
                    response = stream.get()
        except BaseException as exc:
            error = exc
            raise
        finally:
            await self._settle(
                request_id, response, error, messages=messages,
                model_request_parameters=model_request_parameters,
            )
