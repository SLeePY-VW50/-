from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import date
from typing import Literal

import httpx
from langchain_core.tools import tool


@dataclass(frozen=True)
class ToolIdentity:
    user_id: int
    authorization: str


_identity_context: ContextVar[ToolIdentity | None] = ContextVar("verified_tool_identity", default=None)
_client_context: ContextVar["InternalToolClient | None"] = ContextVar("internal_tool_client", default=None)


class InternalToolClient:
    def __init__(
        self,
        base_url: str,
        internal_token: str,
        client: httpx.AsyncClient | None = None,
        timeout: float = 30,
    ):
        self._base_url = base_url.rstrip("/")
        self._internal_token = internal_token
        self._client = client
        self._timeout = timeout

    async def rules(self, lab_id: int) -> dict:
        return await self._get("/api/internal/rules", {"labId": lab_id})

    async def availability(self, lab_id: int, reservation_date: date, mode: str) -> dict:
        return await self._get("/api/internal/availability", {
            "labId": lab_id,
            "date": reservation_date.isoformat(),
            "mode": mode,
        })

    async def reservations(self, user_id: int, status: str | None) -> dict:
        params = {"page": 0, "size": 20}
        if status is not None:
            params["status"] = status
        return await self._get(f"/api/internal/users/{user_id}/reservations", params)

    async def _get(self, path: str, params: dict) -> dict:
        identity = _identity_context.get()
        if identity is None:
            raise RuntimeError("工具缺少已验证用户上下文")
        headers = {
            "Authorization": identity.authorization,
            "X-Internal-Service-Token": self._internal_token,
        }
        url = self._base_url + path
        if self._client is None:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.get(url, params=params, headers=headers)
        else:
            response = await self._client.get(url, params=params, headers=headers)
        response.raise_for_status()
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError("Java内部工具返回失败")
        return body["data"]


@contextmanager
def bind_tool_identity(identity: ToolIdentity, client: InternalToolClient):
    identity_token = _identity_context.set(identity)
    client_token = _client_context.set(client)
    try:
        yield
    finally:
        _client_context.reset(client_token)
        _identity_context.reset(identity_token)


def _client() -> InternalToolClient:
    client = _client_context.get()
    if client is None:
        raise RuntimeError("内部工具尚未配置")
    return client


@tool
async def get_reservation_rules(lab_id: int | None = None) -> dict:
    """查询指定实验室当前有效的公开预约规则；不知道实验室ID时应先向用户询问。"""
    if lab_id is None:
        raise ValueError("查询预约规则需要lab_id")
    return await _client().rules(lab_id)


@tool
async def query_lab_availability(
    lab_id: int,
    date: date,
    mode: Literal["ROOM", "SEAT"],
) -> dict:
    """按实验室、日期和ROOM或SEAT模式查询真实48槽可用性，结果包含dataTime。"""
    return await _client().availability(lab_id, date, mode)


@tool
async def query_my_reservations(
    status: Literal[
        "PENDING_SCHEDULE", "APPROVED", "IN_USE", "COMPLETED",
        "CANCELLED", "NO_SHOW", "EXPIRED", "VIOLATION_TERMINATED",
    ] | None = None,
) -> dict:
    """查询当前已验证用户本人的预约；用户ID只从服务器鉴权上下文获取，不能由模型指定。"""
    identity = _identity_context.get()
    if identity is None:
        raise RuntimeError("工具缺少已验证用户上下文")
    return await _client().reservations(identity.user_id, status)


ALL_TOOLS = [get_reservation_rules, query_lab_availability, query_my_reservations]
