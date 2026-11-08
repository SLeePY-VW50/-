from __future__ import annotations

import json
import re
from uuid import UUID

import redis.asyncio as redis
from redis.exceptions import ResponseError


class PrivacyFilter:
    _patterns = (
        (re.compile(r"(?i)\b(password|passwd|pwd)\s*[:=：]\s*[^\s,，;；]+"), r"\1=[REDACTED]"),
        (re.compile(r"(密码)\s*[:=：]\s*[^\s,，;；]+"), r"\1=[REDACTED]"),
        (re.compile(r"(?i)\b(openid|open_id)\s*[:=：]\s*[^\s,，;；]+"), r"\1=[REDACTED]"),
        (re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "[REDACTED_PHONE]"),
    )

    @classmethod
    def scrub(cls, content: str) -> str:
        cleaned = content
        for pattern, replacement in cls._patterns:
            cleaned = pattern.sub(replacement, cleaned)
        return cleaned


_APPEND_LUA = """
local raw = redis.call('GET', KEYS[1])
local state
if raw then
    state = cjson.decode(raw)
else
    state = {userId = ARGV[1], messages = cjson.decode('[]')}
end
if tostring(state.userId) ~= tostring(ARGV[1]) then
    return redis.error_reply('OWNER_MISMATCH')
end
local additions = cjson.decode(ARGV[2])
for _, message in ipairs(additions) do
    table.insert(state.messages, {role = message.role, content = message.content})
end
local maximum = tonumber(ARGV[3])
while #state.messages > maximum do
    table.remove(state.messages, 1)
end
redis.call('SET', KEYS[1], cjson.encode(state), 'EX', tonumber(ARGV[4]))
return #state.messages
"""


class ChatHistoryRepository:
    def __init__(self, redis_client: redis.Redis, max_messages: int = 20, ttl_seconds: int = 86400):
        self._redis = redis_client
        self._max_messages = max_messages
        self._ttl_seconds = ttl_seconds
        self._append_script = redis_client.register_script(_APPEND_LUA)

    async def list(self, user_id: int, thread_id: UUID | str) -> list[dict[str, str]]:
        raw = await self._redis.get(self._key(user_id, thread_id))
        if not raw:
            return []
        try:
            state = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            return []
        if str(state.get("userId")) != str(user_id):
            return []
        messages = state.get("messages", [])
        return [
            {"role": item["role"], "content": item["content"]}
            for item in messages[-self._max_messages:]
            if isinstance(item, dict) and item.get("role") in {"user", "assistant"}
            and isinstance(item.get("content"), str)
        ]

    async def append(self, user_id: int, thread_id: UUID | str, role: str, content: str) -> None:
        await self._append(user_id, thread_id, [{"role": role, "content": content}])

    async def append_pair(
        self,
        user_id: int,
        thread_id: UUID | str,
        user_content: str,
        assistant_content: str,
    ) -> None:
        await self._append(user_id, thread_id, [
            {"role": "user", "content": user_content},
            {"role": "assistant", "content": assistant_content},
        ])

    async def _append(self, user_id: int, thread_id: UUID | str, messages: list[dict[str, str]]) -> None:
        key = self._key(user_id, thread_id)
        cleaned = []
        for message in messages:
            if message["role"] not in {"user", "assistant"}:
                raise ValueError("历史角色仅支持user或assistant")
            cleaned.append({
                "role": message["role"],
                "content": PrivacyFilter.scrub(message["content"]),
            })
        try:
            await self._append_script(
                keys=[key],
                args=[str(user_id), json.dumps(cleaned, ensure_ascii=False), self._max_messages, self._ttl_seconds],
            )
        except ResponseError as exc:
            if "OWNER_MISMATCH" in str(exc):
                raise ValueError("历史归属校验失败") from exc
            raise

    @staticmethod
    def _key(user_id: int, thread_id: UUID | str) -> str:
        parsed_thread_id = thread_id if isinstance(thread_id, UUID) else UUID(str(thread_id))
        return f"ai:chat:{user_id}:{parsed_thread_id}"
