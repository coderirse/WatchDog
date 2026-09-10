package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * QuotaRepository 编排层测试：已配置判定（含会话-only 平台）、成功写缓存、
 * 异常回退缓存（isStale）、错误消息映射、协程取消传播。
 */
class QuotaRepositoryTest {

    // ===== 测试替身 =====

    private class FakeConfig(
        var configured: List<PlatformType> = emptyList()
    ) : PlatformConfigSource {
        override suspend fun getConfiguredPlatforms() = configured
    }

    private class FakeCache : QuotaCache {
        val store = mutableMapOf<PlatformType, QuotaInfo>()
        var putCount = 0
        override suspend fun get(platform: PlatformType) = store[platform]
        override suspend fun put(platform: PlatformType, quota: QuotaInfo) {
            putCount++
            store[platform] = quota
        }
    }

    private class FakeSessions(
        var withSession: Set<PlatformType> = emptySet()
    ) : WebSessionAccess {
        override suspend fun hasWebSession(platform: PlatformType) = platform in withSession

        /** 会话-only 平台视为已配置时，Repository 不读令牌本身，返回占位值即可。 */
        override suspend fun getWebSession(platform: PlatformType): String? =
            if (platform in withSession) "test-session-token" else null

        override suspend fun getWebSessionCookie(platform: PlatformType): String? = null
    }

    private class FakeProvider(
        override val platform: PlatformType,
        var result: QuotaInfo? = null,
        var error: (() -> Throwable)? = null,
        var latencyMs: Long = 0
    ) : PlatformQuotaProvider {
        var calls = 0
        override suspend fun fetch(): QuotaInfo {
            calls++
            if (latencyMs > 0) delay(latencyMs)
            error?.let { throw it() }
            return result ?: QuotaInfo.notConfigured(platform)
        }
    }

    private fun ok(platform: PlatformType, balance: String = "10.00") = QuotaInfo(
        platform = platform,
        isAvailable = true,
        isConfigured = true,
        totalBalance = balance
    )

    private fun repo(
        config: FakeConfig,
        cache: FakeCache,
        sessions: FakeSessions,
        vararg providers: PlatformQuotaProvider
    ) = QuotaRepository(config, cache, sessions, providers.toList())

    // ===== 用例 =====

    @Test
    fun `未配置平台返回 notConfigured 且不触发请求`() = runTest {
        val p = FakeProvider(PlatformType.DEEPSEEK)
        val repository = repo(FakeConfig(), FakeCache(), FakeSessions(), p)

        val quotas = repository.fetchAllQuotas()

        assertEquals(PlatformType.entries.size, quotas.size)
        val ds = quotas.first { it.platform == PlatformType.DEEPSEEK }
        assertTrue(!ds.isConfigured)
        assertEquals(0, p.calls)
    }

    @Test
    fun `会话-only 平台视为已配置（无 API Key 也拉取）`() = runTest {
        val ds = FakeProvider(PlatformType.DEEPSEEK, result = ok(PlatformType.DEEPSEEK))
        val repository = repo(
            FakeConfig(configured = emptyList()),
            FakeCache(),
            FakeSessions(withSession = setOf(PlatformType.DEEPSEEK)),
            ds
        )

        val quotas = repository.fetchAllQuotas()

        assertEquals(1, ds.calls)
        assertTrue(quotas.first { it.platform == PlatformType.DEEPSEEK }.isConfigured)
    }

    @Test
    fun `不支持会话的平台即使有会话标记也不计入`() = runTest {
        val glm = FakeProvider(PlatformType.GLM, result = ok(PlatformType.GLM))
        val repository = repo(
            FakeConfig(),
            FakeCache(),
            FakeSessions(withSession = setOf(PlatformType.GLM)),
            glm
        )

        repository.fetchAllQuotas()

        assertEquals(0, glm.calls)
    }

