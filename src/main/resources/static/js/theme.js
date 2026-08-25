(() => {
  const storageKey = "nextai-theme";
  const preferred = window.localStorage.getItem(storageKey)
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

  window.togglePlatformTheme = () => {
    const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    window.localStorage.setItem(storageKey, theme);
    apply(theme);
  };

  apply(preferred);
  document.addEventListener("DOMContentLoaded", () => apply(document.documentElement.dataset.theme));
})();
