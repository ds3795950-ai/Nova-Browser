package com.nova.browser

/** Owns tab collection and active-index invariants; Activity owns GeckoSession/UI lifecycle. */
internal class NovaTabController {
    val tabs: MutableList<BrowserTab> = mutableListOf()
    var activeIndex: Int = 0
        set(value) { field = if (tabs.isEmpty()) 0 else value.coerceIn(tabs.indices) }
    fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)
    fun add(tab: BrowserTab, activate: Boolean = true) { tabs.add(tab); if (activate) activeIndex = tabs.lastIndex }
    fun activate(index: Int): BrowserTab? = tabs.getOrNull(index)?.also { activeIndex = index }
    fun removeAt(index: Int): BrowserTab? {
        if (index !in tabs.indices) return null
        val wasActive = index == activeIndex
        val removed = tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> 0
            index < activeIndex -> activeIndex - 1
            wasActive -> index.coerceAtMost(tabs.lastIndex)
            else -> activeIndex
        }
        return removed
    }
    fun clear() { tabs.clear(); activeIndex = 0 }
}
