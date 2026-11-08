import redis.asyncio as redis
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.agent import AIAgent
from app.auth import AuthGateway
from app.config import Settings
from app.history import ChatHistoryRepository
from app.routes import router


def create_app(
    settings: Settings | None = None,
    auth_gateway=None,
    history_repository=None,
    agent=None,
) -> FastAPI:
    settings = settings or Settings()
    app = FastAPI(title="Lab Reservation AI Service")
    app.state.settings = settings
    token = settings.ai_internal_token
    internal_token = token.get_secret_value() if token else ""
    app.state.auth_gateway = auth_gateway or AuthGateway(
        settings.spring_internal_base_url,
        internal_token,
        timeout=settings.ai_timeout_seconds,
    )
    if history_repository is None:
        password = settings.redis_password
        redis_client = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=password.get_secret_value() if password else None,
            db=settings.redis_db,
            decode_responses=True,
        )
        history_repository = ChatHistoryRepository(
            redis_client,
            max_messages=settings.ai_history_max_messages,
            ttl_seconds=settings.history_ttl_seconds,
        )
    app.state.history_repository = history_repository
    app.state.agent = agent or AIAgent(settings)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type"],
    )
    app.include_router(router)
    return app


app = create_app()
