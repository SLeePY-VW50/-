from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    ai_provider: str = "deepseek"
    ai_model: str = "deepseek-chat"
    deepseek_api_key: SecretStr | None = None
    spring_internal_base_url: str = "http://127.0.0.1:8080"
    ai_internal_token: SecretStr | None = None
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_password: SecretStr | None = None
    redis_db: int = 0
    ai_history_ttl_hours: int = 24
    ai_history_max_messages: int = 20
    ai_timeout_seconds: float = 30
    ai_cors_origins: str = "http://localhost:5173"

    @property
    def history_ttl_seconds(self) -> int:
        return self.ai_history_ttl_hours * 3600

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.ai_cors_origins.split(",") if origin.strip()]
