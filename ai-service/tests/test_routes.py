import json
import unittest
import uuid
from datetime import datetime, timezone

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.agent import AIAgent, AgentUnavailableError, ModelNotConfiguredError
from app.config import Settings
from app.main import create_app
from app.schemas import AgentResult, UserContext

THREAD_ID = str(uuid.uuid4())


class FakeAuth:
    def __init__(self):
        self.fail = False
        self.calls = 0

    async def verify(self, authorization):
        self.calls += 1
        if self.fail:
            raise HTTPException(status_code=401, detail="UNAUTHORIZED")
        return UserContext(user_id=7, roles=["STUDENT"])


class FakeHistory:
    def __init__(self):
        self.values = {}

    async def list(self, user_id, thread_id):
        return list(self.values.get((user_id, str(thread_id)), []))

    async def append_pair(self, user_id, thread_id, user_content, assistant_content):
        values = self.values.setdefault((user_id, str(thread_id)), [])
        values.extend([
            {"role": "user", "content": user_content},
            {"role": "assistant", "content": assistant_content},
        ])


class FakeAgent:
    def __init__(self):
        self.model_configured = True
        self.calls = []
        self.error = None
        self.result = AgentResult(
            answer="可以预约",
            draft={
                "labId": 1,
                "date": "2026-11-02",
                "startTime": "09:00",
                "endTime": "10:00",
                "allocationMode": "FIXED",
                "reservationMode": "SEAT",
                "seatCount": 1,
                "preferences": {},
            },
            sources=["availability:dataTime"],
            generated_at=datetime(2026, 10, 31, tzinfo=timezone.utc),
        )

    async def invoke(self, message, history):
        self.calls.append((message, history))
        if self.error:
            raise self.error
        return self.result


class RoutesTest(unittest.TestCase):
    def setUp(self):
        self.auth = FakeAuth()
        self.history = FakeHistory()
        self.agent = FakeAgent()
        self.settings = Settings(
            ai_internal_token="test-internal-token",
            deepseek_api_key=None,
            redis_db=15,
            ai_cors_origins="http://localhost:5173",
        )
        self.app = create_app(
            settings=self.settings,
            auth_gateway=self.auth,
            history_repository=self.history,
            agent=self.agent,
        )
        self.client = TestClient(self.app)

    def request_body(self, message="查询预约"):
        return {"threadId": THREAD_ID, "message": message}

    def test_health_is_public_and_does_not_initialize_or_expose_model_key(self):
        self.agent.model_configured = False

        response = self.client.get("/api/ai/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok", "modelConfigured": False})
        self.assertNotIn("apiKey", response.text)

    def test_auth_failure_never_calls_model(self):
        self.auth.fail = True

        response = self.client.post("/api/ai/chat/invoke", json=self.request_body())

        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.agent.calls, [])

    def test_no_key_returns_503_and_fallback(self):
        self.agent.model_configured = False

        response = self.client.post(
            "/api/ai/chat/invoke", json=self.request_body(), headers={"Authorization": "Bearer user"}
        )

        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["code"], "AI_MODEL_NOT_CONFIGURED")
        self.assertEqual(response.json()["fallback"], "/reservations")
        self.assertEqual(self.agent.calls, [])

    def test_invoke_returns_draft_metadata_and_filters_before_model_and_history(self):
        response = self.client.post(
            "/api/ai/chat/invoke",
            json=self.request_body("手机号13812345678 password=Secret123"),
            headers={"Authorization": "Bearer user"},
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["answer"], "可以预约")
        self.assertEqual(body["draft"]["allocationMode"], "FIXED")
        self.assertEqual(body["sources"], ["availability:dataTime"])
        self.assertIn("generatedAt", body)
        self.assertFalse(body["degraded"])
        self.assertNotIn("13812345678", self.agent.calls[0][0])
        self.assertNotIn("Secret123", self.agent.calls[0][0])
        self.assertNotIn("13812345678", str(self.history.values))
        self.assertNotIn("Secret123", str(self.history.values))

    def test_model_exception_is_unavailable_without_fake_answer(self):
        self.agent.error = TimeoutError("timeout")

        response = self.client.post(
            "/api/ai/chat/invoke", json=self.request_body(), headers={"Authorization": "Bearer user"}
        )

        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["code"], "AI_MODEL_UNAVAILABLE")
        self.assertEqual(response.json()["fallback"], "/reservations")
        self.assertEqual(self.history.values, {})

    def test_sse_streams_real_answer_then_done(self):
        response = self.client.post(
            "/api/ai/chat/stream", json=self.request_body(), headers={"Authorization": "Bearer user"}
        )

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        self.assertIn("event: answer", response.text)
        self.assertIn("可以预约", response.text)
        self.assertIn("event: done", response.text)
        self.assertNotIn("event: error", response.text)

    def test_sse_no_key_and_model_failure_emit_error_with_fallback(self):
        for configured, error in ((False, None), (True, TimeoutError("timeout"))):
            with self.subTest(configured=configured):
                self.agent.model_configured = configured
                self.agent.error = error
                response = self.client.post(
                    "/api/ai/chat/stream", json=self.request_body(), headers={"Authorization": "Bearer user"}
                )
                self.assertIn("event: error", response.text)
                self.assertIn("/reservations", response.text)
                self.assertNotIn("event: answer", response.text)
                expected = "AI_MODEL_NOT_CONFIGURED" if not configured else "AI_MODEL_UNAVAILABLE"
                self.assertIn(expected, response.text)

    def test_history_is_current_user_scoped(self):
        self.history.values[(7, THREAD_ID)] = [{"role": "user", "content": "mine"}]
        self.history.values[(8, THREAD_ID)] = [{"role": "user", "content": "other"}]

        response = self.client.get(
            f"/api/ai/chat/history/{THREAD_ID}", headers={"Authorization": "Bearer user"}
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["items"], [{"role": "user", "content": "mine"}])
        self.assertNotIn("other", response.text)

    def test_cors_uses_explicit_origin_whitelist(self):
        allowed = self.client.options(
            "/api/ai/chat/invoke",
            headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
        )
        denied = self.client.options(
            "/api/ai/chat/invoke",
            headers={"Origin": "http://evil.test", "Access-Control-Request-Method": "POST"},
        )

        self.assertEqual(allowed.headers.get("access-control-allow-origin"), "http://localhost:5173")
        self.assertNotEqual(denied.headers.get("access-control-allow-origin"), "http://evil.test")


class LazyAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_key_does_not_initialize_model(self):
        factory_calls = []

        def model_factory(**kwargs):
            factory_calls.append(kwargs)
            raise AssertionError("must not initialize")

        agent = AIAgent(Settings(deepseek_api_key=None), model_factory=model_factory)

        self.assertFalse(agent.model_configured)
        with self.assertRaises(ModelNotConfiguredError):
            await agent.invoke("hello", [])
        self.assertEqual(factory_calls, [])

    async def test_invalid_model_json_maps_to_unavailable(self):
        class FakeChain:
            async def ainvoke(self, payload):
                return {"messages": [type("Message", (), {"content": "not-json"})()]}

        agent = AIAgent(
            Settings(deepseek_api_key="fake-test-key"),
            model_factory=lambda **kwargs: object(),
            agent_factory=lambda **kwargs: FakeChain(),
        )

        with self.assertRaises(AgentUnavailableError):
            await agent.invoke("hello", [])

    async def test_model_configuration_is_lazy_and_fixed(self):
        model_calls = []
        agent_calls = []

        class FakeChain:
            async def ainvoke(self, payload):
                content = json.dumps({"answer": "真实模型结果", "draft": None, "sources": []})
                return {"messages": [type("Message", (), {"content": content})()]}

        def model_factory(**kwargs):
            model_calls.append(kwargs)
            return object()

        def agent_factory(**kwargs):
            agent_calls.append(kwargs)
            return FakeChain()

        agent = AIAgent(
            Settings(deepseek_api_key="fake-test-key"),
            model_factory=model_factory,
            agent_factory=agent_factory,
        )
        self.assertEqual(model_calls, [])

        result = await agent.invoke("hello", [])
        await agent.invoke("again", [])

        self.assertEqual(result.answer, "真实模型结果")
        self.assertEqual(len(model_calls), 1)
        self.assertEqual(model_calls[0]["temperature"], 0.2)
        self.assertEqual(model_calls[0]["max_tokens"], 1024)
        self.assertEqual(model_calls[0]["timeout"], 30)
        self.assertEqual(len(agent_calls), 1)
        self.assertEqual(len(agent_calls[0]["tools"]), 3)


if __name__ == "__main__":
    unittest.main()
