(() => {
  const LS_KEY = "muxai.adminApiKey";

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  const state = {
    apiKey: localStorage.getItem(LS_KEY) || "",
    loadedTabs: new Set(),
  };

  // --- tabs -----------------------------------------------------------------
  $$(".tab").forEach((btn) => {
    btn.addEventListener("click", () => activateTab(btn.dataset.tab));
  });

  function activateTab(name) {
    $$(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    $$(".tab-panel").forEach((p) => p.classList.toggle("active", p.id === `tab-${name}`));
    if (!state.loadedTabs.has(name)) {
      state.loadedTabs.add(name);
      if (name === "overview") loadOverview();
      if (name === "models") loadModels();
    }
  }

  // --- api key dialog -------------------------------------------------------
  const keyDialog = $("#key-dialog");
  const keyInput = $("#key-input");
  const keyBtn = $("#key-btn");

  keyBtn.addEventListener("click", openKeyDialog);
  $("#key-dialog form").addEventListener("submit", (e) => {
    const btn = e.submitter;
    if (btn && btn.value === "save") {
      const v = keyInput.value.trim();
      if (v) {
        state.apiKey = v;
        localStorage.setItem(LS_KEY, v);
      } else {
        state.apiKey = "";
        localStorage.removeItem(LS_KEY);
      }
      state.loadedTabs.clear();
      // reload whichever tab is visible
      const active = $$(".tab.active")[0];
      if (active) activateTab(active.dataset.tab);
      pollHealth();
    }
  });

  function openKeyDialog() {
    keyInput.value = state.apiKey || "";
    keyDialog.showModal();
    keyInput.focus();
  }

  // --- fetch helpers --------------------------------------------------------
  async function authFetch(path, init = {}) {
    if (!state.apiKey) {
      const err = new Error("No API key set. Click 'API key' in the top-right.");
      err.noKey = true;
      throw err;
    }
    const headers = Object.assign({}, init.headers || {}, {
      Authorization: `Bearer ${state.apiKey}`,
    });
    const res = await fetch(path, Object.assign({}, init, { headers }));
    if (!res.ok) {
      let body = await res.text();
      try { body = JSON.stringify(JSON.parse(body), null, 2); } catch (_) {}
      const err = new Error(`HTTP ${res.status} ${res.statusText}\n${body}`);
      err.status = res.status;
      throw err;
    }
    const ct = res.headers.get("content-type") || "";
    return ct.includes("application/json") ? res.json() : res.text();
  }

  async function plainFetch(path) {
    const res = await fetch(path);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  // --- health ---------------------------------------------------------------
  async function pollHealth() {
    const pill = $("#health-pill");
    try {
      const h = await plainFetch("/actuator/health");
      const ok = h && h.status === "UP";
      pill.textContent = `health: ${ok ? "up" : (h.status || "unknown").toLowerCase()}`;
      pill.className = `pill ${ok ? "pill-ok" : "pill-bad"}`;
    } catch (_) {
      pill.textContent = "health: unreachable";
      pill.className = "pill pill-bad";
    }
  }

  // --- overview -------------------------------------------------------------
  async function loadOverview() {
    const errEl = $("#overview-error");
    errEl.classList.add("hidden");
    try {
      const data = await authFetch("/admin/api/overview");
      renderProviders(data.providers || []);
      renderRoutes(data.routes || []);
      renderKeys(data.apiKeys || []);
    } catch (e) {
      errEl.textContent = e.message;
      errEl.classList.remove("hidden");
      renderProviders([]); renderRoutes([]); renderKeys([]);
      if (e.noKey) openKeyDialog();
    }
  }

  function renderProviders(rows) {
    const tbody = $("#providers-table tbody");
    tbody.innerHTML = "";
    $("#providers-empty").classList.toggle("hidden", rows.length > 0);
    for (const p of rows) {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td class="mono">${esc(p.id)}</td>
        <td>${esc(p.type)}</td>
        <td class="mono">${esc(p.baseUrl || "")}</td>
        <td class="mono">${p.apiKeyMasked ? esc(p.apiKeyMasked) : '<span class="tag-dim">—</span>'}</td>
        <td>${p.timeoutMs} ms</td>
        <td>${(p.models || []).map((m) => `<span class="tag">${esc(m)}</span>`).join("")}</td>
      `;
      tbody.appendChild(tr);
    }
  }

  function renderRoutes(rows) {
    const tbody = $("#routes-table tbody");
    tbody.innerHTML = "";
    $("#routes-empty").classList.toggle("hidden", rows.length > 0);
    rows.forEach((r, i) => {
      const m = r.match || {};
      const matchStr = [
        m.appId ? `app=${esc(m.appId)}` : null,
        m.model ? `model=${esc(m.model)}` : null,
      ].filter(Boolean).join(" · ") || '<span class="tag-dim">*</span>';
      const primary = r.primary
        ? `${esc(r.primary.provider)}${r.primary.model ? " / " + esc(r.primary.model) : ""}`
        : "—";
      const fallback = (r.fallback || []).length
        ? r.fallback.map((s) =>
            `<span class="tag">${esc(s.provider)}${s.model ? " / " + esc(s.model) : ""}</span>`
          ).join("")
        : '<span class="tag-dim">—</span>';
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${i + 1}</td>
        <td class="mono">${matchStr}</td>
        <td class="mono">${primary}</td>
        <td>${fallback}</td>
      `;
      tbody.appendChild(tr);
    });
  }

  function renderKeys(rows) {
    const tbody = $("#keys-table tbody");
    tbody.innerHTML = "";
    $("#keys-empty").classList.toggle("hidden", rows.length > 0);
    for (const k of rows) {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td class="mono">${esc(k.keyMasked || "")}</td>
        <td>${esc(k.appId || "")}</td>
        <td>${k.rateLimitPerMin ?? '<span class="tag-dim">—</span>'}</td>
      `;
      tbody.appendChild(tr);
    }
  }

  // --- models ---------------------------------------------------------------
  async function loadModels() {
    const errEl = $("#models-error");
    errEl.classList.add("hidden");
    const tbody = $("#models-table tbody");
    tbody.innerHTML = "";
    try {
      const data = await authFetch("/v1/models");
      for (const m of data.data || []) {
        const tr = document.createElement("tr");
        tr.innerHTML = `<td class="mono">${esc(m.id)}</td><td class="mono">${esc(m.owned_by || "")}</td>`;
        tbody.appendChild(tr);
      }
    } catch (e) {
      errEl.textContent = e.message;
      errEl.classList.remove("hidden");
      if (e.noKey) openKeyDialog();
    }
  }

  // --- playground -----------------------------------------------------------
  $("#pg-send").addEventListener("click", runPlayground);

  async function runPlayground() {
    const sendBtn = $("#pg-send");
    const statusEl = $("#pg-status");
    const outWrap = $("#pg-output-wrap");
    const outEl = $("#pg-output");
    const rawEl = $("#pg-raw");

    const model = $("#pg-model").value.trim();
    const userMsg = $("#pg-user").value;
    const sysMsg = $("#pg-system").value;
    const temp = parseFloat($("#pg-temp").value);
    const maxTokensRaw = $("#pg-max").value.trim();

    if (!model) { statusEl.textContent = "Model is required."; return; }
    if (!userMsg.trim()) { statusEl.textContent = "User message is required."; return; }

    const messages = [];
    if (sysMsg.trim()) messages.push({ role: "system", content: sysMsg });
    messages.push({ role: "user", content: userMsg });

    const body = { model, messages };
    if (!Number.isNaN(temp)) body.temperature = temp;
    if (maxTokensRaw) body.max_tokens = parseInt(maxTokensRaw, 10);

    sendBtn.disabled = true;
    statusEl.textContent = "Sending…";
    outWrap.classList.add("hidden");
    const t0 = performance.now();
    try {
      const resp = await authFetch("/v1/chat/completions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const ms = Math.round(performance.now() - t0);
      const content = resp?.choices?.[0]?.message?.content ?? "(no content)";
      const usage = resp?.usage;
      outEl.textContent = content;
      rawEl.textContent = JSON.stringify(resp, null, 2);
      outWrap.classList.remove("hidden");
      const usageStr = usage
        ? ` · ${usage.prompt_tokens ?? 0} + ${usage.completion_tokens ?? 0} tokens`
        : "";
      statusEl.textContent = `OK · ${ms} ms${usageStr}`;
    } catch (e) {
      outEl.textContent = e.message;
      rawEl.textContent = "";
      outWrap.classList.remove("hidden");
      statusEl.textContent = "Error";
      if (e.noKey) openKeyDialog();
    } finally {
      sendBtn.disabled = false;
    }
  }

  // --- utils ----------------------------------------------------------------
  function esc(s) {
    return String(s ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  // --- boot -----------------------------------------------------------------
  pollHealth();
  setInterval(pollHealth, 15000);
  activateTab("overview");
  if (!state.apiKey) {
    // soft prompt
    setTimeout(openKeyDialog, 250);
  }
})();
