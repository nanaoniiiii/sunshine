package com.alkaidlab.sunshine.service

/**
 * sunshine.conf 解析器：把 key=value 文本行解析为只读 Map。
 *
 * 从 [SunshineRuntimeManager.reloadConfig] 抽出的纯函数（无 Android/API 依赖），
 * 解析语义与原内联实现完全一致：空行/井号注释行/不含 "=" 的行被忽略，
 * 键与值两侧的空白被 trim，同名键后出现的覆盖先出现的。
 */
internal object SunshineConfParser {
    fun parse(lines: List<String>): Map<String, String> {
        val props = mutableMapOf<String, String>()
        lines.forEach { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#") && t.contains("=")) {
                val parts = t.split("=", limit = 2)
            props[parts[0].trim()] = parts[1].trim()
            }
        }
        return props
    }
}