    @Test
    fun `成功结果写入缓存`() = runTest {
        val cache = FakeCache()
        val ds = FakeProvider(PlatformType.DEEPSEEK, result = ok(PlatformType.DEEPSEEK))
        val repository = repo(FakeConfig(configured = listOf(PlatformType.DEEPSEEK)), cache, FakeSessions(), ds)

        repository.fetchAllQuotas()

        assertEquals(1, cache.putCount)
        assertNotNull(cache.store[PlatformType.DEEPSEEK])
    }

    @Test
    fun `带 errorMessage 的结果不写缓存`() = runTest {
        val cache = FakeCache()
        val ds = FakeProvider(
            PlatformType.DEEPSEEK,
            result = QuotaInfo.error(PlatformType.DEEPSEEK, "HTTP 401")
        )
        val repository = repo(FakeConfig(configured = listOf(PlatformType.DEEPSEEK)), cache, FakeSessions(), ds)

        repository.fetchAllQuotas()

        assertEquals(0, cache.putCount)
    }

    @Test
    fun `网络异常回退缓存并标记 isStale`() = runTest {
        val cache = FakeCache()
        cache.store[PlatformType.DEEPSEEK] = ok(PlatformType.DEEPSEEK, balance = "8.88")
        val ds = FakeProvider(PlatformType.DEEPSEEK, error = { java.io.IOException("boom") })
        val repository = repo(FakeConfig(configured = listOf(PlatformType.DEEPSEEK)), cache, FakeSessions(), ds)

        val quota = repository.fetchPlatformQuota(PlatformType.DEEPSEEK)

        assertTrue(quota.isStale)
        assertNull(quota.errorMessage)
        assertEquals("8.88", quota.totalBalance)
    }

    @Test
    fun `无缓存时网络异常映射为中文错误消息`() = runTest {
        val cases = listOf(
            Triple<() -> Throwable, String, PlatformType>(
                { UnknownHostException() }, "无法连接服务器，请检查网络", PlatformType.DEEPSEEK),
            Triple({ SocketTimeoutException() }, "请求超时，请稍后重试", PlatformType.KIMI),
            Triple({ java.io.IOException() }, "网络请求失败", PlatformType.GLM)
        )
        for ((thrower, expected, platform) in cases) {
            val provider = FakeProvider(platform, error = thrower)
            val repository = repo(
                FakeConfig(configured = listOf(platform)), FakeCache(), FakeSessions(), provider
            )
            val quota = repository.fetchPlatformQuota(platform)
            assertEquals(expected, quota.errorMessage)
            assertTrue(quota.isConfigured)
        }
    }

    @Test
    fun `协程取消向上传播不被吞掉`() = runTest {
        val ds = FakeProvider(PlatformType.DEEPSEEK, error = { CancellationException("cancel") })
        val repository = repo(FakeConfig(configured = listOf(PlatformType.DEEPSEEK)), FakeCache(), FakeSessions(), ds)

        try {
            repository.fetchPlatformQuota(PlatformType.DEEPSEEK)
            fail("应重新抛出 CancellationException")
        } catch (expected: CancellationException) {
            // 预期：取消必须传播
        }
    }

    @Test
    fun `多平台并行获取且各自独立`() = runTest {
        val a = FakeProvider(PlatformType.DEEPSEEK, result = ok(PlatformType.DEEPSEEK, "1.00"), latencyMs = 50)
        val b = FakeProvider(PlatformType.KIMI, result = ok(PlatformType.KIMI, "2.00"), latencyMs = 50)
        val repository = repo(
            FakeConfig(configured = listOf(PlatformType.DEEPSEEK, PlatformType.KIMI)),
            FakeCache(), FakeSessions(), a, b
        )

        val quotas = repository.fetchAllQuotas()

        assertEquals("1.00", quotas.first { it.platform == PlatformType.DEEPSEEK }.totalBalance)
        assertEquals("2.00", quotas.first { it.platform == PlatformType.KIMI }.totalBalance)
    }
}
