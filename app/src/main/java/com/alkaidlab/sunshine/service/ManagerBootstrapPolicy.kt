package com.alkaidlab.sunshine.service

/** Keep privileged recovery limited to the manager that actually hosts this Binder. */
internal object ManagerBootstrapPolicy {
    val managers = setOf("moe.shizuku.privileged.api", "roro.stellar.manager")

    fun activeManager(classPath: String, installedApks: Map<String, List<String>>): String? {
        val paths = classPath.trim().split(':').filter { it.startsWith('/') }.toSet()
        return installedApks.filter { (name, apks) -> name in managers && apks.any { it in paths } }
            .keys.singleOrNull()
    }

    fun restricted(output: String, operation: String): Boolean =
        Regex("(?:${Regex.escape(operation)}|Default mode):\\s*(ignore|deny|errored)\\b")
            .containsMatchIn(output)

    fun whitelisted(output: String, packageName: String): Boolean = output.lineSequence().any {
        val fields = it.trim().split(',')
        fields.size == 3 && fields[0] in setOf("system", "user") && fields[1] == packageName
    }
}
