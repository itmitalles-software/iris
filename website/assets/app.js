// apollo.wutz.io: language and theme toggles (both persisted), copy buttons.
(function () {
  "use strict";

  var DE = {
    skip: "Zum Inhalt springen",
    navFeatures: "Funktionen",
    navSetup: "Einrichtung",
    navPrivacy: "Datenschutz",
    themeToggle: "Dunkelmodus",
    eyebrow: "Android-Client für Hermes Agent",
    heroTitle: "Dein Agent. In deiner Tasche.",
    heroLead: "Apollo verbindet dein Handy mit dem Hermes Agent auf deinem eigenen Rechner. Chatte, gib Tools frei, setze Sitzungen fort und teile Dateien von überall, privat über Tailscale.",
    download: "APK herunterladen",
    setupCta: "Hermes einrichten",
    heroNote: "Kostenlos und Open Source. Ab Android 8. Kein Konto, keine Cloud, keine Telemetrie.",
    f1Tag: "Chat",
    f1Title: "Sprich mit deinem Agenten",
    f1Text: "Antworten live gestreamt, Markdown und Code, Tool-Aufrufe mit Ergebnissen, Reasoning, Bilder und Slash-Befehle. Sitzungen lenken, stoppen, verzweigen oder komprimieren, oder freihändig im Sprachgespräch reden.",
    f2Tag: "Privat",
    f2Title: "Privat von Grund auf",
    f2Text: "Hermes bleibt auf deinem Rechner. Tailscale Serve liefert HTTPS nur in deinem Tailnet, ohne offene Ports. Zugangsdaten liegen verschlüsselt auf dem Handy.",
    f3Tag: "Freigeben",
    f3Title: "Tools nach deinen Regeln",
    f3Text: "Gib Tool-Aufrufe frei, beantworte Rückfragen, Sudo- und Geheimnis-Abfragen im Chat oder direkt aus der Benachrichtigung.",
    f4Tag: "Erinnern",
    f4Title: "Sitzungen, die dir folgen",
    f4Text: "Sitzungen durchsuchen, anheften und fortsetzen, nach Projekt gruppiert. Nach einem Verbindungsabbruch holt Apollo nach, was du verpasst hast.",
    f5Tag: "Automatisieren",
    f5Title: "Cron und Webhooks",
    f5Text: "Geplante Jobs und Webhooks anlegen und beobachten, mit Benachrichtigung, wenn eine Aufgabe fertig ist.",
    f6Tag: "Ordnen",
    f6Title: "Projekte, Kanban, Dateien",
    f6Text: "Kanban-Boards, Projekte, Skills, MCP-Server und Modelle. Teile Dateien aus jeder App mit Apollo oder öffne, was der Agent erstellt hat.",
    setupTitle: "In vier Schritten eingerichtet",
    s1Title: "Hermes und Tailscale installieren",
    s1Text: 'Installiere <a href="https://github.com/NousResearch/hermes-agent">Hermes Agent</a> auf deinem Rechner. Installiere dann Tailscale auf diesem Rechner und auf deinem Handy und melde beide im selben Tailnet an.',
    s2Title: "Setup-Skript ausführen",
    s2Text: "Es legt einen Login an (gespeichert wird nur ein Hash) und startet das offizielle Hermes-Backend als Benutzerdienst auf 127.0.0.1. Lies es vor dem Ausführen.",
    s3Title: "Im Tailnet veröffentlichen",
    s3Text: "Tailscale Serve ergänzt HTTPS mit gültigem Zertifikat, erreichbar nur in deinem Tailnet.",
    s4Title: "Mit Apollo anmelden",
    s4Text: "Installiere die APK, tippe auf „Auth-Login“, gib die Tailnet-Adresse deines Rechners ein (https://<rechner>.<tailnet>.ts.net) und melde dich an.",
    setupMore: "Manuelle Einrichtung und Systeme ohne systemd:",
    copy: "Kopieren",
    copied: "Kopiert",
    copyFailed: "Kopieren fehlgeschlagen",
    copiedStatus: "Befehl in die Zwischenablage kopiert",
    langLabel: "EN – Switch to English",
    privacyTitle: "Keine Cloud dazwischen",
    p1: "Keine Telemetrie, Werbung oder Tracker.",
    p2: "Zugangsdaten verschlüsselt mit dem Android Keystore.",
    p3: "Normale TLS-Prüfung, keine offenen Ports nötig.",
    p4: "Nutzt nur die offiziellen Hermes-Dashboard- und Backend-Schnittstellen.",
    p5: "Die einzige andere Anfrage ist die optionale Update-Prüfung bei GitHub.",
    disclaimer: "Apollo ist ein unabhängiger Open-Source-Android-Client für Hermes Agent, entwickelt von wutz.io. Nicht mit Nous Research verbunden und nicht von Nous Research unterstützt.",
    credit: 'Basiert auf <a href="https://github.com/Hy4ri/hermes-mobile">Hermes Mobile</a> von Hy4ri (Apache-2.0).',
    license: "Lizenz",
    title: "Apollo — Android-Client für Hermes Agent"
  };

  var root = document.documentElement;
  var EN = {};
  document.querySelectorAll("[data-i18n]").forEach(function (el) { EN[el.dataset.i18n] = el.textContent; });
  document.querySelectorAll("[data-i18n-html]").forEach(function (el) { EN[el.dataset.i18nHtml] = el.innerHTML; });
  document.querySelectorAll("[data-i18n-aria]").forEach(function (el) { EN[el.dataset.i18nAria] = el.getAttribute("aria-label"); });
  EN.copy = "Copy";
  EN.copied = "Copied";
  EN.copyFailed = "Copy failed";
  EN.copiedStatus = "Command copied to the clipboard";
  EN.langLabel = "DE – Auf Deutsch umschalten";
  EN.title = document.title;

  function store(key, value) { try { localStorage.setItem(key, value); } catch (e) {} }
  function t(key) { return (root.lang === "de" ? DE : EN)[key]; }

  function applyLang() {
    document.querySelectorAll("[data-i18n]").forEach(function (el) { el.textContent = t(el.dataset.i18n); });
    document.querySelectorAll("[data-i18n-html]").forEach(function (el) { el.innerHTML = t(el.dataset.i18nHtml); });
    document.querySelectorAll("[data-i18n-aria]").forEach(function (el) { el.setAttribute("aria-label", t(el.dataset.i18nAria)); });
    document.title = t("title");
    var lang = document.getElementById("lang");
    lang.textContent = root.lang === "de" ? "EN" : "DE";
    // Named in the target language and containing the visible text (WCAG 2.5.3).
    lang.lang = root.lang === "de" ? "en" : "de";
    lang.setAttribute("aria-label", t("langLabel"));
  }

  function applyTheme() {
    document.querySelector('meta[name="theme-color"]').content = root.dataset.theme === "light" ? "#F7F4EE" : "#000000";
    document.querySelector('link[rel="icon"]').href =
      root.dataset.theme === "light" ? "assets/apollo-emblem-light.svg" : "assets/apollo-emblem-dark.svg";
    document.getElementById("theme").setAttribute("aria-pressed", root.dataset.theme === "light" ? "false" : "true");
  }

  document.getElementById("lang").addEventListener("click", function () {
    root.lang = root.lang === "de" ? "en" : "de";
    store("apollo-lang", root.lang);
    applyLang();
  });
  document.getElementById("theme").addEventListener("click", function () {
    root.dataset.theme = root.dataset.theme === "light" ? "dark" : "light";
    store("apollo-theme", root.dataset.theme);
    applyTheme();
  });
  var status = document.getElementById("copy-status");
  function announce(button, key, message) {
    button.textContent = t(key);
    status.textContent = message;
    setTimeout(function () { button.textContent = t("copy"); status.textContent = ""; }, 1500);
  }
  document.querySelectorAll(".copy").forEach(function (button) {
    button.addEventListener("click", function () {
      var text = document.getElementById(button.dataset.copy).textContent;
      var copy = navigator.clipboard && navigator.clipboard.writeText
        ? navigator.clipboard.writeText(text)
        : Promise.reject(new Error("Clipboard API unavailable"));
      copy.then(
        function () { announce(button, "copied", t("copiedStatus")); },
        function () { announce(button, "copyFailed", t("copyFailed")); }
      );
    });
  });

  applyLang();
  applyTheme();
})();
