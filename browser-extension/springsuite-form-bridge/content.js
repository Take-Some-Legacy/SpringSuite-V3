(() => {
  "use strict";

  const PAGE_ID = typeof crypto?.randomUUID === "function"
    ? crypto.randomUUID()
    : `page-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const MAX_FORMS = 64;
  const MAX_FIELDS_PER_FORM = 256;
  const MAX_OPTIONS_PER_FIELD = 100;
  const NON_DATA_INPUT_TYPES = new Set(["hidden", "button", "submit", "reset", "image"]);
  const FIELD_SELECTOR = "input, select, textarea";
  const SUBMIT_SELECTOR = "button[type='submit'], input[type='submit'], button:not([type])";
  const SENSITIVE_PATTERN = /(password|passcode|secret|token|api[-_ ]?key|credit|card|cvv|cvc|iban|bank|ssn|social[-_ ]?security|passport|one[-_ ]?time[-_ ]?code)/i;
  let timer = 0;
  let lastFingerprint = "";

  function schedule(force = false) {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => sendSnapshot(force), force ? 20 : 250);
  }

  async function sendSnapshot(force) {
    const payload = collectSnapshot();
    const fingerprint = stableFingerprint(payload);
    if (!force && fingerprint === lastFingerprint) {
      return;
    }
    lastFingerprint = fingerprint;
    try {
      await chrome.runtime.sendMessage({ type: "SPRINGSUITE_FORM_SNAPSHOT", payload });
    } catch (_) {
      // The background worker may be restarting. The heartbeat retries automatically.
    }
  }

  function collectSnapshot() {
    const activeElement = document.activeElement instanceof Element ? document.activeElement : null;
    const actualForms = Array.from(document.forms || []).slice(0, MAX_FORMS);
    const forms = actualForms.map((form, index) => collectForm(form, index, activeElement));
    const orphanFields = Array.from(document.querySelectorAll(FIELD_SELECTOR)).filter((element) => !element.form);
    if (orphanFields.length > 0 && forms.length < MAX_FORMS) {
      forms.push(collectSyntheticForm(orphanFields, forms.length, activeElement));
    }

    return {
      schema: "spring-suite.browser_dom_snapshot.v1",
      pageId: PAGE_ID,
      capturedAt: new Date().toISOString(),
      url: location.href,
      title: cleanText(document.title || location.hostname),
      language: document.documentElement.lang || navigator.language || "",
      browser: "chromium-extension",
      activeElementSelector: activeElement ? selectorFor(activeElement) : "",
      forms,
      metadata: {
        generator: "springsuite-form-bridge",
        generatorVersion: chrome.runtime.getManifest().version,
        origin: location.origin,
        path: location.pathname,
        formCount: forms.length,
        viewport: {
          width: window.innerWidth,
          height: window.innerHeight,
          devicePixelRatio: window.devicePixelRatio || 1
        }
      }
    };
  }

  function collectForm(form, index, activeElement) {
    const controls = Array.from(form.elements || []).filter(isRecognizableField).slice(0, MAX_FIELDS_PER_FORM);
    const formSelector = selectorFor(form) || `form:nth-of-type(${index + 1})`;
    return {
      id: `dom:${formSelector}`,
      name: firstText(
        form.getAttribute("aria-label"),
        form.getAttribute("name"),
        form.id,
        form.querySelector("legend")?.textContent,
        `Form ${index + 1}`
      ),
      action: form.action || location.href,
      method: (form.method || "get").toLowerCase(),
      active: Boolean(activeElement && (activeElement.form === form || form.contains(activeElement))),
      fields: controls.map((field, fieldIndex) => collectField(field, fieldIndex, formSelector, activeElement)),
      submitControls: Array.from(form.elements || [])
        .filter(isSubmitControl)
        .slice(0, 32)
        .map((button, buttonIndex) => collectSubmit(button, buttonIndex)),
      metadata: {
        cssSelector: formSelector,
        autocomplete: form.autocomplete || "",
        encoding: form.enctype || "",
        target: form.target || "",
        noValidate: Boolean(form.noValidate),
        synthetic: false
      }
    };
  }

  function collectSyntheticForm(fields, index, activeElement) {
    const formSelector = "document:orphan-controls";
    return {
      id: `dom:${formSelector}`,
      name: firstText(document.querySelector("main h1")?.textContent, document.title, `Page form ${index + 1}`),
      action: location.href,
      method: "get",
      active: Boolean(activeElement && fields.includes(activeElement)),
      fields: fields.filter(isRecognizableField).slice(0, MAX_FIELDS_PER_FORM).map((field, fieldIndex) => collectField(field, fieldIndex, formSelector, activeElement)),
      submitControls: Array.from(document.querySelectorAll(SUBMIT_SELECTOR))
        .filter((button) => !button.form)
        .slice(0, 32)
        .map((button, buttonIndex) => collectSubmit(button, buttonIndex)),
      metadata: {
        cssSelector: formSelector,
        synthetic: true,
        reason: "form-associated controls without a form owner"
      }
    };
  }

  function collectField(field, index, formSelector, activeElement) {
    const type = fieldType(field);
    const selector = selectorFor(field) || `${field.tagName.toLowerCase()}:nth-of-type(${index + 1})`;
    const label = labelFor(field);
    const bounds = screenBounds(field);
    const options = field instanceof HTMLSelectElement
      ? Array.from(field.options).slice(0, MAX_OPTIONS_PER_FIELD).map((option) => cleanText(option.label || option.textContent || option.value))
      : [];
    const autocomplete = field.getAttribute("autocomplete") || "";
    const sensitive = type === "password" || SENSITIVE_PATTERN.test([
      field.id,
      field.getAttribute("name"),
      label,
      autocomplete,
      field.getAttribute("aria-label")
    ].filter(Boolean).join(" "));

    return {
      id: `dom:${selector}`,
      label,
      name: field.getAttribute("name") || field.id || selector,
      type,
      role: field.getAttribute("role") || roleFor(field, type),
      placeholder: field.getAttribute("placeholder") || "",
      required: Boolean(field.required || field.getAttribute("aria-required") === "true"),
      focused: field === activeElement,
      sensitive,
      readOnly: Boolean(field.readOnly || field.getAttribute("aria-readonly") === "true"),
      disabled: Boolean(field.disabled || field.getAttribute("aria-disabled") === "true"),
      visible: isVisible(field),
      valuePresent: valuePresent(field),
      options,
      metadata: {
        cssSelector: selector,
        formSelector,
        tagName: field.tagName.toLowerCase(),
        autocomplete,
        inputMode: field.getAttribute("inputmode") || "",
        min: field.getAttribute("min") || "",
        max: field.getAttribute("max") || "",
        step: field.getAttribute("step") || "",
        pattern: field.getAttribute("pattern") || "",
        minLength: numericProperty(field, "minLength"),
        maxLength: numericProperty(field, "maxLength"),
        multiple: Boolean(field.multiple),
        bounds
      }
    };
  }

  function collectSubmit(button, index) {
    const selector = selectorFor(button) || `${button.tagName.toLowerCase()}:nth-of-type(${index + 1})`;
    return {
      id: `dom:${selector}`,
      label: firstText(button.getAttribute("aria-label"), button.value, button.textContent, `Submit ${index + 1}`),
      type: (button.getAttribute("type") || "submit").toLowerCase(),
      disabled: Boolean(button.disabled || button.getAttribute("aria-disabled") === "true"),
      metadata: {
        cssSelector: selector,
        tagName: button.tagName.toLowerCase(),
        bounds: screenBounds(button)
      }
    };
  }

  function isRecognizableField(element) {
    if (!(element instanceof HTMLElement)) {
      return false;
    }
    const tag = element.tagName.toLowerCase();
    if (!new Set(["input", "select", "textarea"]).has(tag)) {
      return false;
    }
    return !(element instanceof HTMLInputElement && NON_DATA_INPUT_TYPES.has(element.type.toLowerCase()));
  }

  function isSubmitControl(element) {
    if (element instanceof HTMLButtonElement) {
      return (element.getAttribute("type") || "submit").toLowerCase() === "submit";
    }
    return element instanceof HTMLInputElement && element.type.toLowerCase() === "submit";
  }

  function fieldType(field) {
    if (field instanceof HTMLSelectElement) {
      return field.multiple ? "select-multiple" : "select-one";
    }
    if (field instanceof HTMLTextAreaElement) {
      return "textarea";
    }
    return (field.getAttribute("type") || "text").toLowerCase();
  }

  function roleFor(field, type) {
    if (field instanceof HTMLSelectElement) return field.multiple ? "listbox" : "combobox";
    if (type === "checkbox") return "checkbox";
    if (type === "radio") return "radio";
    if (type === "range") return "slider";
    return "textbox";
  }

  function valuePresent(field) {
    if (field instanceof HTMLInputElement) {
      if (field.type === "checkbox" || field.type === "radio") return field.checked;
      if (field.type === "file") return Boolean(field.files && field.files.length > 0);
      return Boolean(field.value);
    }
    if (field instanceof HTMLSelectElement) return field.selectedIndex >= 0 && Boolean(field.value);
    if (field instanceof HTMLTextAreaElement) return Boolean(field.value);
    return false;
  }

  function labelFor(field) {
    const nativeLabels = field.labels ? Array.from(field.labels).map((label) => cleanText(label.textContent)).filter(Boolean) : [];
    const labelledBy = (field.getAttribute("aria-labelledby") || "")
      .split(/\s+/)
      .filter(Boolean)
      .map((id) => cleanText(document.getElementById(id)?.textContent))
      .filter(Boolean);
    return firstText(
      nativeLabels.join(" / "),
      field.getAttribute("aria-label"),
      labelledBy.join(" / "),
      field.getAttribute("placeholder"),
      field.getAttribute("name"),
      field.id,
      fieldType(field)
    );
  }

  function selectorFor(element) {
    if (!(element instanceof Element)) return "";
    if (element.id && document.querySelectorAll(`#${escapeCss(element.id)}`).length === 1) {
      return `#${escapeCss(element.id)}`;
    }
    const testId = element.getAttribute("data-testid") || element.getAttribute("data-test-id");
    if (testId) {
      const selector = `[data-testid="${escapeAttribute(testId)}"], [data-test-id="${escapeAttribute(testId)}"]`;
      if (document.querySelectorAll(selector).length === 1) return selector;
    }
    const name = element.getAttribute("name");
    if (name) {
      const selector = `${element.tagName.toLowerCase()}[name="${escapeAttribute(name)}"]`;
      if (document.querySelectorAll(selector).length === 1) return selector;
    }

    const parts = [];
    let current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE && current !== document.documentElement) {
      const tag = current.tagName.toLowerCase();
      const siblings = current.parentElement
        ? Array.from(current.parentElement.children).filter((sibling) => sibling.tagName === current.tagName)
        : [];
      const suffix = siblings.length > 1 ? `:nth-of-type(${siblings.indexOf(current) + 1})` : "";
      parts.unshift(`${tag}${suffix}`);
      current = current.parentElement;
      if (parts.length >= 8) break;
    }
    return parts.join(" > ");
  }

  function isVisible(element) {
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== "none"
      && style.visibility !== "hidden"
      && Number(style.opacity || 1) > 0
      && rect.width > 0
      && rect.height > 0;
  }

  function screenBounds(element) {
    const rect = element.getBoundingClientRect();
    const browserChromeHeight = Math.max(0, window.outerHeight - window.innerHeight);
    const left = Math.round(window.screenX + rect.left);
    const top = Math.round(window.screenY + browserChromeHeight + rect.top);
    const width = Math.round(rect.width);
    const height = Math.round(rect.height);
    return { left, top, right: left + width, bottom: top + height, width, height };
  }

  function stableFingerprint(payload) {
    const stable = {
      url: payload.url,
      activeElementSelector: payload.activeElementSelector,
      forms: payload.forms.map((form) => ({
        id: form.id,
        action: form.action,
        method: form.method,
        active: form.active,
        fields: form.fields.map((field) => ({
          id: field.id,
          type: field.type,
          required: field.required,
          focused: field.focused,
          sensitive: field.sensitive,
          disabled: field.disabled,
          readOnly: field.readOnly,
          visible: field.visible,
          valuePresent: field.valuePresent,
          options: field.options
        }))
      }))
    };
    return JSON.stringify(stable);
  }

  function numericProperty(element, name) {
    const value = Number(element[name]);
    return Number.isFinite(value) && value >= 0 ? value : -1;
  }

  function cleanText(value) {
    return String(value || "").replace(/\s+/g, " ").trim().slice(0, 500);
  }

  function firstText(...values) {
    for (const value of values) {
      const normalized = cleanText(value);
      if (normalized) return normalized;
    }
    return "";
  }

  function escapeCss(value) {
    if (globalThis.CSS && typeof CSS.escape === "function") return CSS.escape(String(value));
    return String(value).replace(/[^a-zA-Z0-9_-]/g, (character) => `\\${character}`);
  }

  function escapeAttribute(value) {
    return String(value).replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
  }

  document.addEventListener("focusin", () => schedule(false), true);
  document.addEventListener("input", () => schedule(false), true);
  document.addEventListener("change", () => schedule(false), true);
  document.addEventListener("submit", () => schedule(true), true);

  const observer = new MutationObserver(() => schedule(false));
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: [
      "action", "method", "name", "id", "type", "role", "required", "disabled", "readonly",
      "placeholder", "autocomplete", "aria-label", "aria-labelledby", "aria-required", "aria-disabled"
    ]
  });

  schedule(true);
  window.setInterval(() => sendSnapshot(true), 5000);
})();
