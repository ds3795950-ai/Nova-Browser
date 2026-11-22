(() => {
  try {
    const text = (document.body && document.body.innerText || "").trim().slice(0, 24000);
    if (!text) return;
    browser.runtime.sendNativeMessage("nova_ai_reader", { type: "nova_page_text", text });
  } catch (_) {}
})();
