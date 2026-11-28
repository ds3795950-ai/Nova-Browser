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
    fun cssFor(mode: NovaSiteDarkMode): String = when (mode) {
        NovaSiteDarkMode.DEFAULT -> """
            (function(){
              try {
                var root=document.documentElement;
                var style=document.getElementById('nova-force-color-scheme');
                var raw=root.getAttribute('data-nova-original-color-scheme');
                // A default site with no Nova override must remain completely untouched.
                if(!style && raw===null) return;
                if(style) style.remove();
                if(raw!==null) {
                  var original=JSON.parse(raw);
                  if(original.value) root.style.setProperty('color-scheme',original.value,original.priority || '');
                  else root.style.removeProperty('color-scheme');
                  root.removeAttribute('data-nova-original-color-scheme');
                }
              } catch(e) {}
            })();
        """.trimIndent()
        NovaSiteDarkMode.FORCE_DARK -> """
            (function(){
              try {
                var root=document.documentElement;
                var attr='data-nova-original-color-scheme';
                if(!root.hasAttribute(attr)) {
                  root.setAttribute(attr,JSON.stringify({
                    value:root.style.getPropertyValue('color-scheme'),
                    priority:root.style.getPropertyPriority('color-scheme')
                  }));
                }
                var style=document.getElementById('nova-force-color-scheme');
                if(!style){style=document.createElement('style');style.id='nova-force-color-scheme';root.appendChild(style);}
                style.textContent=':root,html,body{color-scheme:dark !important;background-color:#121212 !important;color:#eee !important;}a{color:#8ab4f8 !important;}';
                root.style.setProperty('color-scheme','dark','important');
              } catch(e) {}
            })();
        """.trimIndent()
        NovaSiteDarkMode.FORCE_LIGHT -> """
            (function(){
              try {
                var root=document.documentElement;
                var attr='data-nova-original-color-scheme';
                if(!root.hasAttribute(attr)) {
                  root.setAttribute(attr,JSON.stringify({
                    value:root.style.getPropertyValue('color-scheme'),
                    priority:root.style.getPropertyPriority('color-scheme')
                  }));
                }
                var style=document.getElementById('nova-force-color-scheme');
                if(!style){style=document.createElement('style');style.id='nova-force-color-scheme';root.appendChild(style);}
                style.textContent=':root,html,body{color-scheme:light !important;background-color:#fff !important;color:#111 !important;}';
                root.style.setProperty('color-scheme','light','important');
              } catch(e) {}
            })();
        """.trimIndent()
    }
}
