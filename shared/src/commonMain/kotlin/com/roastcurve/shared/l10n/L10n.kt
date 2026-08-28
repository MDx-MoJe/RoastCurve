package com.roastcurve.shared.l10n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 语言包运行时（commonMain，三端通用）
 *
 * 设计：
 * - 内置中文（源语言，硬编码兜底）+ 内置英文包
 * - 用户导入 zip 语言包（内含 lang.json），运行时热切换
 * - 键缺失自动回退：语言包 → 内置英文 → 内置中文
 * - 占位符：{name} 形式，运行时替换
 */
object L10n {

    // ==================== 数据模型 ====================

    @Serializable
    data class LangMeta(
        val name: String,          // 语言自称，如 "Deutsch"
        val version: Int = 1,
        val authors: List<String> = emptyList(),
        val base: String = "zh-CN",
    )

    @Serializable
    data class LangPack(
        val meta: LangMeta,
        val strings: Map<String, String>,
    )

    /** 当前生效的语言状态 */
    data class LangState(
        val builtin: BuiltinLang = BuiltinLang.ZH,   // 当前选中的内置语言
        val packName: String? = null,                // 导入的语言包名（null=用内置）
        val pack: LangPack? = null,
        val packCount: Int = 0,                      // 语言包词条数
    ) {
        val displayName: String
            get() = pack?.meta?.name ?: builtin.displayName
    }

    enum class BuiltinLang(val displayName: String, val code: String) {
        ZH("简体中文", "zh-CN"),
        EN("English", "en"),
    }

    // ==================== 状态 ====================

    private val _state = MutableStateFlow(LangState())
    val state: StateFlow<LangState> = _state

    private val json = Json { ignoreUnknownKeys = true }

    /** 读取语言包 JSON（供平台层从 zip 解出后调用） */
    fun parsePack(jsonText: String): Result<LangPack> = try {
        val p = json.decodeFromString<LangPack>(jsonText)
        require(p.meta.name.isNotBlank()) { "meta.name 不能为空" }
        require(p.strings.isNotEmpty()) { "strings 不能为空" }
        Result.success(p)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ==================== 查询 ====================

    /** 核心查询：pack → builtin EN → 内置中文（源串） */
    fun get(key: String): String {
        val s = _state.value
        s.pack?.strings?.get(key)?.let { return it }
        if (s.pack == null && s.builtin == BuiltinLang.EN) {
            BuiltinEn.strings[key]?.let { return it }
        }
        return ZhSource.strings[key] ?: key
    }

    /** 带占位符查询：L10n.get("weight.loss", "rate" to 12.5) */
    fun get(key: String, vararg params: Pair<String, Any?>): String {
        var s = get(key)
        params.forEach { (k, v) -> s = s.replace("{$k}", v.toString()) }
        return s
    }

    // ==================== 切换 ====================

    fun selectBuiltin(lang: BuiltinLang) {
        _state.value = _state.value.copy(builtin = lang, packName = null, pack = null, packCount = 0)
    }

    fun applyPack(pack: LangPack) {
        _state.value = LangState(
            builtin = _state.value.builtin,
            packName = pack.meta.name,
            pack = pack,
            packCount = pack.strings.size,
        )
    }

    fun useBuiltin() {
        _state.value = _state.value.copy(packName = null, pack = null, packCount = 0)
    }
}
