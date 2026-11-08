import unittest

import httpx

from app.tools import (
    ALL_TOOLS,
    InternalToolClient,
    ToolIdentity,
    bind_tool_identity,
    get_reservation_rules,
    query_lab_availability,
    query_my_reservations,
)


class InternalToolsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.requests = []

        def handler(request):
            self.requests.append(request)
            return httpx.Response(200, json={"code": 0, "data": {"path": request.url.path}})

        self.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        self.tools = InternalToolClient("http://spring.test", "internal-test-token", client=self.client)
        self.binding = bind_tool_identity(ToolIdentity(42, "Bearer verified-user"), self.tools)
        self.binding.__enter__()

    async def asyncTearDown(self):
        self.binding.__exit__(None, None, None)
        await self.client.aclose()

    async def test_only_three_readonly_tools_are_exposed(self):
        self.assertEqual(
            {tool.name for tool in ALL_TOOLS},
            {"get_reservation_rules", "query_lab_availability", "query_my_reservations"},
        )
        self.assertNotIn("userId", query_my_reservations.args_schema.model_fields)
        self.assertNotIn("user_id", query_my_reservations.args_schema.model_fields)

    async def test_my_reservations_uses_verified_context_user_id(self):
        result = await query_my_reservations.ainvoke({"status": "APPROVED"})

        self.assertEqual(result["path"], "/api/internal/users/42/reservations")
        request = self.requests[-1]
        self.assertEqual(request.method, "GET")
        self.assertEqual(request.url.path, "/api/internal/users/42/reservations")
        self.assertEqual(request.url.params["status"], "APPROVED")
        self.assertEqual(request.headers["Authorization"], "Bearer verified-user")
        self.assertEqual(request.headers["X-Internal-Service-Token"], "internal-test-token")

    async def test_availability_uses_typed_fixed_parameters(self):
        await query_lab_availability.ainvoke({"lab_id": 3, "date": "2026-11-02", "mode": "SEAT"})

        request = self.requests[-1]
        self.assertEqual(request.url.path, "/api/internal/availability")
        self.assertEqual(dict(request.url.params), {"labId": "3", "date": "2026-11-02", "mode": "SEAT"})

    async def test_rules_requires_a_lab_id_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            await get_reservation_rules.ainvoke({})
        self.assertEqual(self.requests, [])

        await get_reservation_rules.ainvoke({"lab_id": 2})
        self.assertEqual(self.requests[-1].url.path, "/api/internal/rules")
        self.assertEqual(self.requests[-1].url.params["labId"], "2")


if __name__ == "__main__":
    unittest.main()
