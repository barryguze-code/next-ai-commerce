(() => {
  if (window.__nextAiThemeReady) return;
  window.__nextAiThemeReady = true;
  const storageKey = "nextai-theme";
  const cookieKey = "nextai_theme";
  const textSizeStorageKey = "nextai-text-size";
  const textSizeCookieKey = "nextai_text_size";
  const textSizes = {compact: {rootSize: "14.4px", label: "Compact", index: 0}, standard: {rootSize: "16px", label: "Standard", index: 1}, larger: {rootSize: "17.92px", label: "Larger", index: 2}};
  const cookieTheme = () => document.cookie.split(";").map(value => value.trim())
    .find(value => value.startsWith(cookieKey + "="))?.split("=")[1];
  const remember = theme => {
    window.localStorage.setItem(storageKey, theme);
    document.cookie = `${cookieKey}=${theme}; Max-Age=31536000; Path=/; SameSite=Lax`;
  };
  const cookieValue = key => document.cookie.split(";").map(value => value.trim())
    .find(value => value.startsWith(key + "="))?.split("=")[1];
  const rememberTextSize = size => {
    window.localStorage.setItem(textSizeStorageKey, size);
    document.cookie = `${textSizeCookieKey}=${size}; Max-Age=31536000; Path=/; SameSite=Lax`;
  };
  const preferred = cookieTheme() || window.localStorage.getItem(storageKey)
    || (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");

  const apply = theme => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    document.querySelectorAll("[data-theme-toggle]").forEach(button => {
      const dark = theme === "dark";
      button.setAttribute("aria-label", dark ? "Use light appearance" : "Use dark appearance");
      button.setAttribute("title", dark ? "Use light appearance" : "Use dark appearance");
      button.setAttribute("aria-pressed", String(dark));
    });
  };

  const applyTextSize = value => {
    const size = textSizes[value] ? value : "standard";
    const setting = textSizes[size];
    document.documentElement.dataset.textSize = size;
    document.documentElement.style.setProperty("--platform-root-font-size", setting.rootSize);
    document.querySelectorAll("[data-text-size-range]").forEach(input => input.value = setting.index);
    document.querySelectorAll("[data-text-size-output]").forEach(output => output.textContent = setting.label);
    document.querySelectorAll("[data-text-size-toggle]").forEach(button => button.title = `Text size: ${setting.label}`);
  };

  window.setPlatformTextSize = size => {
    const normalized = textSizes[size] ? size : "standard";
    rememberTextSize(normalized);
    applyTextSize(normalized);
  };

  window.toggleTextSizeMenu = (button, event) => {
    event?.stopPropagation();
    const menu = button.closest(".text-size-control")?.querySelector("[data-text-size-menu]");
    if (!menu) return;
    const opening = menu.hidden;
    document.querySelectorAll("[data-text-size-menu]").forEach(item => item.hidden = true);
    document.querySelectorAll("[data-text-size-toggle]").forEach(item => item.setAttribute("aria-expanded", "false"));
    menu.hidden = !opening;
    button.setAttribute("aria-expanded", String(opening));
  };

  window.togglePlatformTheme = () => {
    const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    remember(theme);
    apply(theme);
  };

  const showThemeWelcome = () => {
    if (!document.body.classList.contains("app-page") || cookieTheme()) return;
    const dialog = document.createElement("dialog");
    dialog.className = "theme-welcome";
    dialog.setAttribute("aria-labelledby", "theme-welcome-title");
    dialog.innerHTML = `
      <div class="theme-welcome-shell">
        <div class="theme-welcome-mark" aria-hidden="true">◐</div>
        <div class="theme-welcome-copy">
          <span>Appearance</span>
          <h2 id="theme-welcome-title">Make the workspace yours</h2>
          <p>Choose the appearance that feels most comfortable. You can change it anytime from the sun or moon beside your profile.</p>
        </div>
        <div class="theme-choices" role="group" aria-label="Choose appearance">
          <button type="button" data-theme-choice="light">
            <span class="theme-preview light-preview"><i></i><b></b><em></em></span>
            <strong>Light</strong><small>Bright and clear</small>
          </button>
          <button type="button" data-theme-choice="dark">
            <span class="theme-preview dark-preview"><i></i><b></b><em></em></span>
            <strong>Dark</strong><small>Calm and low glare</small>
          </button>
        </div>
        <small class="theme-welcome-note">Your choice is remembered on this browser.</small>
      </div>`;
    dialog.addEventListener("cancel", event => event.preventDefault());
    dialog.querySelectorAll("[data-theme-choice]").forEach(button => button.addEventListener("click", () => {
      const theme = button.dataset.themeChoice;
      remember(theme);
      apply(theme);
      dialog.close();
      dialog.remove();
    }));
    document.body.appendChild(dialog);
    dialog.showModal();
  };

  const sortableValue = (row, index) => {
    const cell = row.cells[index];
    return (cell?.dataset.sortValue || cell?.textContent || "").trim();
  };

  const compareValues = (left, right) => {
    if (!left && !right) return 0;
    if (!left) return 1;
    if (!right) return -1;
    const numeric = value => value.replace(/[$,%\s]/g, "").replace(/^[A-Z]{3}/, "");
    const leftNumber = numeric(left);
    const rightNumber = numeric(right);
    if (/^-?\d+(\.\d+)?$/.test(leftNumber) && /^-?\d+(\.\d+)?$/.test(rightNumber)) {
      return Number(leftNumber) - Number(rightNumber);
    }
    return left.localeCompare(right, undefined, {numeric: true, sensitivity: "base"});
  };

  const enableTableSorting = () => {
    document.querySelectorAll("table:not([data-server-sort]):not(.receive-table)").forEach(table => {
      if (table.dataset.sortReady === "true") return;
      const body = table.tBodies[0];
      const headers = table.tHead?.rows[0]?.cells;
      if (!body || !headers || body.rows.length < 2) return;
      table.dataset.sortReady = "true";
      [...headers].forEach((header, index) => {
        if (header.dataset.noSort !== undefined) return;
        header.classList.add("sortable-column");
        header.tabIndex = 0;
        header.setAttribute("aria-sort", "none");
        header.title ||= "Sort this column";
        const sort = () => {
          const direction = header.getAttribute("aria-sort") === "ascending" ? "descending" : "ascending";
          [...headers].forEach(other => other.setAttribute("aria-sort", "none"));
          header.setAttribute("aria-sort", direction);
          const rows = [...body.rows].filter(row => row.cells.length >= headers.length && !row.querySelector("td[colspan]"));
          // Columns can be reordered or receive a leading context control after boot.
          rows.sort((left, right) => compareValues(sortableValue(left, header.cellIndex), sortableValue(right, header.cellIndex)) * (direction === "ascending" ? 1 : -1));
          rows.forEach(row => body.appendChild(row));
        };
        header.addEventListener("click", sort);
        header.addEventListener("keydown", event => {
          if (event.key === "Enter" || event.key === " ") { event.preventDefault(); sort(); }
        });
      });
    });
  };

  const preferredTextSize = cookieValue(textSizeCookieKey) || window.localStorage.getItem(textSizeStorageKey) || "standard";
  apply(preferred);
  applyTextSize(preferredTextSize);
  document.addEventListener("DOMContentLoaded", () => {
    apply(document.documentElement.dataset.theme);
    applyTextSize(document.documentElement.dataset.textSize);
    enableTableSorting();
    showThemeWelcome();
    document.addEventListener("click", event => {
      if (event.target.closest(".text-size-control")) return;
      document.querySelectorAll("[data-text-size-menu]").forEach(item => item.hidden = true);
      document.querySelectorAll("[data-text-size-toggle]").forEach(item => item.setAttribute("aria-expanded", "false"));
    });
    document.addEventListener("keydown", event => {
      if (event.key !== "Escape") return;
      document.querySelectorAll("[data-text-size-menu]").forEach(item => item.hidden = true);
      document.querySelectorAll("[data-text-size-toggle]").forEach(item => item.setAttribute("aria-expanded", "false"));
    });
  });
})();
