import json
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import JSONResponse, StreamingResponse

from app.agent import ModelNotConfiguredError
from app.history import PrivacyFilter
from app.schemas import AgentResult, ChatRequest, UserContext
from app.tools import InternalToolClient, ToolIdentity, bind_tool_identity

router = APIRouter(prefix="/api/ai")


async def authenticated_user(
    request: Request,
    authorization: str | None = Header(default=None),
) -> UserContext:
    user = await request.app.state.auth_gateway.verify(authorization)
    request.state.authorization = authorization
    return user


async def _invoke_agent(request: Request, payload: ChatRequest, user: UserContext) -> AgentResult:
    agent = request.app.state.agent
    if not agent.model_configured:
        raise ModelNotConfiguredError("AI模型未配置")
    repository = request.app.state.history_repository
    history = await repository.list(user.user_id, payload.thread_id)
    clean_history = [
        {"role": item["role"], "content": PrivacyFilter.scrub(item["content"])}
        for item in history
    ]
    clean_message = PrivacyFilter.scrub(payload.message)
    settings = request.app.state.settings
    token = settings.ai_internal_token
    tools = InternalToolClient(
        settings.spring_internal_base_url,
        token.get_secret_value() if token else "",
        timeout=settings.ai_timeout_seconds,
    )
    identity = ToolIdentity(user.user_id, request.state.authorization)
    with bind_tool_identity(identity, tools):
        result = await agent.invoke(clean_message, clean_history)
    await repository.append_pair(user.user_id, payload.thread_id, clean_message, result.answer)
    return result


def _error(code: str, status_code: int = 503) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"code": code, "fallback": "/reservations"})


@router.post("/chat/invoke", response_model=AgentResult)
async def invoke(
    payload: ChatRequest,
    request: Request,
    user: UserContext = Depends(authenticated_user),
):
    try:
        return await _invoke_agent(request, payload, user)
    except ModelNotConfiguredError:
        return _error("AI_MODEL_NOT_CONFIGURED")
    except Exception:
        return _error("AI_MODEL_UNAVAILABLE")


@router.post("/chat/stream")
async def stream(
    payload: ChatRequest,
    request: Request,
    user: UserContext = Depends(authenticated_user),
):
    async def events():
        try:
            result = await _invoke_agent(request, payload, user)
            for offset in range(0, len(result.answer), 64):
                if await request.is_disconnected():
                    return
                data = json.dumps({"answer": result.answer[offset:offset + 64]}, ensure_ascii=False)
                yield f"event: answer\ndata: {data}\n\n"
            done = result.model_dump(mode="json", by_alias=True, exclude={"answer"})
            yield f"event: done\ndata: {json.dumps(done, ensure_ascii=False)}\n\n"
        except ModelNotConfiguredError:
            yield _error_event("AI_MODEL_NOT_CONFIGURED")
        except Exception:
            yield _error_event("AI_MODEL_UNAVAILABLE")

    return StreamingResponse(events(), media_type="text/event-stream")


@router.get("/chat/history/{thread_id}")
async def history(
    thread_id: UUID,
    request: Request,
    user: UserContext = Depends(authenticated_user),
):
    items = await request.app.state.history_repository.list(user.user_id, thread_id)
    return {"items": items}


@router.get("/health")
async def health(request: Request):
    return {"status": "ok", "modelConfigured": request.app.state.agent.model_configured}


def _error_event(code: str) -> str:
    data = json.dumps({"code": code, "fallback": "/reservations"})
    return f"event: error\ndata: {data}\n\n"
