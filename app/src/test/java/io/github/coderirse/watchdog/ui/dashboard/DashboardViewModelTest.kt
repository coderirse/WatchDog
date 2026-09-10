package io.github.coderirse.watchdog.ui.dashboard

import io.github.coderirse.watchdog.data.model.BalanceSnapshot
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaState
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.QuotaCache
import io.github.coderirse.watchdog.data.repository.QuotaRepository
import io.github.coderirse.watchdog.data.repository.PlatformConfigSource
import io.github.coderirse.watchdog.data.repository.RefreshIntervalSource
import io.github.coderirse.watchdog.data.repository.WebSessionAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DashboardViewModel 关键分支测试。
 *
 * 覆盖的是此前完全没有测试、却最容易出错的三处：
 * 1. QuotaState 三态推导（全成功 / 部分失败 / 全失败）；
 * 2. 余额趋势快照的写入条件（缓存数据与异常平台不得写入，避免假性波动）；
 * 3. 刷新并发去重（手动/下拉/自动同时触发时只打一次接口）。
 *
 * 依赖全部为手写假实现，不引入 Robolectric。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== 测试替身 =====

    private class FakeConfig(private val configured: List<PlatformType>) : PlatformConfigSource {
        override suspend fun getConfiguredPlatforms() = configured
    }

    private class FakeCache : QuotaCache {
        override suspend fun get(platform: PlatformType): QuotaInfo? = null
        override suspend fun put(platform: PlatformType, quota: QuotaInfo) = Unit
    }

    private class FakeSessions : WebSessionAccess {
        override suspend fun hasWebSession(platform: PlatformType) = false
        override suspend fun getWebSession(platform: PlatformType): String? = null
        override suspend fun getWebSessionCookie(platform: PlatformType): String? = null
    }

    private class FakeProvider(
        override val platform: PlatformType,
        private val result: QuotaInfo,
        private val gate: CompletableDeferred<Unit>? = null
    ) : PlatformQuotaProvider {
        var calls = 0
        override suspend fun fetch(): QuotaInfo {
            calls++
            gate?.await()
            return result
        }
    }

    private class FakeIntervals(var minutes: Int = 5) : RefreshIntervalSource {
        override suspend fun getAutoRefreshInterval() = minutes
    }

    private fun viewModel(
        providers: List<PlatformQuotaProvider>,
        configured: List<PlatformType>,
        intervals: FakeIntervals = FakeIntervals(),
        onAlert: (List<QuotaInfo>) -> Unit = {},
        onRecord: (Double) -> Unit = {},
        history: List<BalanceSnapshot> = emptyList()
    ): DashboardViewModel {
        val repository = QuotaRepository(FakeConfig(configured), FakeCache(), FakeSessions(), providers)
        return DashboardViewModel(
            quotaRepository = repository,
            refreshIntervalSource = intervals,
            alertEvaluator = { quotas -> onAlert(quotas) },
            historyRecorder = { balance -> onRecord(balance) },
            historyLoader = { history }
        )
    }

    /** 成功平台的标准结果：isAvailable=true 且余额可解析。 */
    private fun ok(platform: PlatformType, balance: String = "10.00", currency: String = "CNY") =
        QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            totalBalance = balance,
            currency = currency
        )

    // ===== 用例 =====

    @Test
    fun `全部成功时为 Success 且非离线`() = runTest(dispatcher) {
        val vm = viewModel(
            providers = listOf(FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK))),
            configured = listOf(PlatformType.DEEPSEEK)
        )

        advanceUntilIdle()

        val state = vm.quotaState.value
        assertTrue("期望 Success，实际 $state", state is QuotaState.Success)
        assertFalse(vm.isOffline.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun `部分平台失败时为 PartialSuccess 并列出失败平台`() = runTest(dispatcher) {
        val vm = viewModel(
            providers = listOf(
                FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK)),
                FakeProvider(PlatformType.GLM, QuotaInfo.error(PlatformType.GLM, "HTTP 401"))
            ),
            configured = listOf(PlatformType.DEEPSEEK, PlatformType.GLM)
        )

        advanceUntilIdle()

        val state = vm.quotaState.value
        assertTrue("期望 PartialSuccess，实际 $state", state is QuotaState.PartialSuccess)
        assertEquals(listOf(PlatformType.GLM), (state as QuotaState.PartialSuccess).failedPlatforms)
    }

    @Test
    fun `全部平台失败时为 Error 且消息包含平台名与原因`() = runTest(dispatcher) {
        val vm = viewModel(
            providers = listOf(
                FakeProvider(PlatformType.DEEPSEEK, QuotaInfo.error(PlatformType.DEEPSEEK, "HTTP 401"))
            ),
            configured = listOf(PlatformType.DEEPSEEK)
        )

        advanceUntilIdle()

        val state = vm.quotaState.value
        assertTrue("期望 Error，实际 $state", state is QuotaState.Error)
        val message = (state as QuotaState.Error).message
        assertTrue("消息应含平台名：$message", message.contains("DeepSeek"))
        assertTrue("消息应含原因：$message", message.contains("HTTP 401"))
    }

    @Test
    fun `缓存数据触发离线标记且不写入趋势快照`() = runTest(dispatcher) {
        val recorded = mutableListOf<Double>()
        val staleQuota = ok(PlatformType.DEEPSEEK, "8.88").copy(isStale = true)
        val vm = viewModel(
            providers = listOf(FakeProvider(PlatformType.DEEPSEEK, staleQuota)),
            configured = listOf(PlatformType.DEEPSEEK),
            onRecord = { recorded += it }
        )

        advanceUntilIdle()

        assertTrue("存在 isStale 平台时应置离线", vm.isOffline.value)
        assertTrue("缓存数据不得写入趋势快照，实际 $recorded", recorded.isEmpty())
    }

    @Test
    fun `全部平台新鲜时写入趋势快照且值为 CNY 总额`() = runTest(dispatcher) {
        val recorded = mutableListOf<Double>()
        val vm = viewModel(
            providers = listOf(
                FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK, "10.00")),
                FakeProvider(PlatformType.KIMI, ok(PlatformType.KIMI, "5.50"))
            ),
            configured = listOf(PlatformType.DEEPSEEK, PlatformType.KIMI),
            onRecord = { recorded += it }
        )

        advanceUntilIdle()

        assertEquals(listOf(15.5), recorded)
    }

    @Test
    fun `有平台查询异常时不写入趋势快照`() = runTest(dispatcher) {
        val recorded = mutableListOf<Double>()
        val vm = viewModel(
            providers = listOf(
                FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK, "10.00")),
                FakeProvider(PlatformType.GLM, QuotaInfo.error(PlatformType.GLM, "HTTP 500"))
            ),
            configured = listOf(PlatformType.DEEPSEEK, PlatformType.GLM),
            onRecord = { recorded += it }
        )

        advanceUntilIdle()

        assertTrue("存在异常平台时不应写入快照，实际 $recorded", recorded.isEmpty())
    }

    @Test
    fun `非 CNY 平台不计入趋势快照`() = runTest(dispatcher) {
        val recorded = mutableListOf<Double>()
        val vm = viewModel(
            providers = listOf(
                FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK, "10.00")),
                FakeProvider(PlatformType.SILICONFLOW, ok(PlatformType.SILICONFLOW, "99.00", "USD"))
            ),
            configured = listOf(PlatformType.DEEPSEEK, PlatformType.SILICONFLOW),
            onRecord = { recorded += it }
        )

        advanceUntilIdle()

        // USD 平台既不计入金额，也不阻止快照写入（CNY 平台全部新鲜即可）
        assertEquals(listOf(10.0), recorded)
    }

    @Test
    fun `刷新进行中再次刷新会被去重`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK), gate)
        val vm = viewModel(
            providers = listOf(provider),
            configured = listOf(PlatformType.DEEPSEEK)
        )

        // init 已发起首次刷新并阻塞在 gate 上；此时再点刷新不应重复请求
        advanceUntilIdle()
        assertEquals(1, provider.calls)

        vm.refresh()
        advanceUntilIdle()
        assertEquals("并发刷新应被去重", 1, provider.calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, provider.calls)
        assertTrue(vm.quotaState.value is QuotaState.Success)
    }

    @Test
    fun `预警评估收到本次刷新的全部平台数据`() = runTest(dispatcher) {
        val alerted = mutableListOf<List<QuotaInfo>>()
        val vm = viewModel(
            providers = listOf(FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK))),
            configured = listOf(PlatformType.DEEPSEEK),
            onAlert = { alerted += it }
        )

        advanceUntilIdle()

        assertEquals(1, alerted.size)
        // 未配置的平台也会出现在列表中（isConfigured=false），由告警层自行过滤
        assertEquals(PlatformType.entries.size, alerted.first().size)
    }

    @Test
    fun `自动刷新间隔读取时会钳制为至少一分钟`() = runTest(dispatcher) {
        val vm = viewModel(
            providers = listOf(FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK))),
            configured = listOf(PlatformType.DEEPSEEK),
            intervals = FakeIntervals(minutes = 0)
        )

        vm.loadAutoRefreshInterval()
        advanceUntilIdle()

        assertEquals(1, vm.autoRefreshInterval.value)
    }

    @Test
    fun `历史快照在初始化时加载`() = runTest(dispatcher) {
        val history = listOf(BalanceSnapshot(timestamp = 1L, balance = 12.0))
        val vm = viewModel(
            providers = listOf(FakeProvider(PlatformType.DEEPSEEK, ok(PlatformType.DEEPSEEK))),
            configured = listOf(PlatformType.DEEPSEEK),
            history = history
        )

        advanceUntilIdle()

        assertEquals(history, vm.balanceHistory.value)
    }
}
