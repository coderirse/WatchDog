package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.watchdog.data.model.BalanceSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 余额历史快照存储：记录按 CNY 计价的平台总余额随时间变化，
 * 供仪表盘绘制余额趋势图。仅在有变化时追加，避免频繁自动刷新产生重复点。
 */
class BalanceHistoryStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_history",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    /** 追加一条快照；与最近一次余额相同时跳过（去重）。 */
    suspend fun record(balance: Double) {
        withContext(Dispatchers.IO) {
            val list = loadSync().toMutableList()
            if (list.lastOrNull()?.balance == balance) return@withContext
            list += BalanceSnapshot(System.currentTimeMillis(), balance)
            if (list.size > MAX_ENTRIES) {
                list.removeAt(0)
            }
            val json = gson.toJson(list)
            prefs.edit { putString(KEY_HISTORY, json) }
        }
    }

    /** 读取全部快照（按时间升序）。 */
    suspend fun load(): List<BalanceSnapshot> {
        return withContext(Dispatchers.IO) { loadSync() }
    }

    private fun loadSync(): List<BalanceSnapshot> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<BalanceSnapshot>>() {}.type
            gson.fromJson<List<BalanceSnapshot>>(json, type)
        }.getOrNull() ?: emptyList()
    }

    private companion object {
        const val KEY_HISTORY = "balance_history"
        const val MAX_ENTRIES = 200
    }
}
