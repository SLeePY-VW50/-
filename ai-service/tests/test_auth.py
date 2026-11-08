import unittest

import httpx
from fastapi import HTTPException

from app.auth import AuthGateway


class AuthGatewayTest(unittest.IsolatedAsyncioTestCase):
    async def test_verify_uses_fixed_endpoint_and_returns_server_identity(self):
        requests = []

        def handler(request):
            requests.append(request)
            return httpx.Response(200, json={
                "code": 0,
                "data": {"userId": 42, "roles": ["STUDENT"], "account": "hidden"},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            gateway = AuthGateway("http://spring.test", "internal-test-token", client=client)
            user = await gateway.verify("Bearer user-test-token")

        self.assertEqual(user.user_id, 42)
        self.assertEqual(user.roles, ["STUDENT"])
        self.assertEqual(len(requests), 1)
        request = requests[0]
        self.assertEqual(request.method, "GET")
        self.assertEqual(request.url.path, "/api/internal/auth/verify")
        self.assertEqual(request.headers["Authorization"], "Bearer user-test-token")
        self.assertEqual(request.headers["X-Internal-Service-Token"], "internal-test-token")

    async def test_missing_or_invalid_bearer_is_unauthorized_without_http(self):
        calls = []

        def handler(request):
            calls.append(request)
            return httpx.Response(200, json={"code": 0, "data": {"userId": 1, "roles": []}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            gateway = AuthGateway("http://spring.test", "token", client=client)
            for authorization in (None, "Basic not-bearer", "Bearer "):
                with self.assertRaises(HTTPException) as raised:
                    await gateway.verify(authorization)
                self.assertEqual(raised.exception.status_code, 401)
        self.assertEqual(calls, [])

    async def test_java_401_and_403_are_not_hidden(self):
        for status_code in (401, 403):
            with self.subTest(status_code=status_code):
                def handler(request):
                    return httpx.Response(status_code, json={"code": status_code})

                async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
                    gateway = AuthGateway("http://spring.test", "token", client=client)
                    with self.assertRaises(HTTPException) as raised:
                        await gateway.verify("Bearer user-token")
                self.assertEqual(raised.exception.status_code, status_code)


if __name__ == "__main__":
    unittest.main()
