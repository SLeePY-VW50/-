import httpx
from fastapi import HTTPException

from app.schemas import UserContext


class AuthGateway:
    def __init__(
        self,
        base_url: str,
        internal_token: str,
        client: httpx.AsyncClient | None = None,
        timeout: float = 30,
    ):
        self._endpoint = base_url.rstrip("/") + "/api/internal/auth/verify"
        self._internal_token = internal_token
        self._client = client
        self._timeout = timeout

    async def verify(self, authorization: str | None) -> UserContext:
        if not authorization or not authorization.startswith("Bearer ") or not authorization[7:].strip():
            raise HTTPException(status_code=401, detail="UNAUTHORIZED")
        headers = {
            "Authorization": authorization,
            "X-Internal-Service-Token": self._internal_token,
        }
        try:
            if self._client is None:
                async with httpx.AsyncClient(timeout=self._timeout) as client:
                    response = await client.get(self._endpoint, headers=headers)
            else:
                response = await self._client.get(self._endpoint, headers=headers)
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=503, detail="AUTH_UNAVAILABLE") from exc
        if response.status_code in (401, 403):
            raise HTTPException(status_code=response.status_code, detail="UNAUTHORIZED")
        if response.status_code != 200:
            raise HTTPException(status_code=503, detail="AUTH_UNAVAILABLE")
        try:
            body = response.json()
            if body.get("code") != 0:
                raise ValueError("verification failed")
            return UserContext.model_validate(body["data"])
        except (ValueError, KeyError, TypeError) as exc:
            raise HTTPException(status_code=401, detail="UNAUTHORIZED") from exc
