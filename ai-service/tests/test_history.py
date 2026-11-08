import os
import unittest
import uuid

import redis.asyncio as redis

from app.history import ChatHistoryRepository, PrivacyFilter


class PrivacyFilterTest(unittest.TestCase):
    def test_password_openid_and_phone_are_redacted(self):
        source = "password=Secret123 openid: wx-private-id 手机号13812345678，密码：AnotherSecret"

        cleaned = PrivacyFilter.scrub(source)

        for private_value in ("Secret123", "wx-private-id", "13812345678", "AnotherSecret"):
            self.assertNotIn(private_value, cleaned)
        self.assertIn("REDACTED", cleaned)


class ChatHistoryRepositoryIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.redis = redis.Redis(
            host=os.environ.get("REDIS_HOST", "127.0.0.1"),
            port=int(os.environ.get("REDIS_PORT", "6379")),
            password=os.environ.get("REDIS_PASSWORD") or None,
            db=15,
            decode_responses=True,
        )
        await self.redis.ping()
        self.repository = ChatHistoryRepository(self.redis, max_messages=20, ttl_seconds=86400)
        self.user_id = 990000001
        self.other_user_id = 990000002
        self.thread_id = uuid.uuid4()
        await self.cleanup()

    async def asyncTearDown(self):
        await self.cleanup()
        await self.redis.aclose()

    async def cleanup(self):
        for user_id in (self.user_id, self.other_user_id):
            keys = [key async for key in self.redis.scan_iter(f"ai:chat:{user_id}:*")]
            if keys:
                await self.redis.delete(*keys)

    async def test_history_is_limited_and_ttl_is_24_hours(self):
        for index in range(25):
            await self.repository.append(self.user_id, self.thread_id, "user", f"message-{index}")

        items = await self.repository.list(self.user_id, self.thread_id)

        self.assertEqual(len(items), 20)
        self.assertEqual(items[0], {"role": "user", "content": "message-5"})
        self.assertEqual(items[-1], {"role": "user", "content": "message-24"})
        self.assertTrue(all(set(item) == {"role", "content"} for item in items))
        ttl = await self.redis.ttl(f"ai:chat:{self.user_id}:{self.thread_id}")
        self.assertGreater(ttl, 86390)
        self.assertLessEqual(ttl, 86400)

    async def test_cross_user_history_is_empty_and_owner_mismatch_is_rejected(self):
        await self.repository.append(self.user_id, self.thread_id, "user", "private")

        self.assertEqual(await self.repository.list(self.other_user_id, self.thread_id), [])
        key = f"ai:chat:{self.user_id}:{self.thread_id}"
        await self.redis.set(key, '{"userId":990000002,"messages":[]}')
        self.assertEqual(await self.repository.list(self.user_id, self.thread_id), [])
        with self.assertRaises(ValueError):
            await self.repository.append(self.user_id, self.thread_id, "user", "must-not-append")

    async def test_thread_id_must_be_uuid(self):
        with self.assertRaises(ValueError):
            await self.repository.list(self.user_id, "not-a-uuid")

    async def test_pair_append_is_atomic_and_privacy_filtered(self):
        await self.repository.append_pair(
            self.user_id,
            self.thread_id,
            "phone 13812345678",
            "password=Secret123",
        )

        items = await self.repository.list(self.user_id, self.thread_id)

        self.assertEqual([item["role"] for item in items], ["user", "assistant"])
        self.assertNotIn("13812345678", str(items))
        self.assertNotIn("Secret123", str(items))
        raw = await self.redis.get(f"ai:chat:{self.user_id}:{self.thread_id}")
        self.assertNotIn("13812345678", raw)
        self.assertNotIn("Secret123", raw)


if __name__ == "__main__":
    unittest.main()
