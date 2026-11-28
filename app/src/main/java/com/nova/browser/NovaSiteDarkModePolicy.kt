package com.nova.browser

/** Per-site dark mode preference independent of app chrome theme. */
internal enum class NovaSiteDarkMode(val stored: String) {
    DEFAULT("default"),
    FORCE_DARK("force_dark"),
    FORCE_LIGHT("force_light");

    companion object {
        fun fromStored(value: String?): NovaSiteDarkMode =
            entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

internal object NovaSiteDarkModePolicy {
    fun cssFor(mode: NovaSiteDarkMode): String? = when (mode) {
        NovaSiteDarkMode.DEFAULT -> null
        NovaSiteDarkMode.FORCE_DARK -> """
            (function(){
              try {
                var s=document.getElementById('nova-force-color-scheme');
                if(!s){s=document.createElement('style');s.id='nova-force-color-scheme';document.documentElement.appendChild(s);}
                s.textContent=':root,html,body{color-scheme:dark !important;background-color:#121212 !important;color:#eee !important;}a{color:#8ab4f8 !important;}';
                document.documentElement.style.colorScheme='dark';
              } catch(e) {}
            })();
            """.trimIndent()
        NovaSiteDarkMode.FORCE_LIGHT -> """
            (function(){
              try {
                var s=document.getElementById('nova-force-color-scheme');
                if(!s){s=document.createElement('style');s.id='nova-force-color-scheme';document.documentElement.appendChild(s);}
                s.textContent=':root,html,body{color-scheme:light !important;background-color:#fff !important;color:#111 !important;}';
                document.documentElement.style.colorScheme='light';
              } catch(e) {}
            })();
            """.trimIndent()
    }
}
