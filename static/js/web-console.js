(() => {
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value ?? '').replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
  const asArray = (value) => Array.isArray(value) ? value : [];
  const api = (...parts) => '/' + parts.join('/');

  const state = {
    ready: false,
    busy: false,
    commands: [],
    history: loadHistory(),
    historyIndex: null,
    timer: null,
    started: 0
  };

  function loadHistory() {
    try {
      const raw = localStorage.getItem('springsuite.webConsole.history');
      const list = JSON.parse(raw || '[]');
      return Array.isArray(list) ? list.filter(Boolean).slice(-200) : [];
    } catch (_) { return []; }
  }

  function saveHistory() {
    try { localStorage.setItem('springsuite.webConsole.history', JSON.stringify(state.history.slice(-200))); } catch (_) {}
    setText('web-console-history', `История: ${state.history.length}`);
  }

  function setText(id, text) {
    const el = $(id);
    if (el) el.textContent = text ?? '';
  }

  function setDot(ok) {
    const el = $('web-console-dot');
    if (!el) return;
    el.className = 'dot ' + (state.busy ? 'warn' : ok ? 'good' : 'bad');
  }

  function commandNames() {
    return state.commands.map(item => item.name).filter(Boolean).sort();
  }

  function descriptorOf(line) {
    const name = String(line || '').trim().split(/\s+/)[0].toLowerCase();
    return state.commands.find(item => String(item.name).toLowerCase() === name);
  }

  async function loadCommands() {
    try {
      const response = await fetch(api('api', 'control-panel'));
      const body = await response.json();
      state.commands = asArray(body?.data?.commands);
      state.ready = true;
      setDot(true);
      setText('web-console-pill', `${state.commands.length} команд`);
      saveHistory();
    } catch (error) {
      state.ready = false;
      setDot(false);
      setText('web-console-pill', 'снимок недоступен');
      writeSystem(`Не удалось загрузить реестр команд: ${error.message}`, 'bad');
    }
  }

  function write(entry) {
    const out = $('web-console-output');
    if (!out) return null;
    const div = document.createElement('div');
    div.className = `term-entry ${entry.level || 'system'}`;
    const parts = [];
    if (entry.command) parts.push(`<div class="cmd"><span class="prompt">${esc(prompt())}</span> ${esc(entry.command)}</div>`);
    if (entry.stdout) parts.push(`<div class="stdout">${esc(entry.stdout)}</div>`);
    if (entry.stderr) parts.push(`<div class="stderr">${esc(entry.stderr)}</div>`);
    if (entry.meta) parts.push(`<div class="meta">${esc(entry.meta)}</div>`);
    div.innerHTML = parts.join('') || `<div class="meta">${esc(entry.meta || '')}</div>`;
    out.appendChild(div);
    out.scrollTop = out.scrollHeight;
    return div;
  }

  function writeSystem(text, level = 'system') {
    return write({ level, stdout: text, meta: 'web-console' });
  }

  function prompt() {
    return 'suite@northstar:~$';
  }

  function clearConsole() {
    const out = $('web-console-output');
    if (out) out.innerHTML = '';
    writeSystem('Веб-консоль SpringSuite\nEnter: выполнить команду • Tab: автодополнение • Вверх/Вниз: история • Ctrl+L: очистить • Esc: скрыть подсказки');
  }

  function local(line) {
    const value = line.trim();
    if (!value) return true;
    if (value === 'clear' || value === 'cls') { clearConsole(); return true; }
    if (value === 'history') {
      write({ command: value, stdout: state.history.map((item, i) => `${String(i + 1).padStart(3, ' ')}  ${item}`).join('\n') || '(пусто)', meta: 'локальная история' });
      return true;
    }
    if (value === 'web-help') {
      write({ command: value, stdout: helpText(), meta: 'локальная справка' });
      return true;
    }
    return false;
  }

  function helpText() {
    return [
      'Локальные команды веб-консоли:',
      '  clear      очистить терминал локально',
      '  history    показать локальную историю',
      '  web-help   показать эту справку',
      '',
      'Команды backend:',
      commandNames().join(', ')
    ].join('\n');
  }

  function shouldConfirm(line) {
    const descriptor = descriptorOf(line);
    if (!descriptor) return false;
    const risk = String(descriptor.riskLevel || '').toUpperCase();
    return risk && risk !== 'READ_ONLY';
  }

  async function run(line) {
    const value = line.trim();
    if (!value || state.busy) return;
    hideSuggestions();
    remember(value);
    if (local(value)) { setInput(''); return; }
    const descriptor = descriptorOf(value);
    if (shouldConfirm(value)) {
      const risk = descriptor?.riskLevel || 'NON_READ_ONLY';
      if (!window.confirm(`Выполнить команду ${risk}: ${value}?`)) {
        write({ level: 'warn', command: value, stdout: 'отменено оператором', meta: risk });
        setInput('');
        return;
      }
    }
    setInput('');
    state.busy = true;
    state.started = performance.now();
    setDot(true);
    setText('web-console-state', 'выполнение');
    const row = write({ level: 'warn', command: value, stdout: 'выполнение...', meta: 'запрос начат' });
    state.timer = setInterval(() => {
      const elapsed = Math.round(performance.now() - state.started);
      const dots = '.'.repeat((Math.floor(elapsed / 350) % 3) + 1);
      const stdout = row?.querySelector('.stdout');
      const meta = row?.querySelector('.meta');
      if (stdout) stdout.textContent = `выполнение${dots}`;
      if (meta) meta.textContent = `${elapsed} ms`;
    }, 180);
    try {
      const started = performance.now();
      const response = await fetch(api('api', 'commands', 'execute'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ line: value })
      });
      const text = await response.text();
      let body;
      try { body = JSON.parse(text); } catch (error) { body = { ok: false, code: 'invalid_json', message: error.message, data: text }; }
      const result = body.data || body;
      const payload = result.data || {};
      const stdoutText = payload._stdout ?? result.message ?? body.message ?? '';
      const failed = !response.ok || result.ok === false || body.ok === false;
      const elapsed = Math.round(performance.now() - started);
      finishRow(row, value, stdoutText, failed ? (result.message || body.message || 'команда завершилась ошибкой') : '', `${result.code || body.code || 'ok'} • ${elapsed} ms • ${result.timestamp || body.timestamp || ''}`, failed ? 'bad' : 'good');
      if (!payload._stdout && Object.keys(payload).length) write({ level: 'system', stdout: JSON.stringify(payload, null, 2), meta: 'структурированные данные' });
      loadCommands();
    } catch (error) {
      finishRow(row, value, '', error.message || 'Не удалось выполнить сетевой запрос', 'сетевая ошибка', 'bad');
    } finally {
      if (state.timer) clearInterval(state.timer);
      state.timer = null;
      state.busy = false;
      setDot(true);
      setText('web-console-state', 'готово');
      $('web-console-input')?.focus();
    }
  }

  function finishRow(row, command, stdout, stderr, meta, level) {
    if (!row) return;
    row.className = `term-entry ${level}`;
    row.innerHTML = `<div class="cmd"><span class="prompt">${esc(prompt())}</span> ${esc(command)}</div>${stdout ? `<div class="stdout">${esc(stdout)}</div>` : ''}${stderr ? `<div class="stderr">${esc(stderr)}</div>` : ''}<div class="meta">${esc(meta)}</div>`;
  }

  function remember(value) {
    state.history = [...state.history.filter(item => item !== value), value].slice(-200);
    state.historyIndex = null;
    saveHistory();
  }

  function setInput(value) {
    const input = $('web-console-input');
    if (input) input.value = value;
  }

  function complete() {
    const input = $('web-console-input');
    const raw = input?.value || '';
    const first = raw.trimStart().split(/\s+/)[0] || '';
    const names = commandNames();
    if (!first) return showSuggestions(names);
    const matches = names.filter(name => name.startsWith(first.toLowerCase()));
    if (matches.length === 1) {
      input.value = matches[0] + (raw.includes(' ') ? raw.slice(raw.indexOf(' ')) : ' ');
      hideSuggestions();
    } else showSuggestions(matches);
  }

  function showSuggestions(items) {
    const box = $('web-console-suggestions');
    if (!box) return;
    if (!items.length) return hideSuggestions();
    box.innerHTML = items.map(name => `<button class="suggestion-chip" type="button" data-web-console-suggest="${esc(name)}">${esc(name)}</button>`).join('');
    box.classList.add('is-visible');
  }

  function hideSuggestions() {
    const box = $('web-console-suggestions');
    if (!box) return;
    box.innerHTML = '';
    box.classList.remove('is-visible');
  }

  function bind() {
    $('web-console-form')?.addEventListener('submit', event => { event.preventDefault(); run($('web-console-input')?.value || ''); });
    $('web-console-clear')?.addEventListener('click', clearConsole);
    document.querySelectorAll('[data-web-console-run]').forEach(button => button.addEventListener('click', () => run(button.dataset.webConsoleRun || '')));
    $('web-console-input')?.addEventListener('keydown', event => {
      if (event.key === 'Tab') { event.preventDefault(); complete(); }
      else if (event.key === 'ArrowUp') {
        event.preventDefault();
        const h = state.history;
        if (!h.length) return;
        state.historyIndex = state.historyIndex == null ? h.length - 1 : Math.max(0, state.historyIndex - 1);
        setInput(h[state.historyIndex]);
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        const h = state.history;
        if (!h.length || state.historyIndex == null) return;
        state.historyIndex += 1;
        if (state.historyIndex >= h.length) { state.historyIndex = null; setInput(''); }
        else setInput(h[state.historyIndex]);
      } else if (event.key.toLowerCase() === 'l' && event.ctrlKey) {
        event.preventDefault(); clearConsole();
      } else if (event.key === 'Escape') hideSuggestions();
    });
    $('web-console-suggestions')?.addEventListener('click', event => {
      const chip = event.target.closest('[data-web-console-suggest]');
      if (!chip) return;
      setInput(chip.dataset.webConsoleSuggest + ' ');
      hideSuggestions();
      $('web-console-input')?.focus();
    });
  }

  function init() {
    if (!$('web-console-output')) return;
    bind();
    clearConsole();
    saveHistory();
    loadCommands();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
