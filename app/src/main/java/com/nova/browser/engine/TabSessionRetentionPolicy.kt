package com.nova.browser.engine

/** Selects which tab runtimes stay alive while older tabs sleep and reload lazily. */
internal object TabSessionRetentionPolicy {
    fun retainedTabIds(
        tabLastAccess: List<Pair<String, Long>>,
        activeTabId: String?,
        splitTabId: String?,
        maxLiveTabs: Int = 4
    ): Set<String> {
        require(maxLiveTabs > 0) { "At least one live tab must be retained" }
        val latestAccessById = linkedMapOf<String, Long>()
        tabLastAccess.forEach { (id, accessedAt) ->
            if (id.isNotBlank()) latestAccessById[id] = maxOf(latestAccessById[id] ?: Long.MIN_VALUE, accessedAt)
        }
        val retained = linkedSetOf<String>()
        activeTabId?.takeIf(latestAccessById::containsKey)?.let(retained::add)
        splitTabId?.takeIf(latestAccessById::containsKey)?.let(retained::add)
        latestAccessById.entries
            .asSequence()
            .filterNot { it.key in retained }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take((maxLiveTabs - retained.size).coerceAtLeast(0))
            .forEach { retained.add(it.key) }
        return retained
    }
}
