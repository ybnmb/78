package com.dafeiyu.paiguard.core

import android.content.Context
import java.security.MessageDigest

/**
 * 毒公式黑名单。
 *
 * 有些公式不是"多"，是"坏"——一个 `\def\x{\x}\x` 就够把解析栈打穿。
 * 这类公式被记指纹后，下次进入会话直接跳过，不用再赌一次它会不会炸。
 *
 * 指纹只存哈希，不存原文：一是省空间，二是群里别人的内容不该被我们留档。
 */
internal object PoisonBook {

    private const val PREFS = "paiguard_poison"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200
    private const val MAX_LEN = 64 * 1024

    private val memory = LinkedHashMap<String, Int>(64, 0.75f, true)

    @Volatile
    private var loaded = false

    fun contains(latex: String): Boolean {
        if (latex.isEmpty() || latex.length > MAX_LEN) return false
        ensureLoaded()
        return memory.containsKey(fingerprint(latex))
    }

    fun remember(latex: String, note: String) {
        if (latex.isEmpty() || latex.length > MAX_LEN) return
        ensureLoaded()
        val fp = fingerprint(latex)
        val next = (memory[fp] ?: 0) + 1
        memory[fp] = next
        if (memory.size > MAX_ENTRIES) {
            val it = memory.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        Reporter.log("毒公式入库(${next}): $fp $note")
        persist()
    }

    fun count(): Int {
        ensureLoaded()
        return memory.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val ctx = Host.context()
                if (ctx != null) {
                    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY, null)
                    if (!raw.isNullOrEmpty()) {
                        raw.split(',').forEach { pair ->
                            val parts = pair.split(':')
                            if (parts.size == 2) {
                                parts[1].toIntOrNull()?.let { memory[parts[0]] = it }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Reporter.log("毒公式库读取失败: ${t.message}")
            }
            loaded = true
        }
    }

    private fun persist() {
        try {
            val ctx = Host.context() ?: return
            val raw = memory.entries.joinToString(",") { "${it.key}:${it.value}" }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, raw).apply()
        } catch (t: Throwable) {
            Reporter.log("毒公式库写入失败: ${t.message}")
        }
    }

    private fun fingerprint(latex: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(latex.toByteArray(Charsets.UTF_8))
            buildString {
                for (i in 0 until 8) {
                    append(String.format("%02x", bytes[i]))
                }
            }
        } catch (_: Throwable) {
            latex.hashCode().toString(16)
        }
    }
}
