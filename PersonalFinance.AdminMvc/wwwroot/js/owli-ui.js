"use strict";

(() => {
    const root = document.documentElement;
    const themeKey = "owli-admin-theme";
    const sidebarKey = "owli-admin-sidebar";
    const read = key => {
        try { return localStorage.getItem(key); } catch { return null; }
    };
    const save = (key, value) => {
        try { localStorage.setItem(key, value); } catch { /* Preferences are optional. */ }
    };
    const preferredTheme = read(themeKey);
    root.dataset.theme = preferredTheme === "dark" || preferredTheme === "light"
        ? preferredTheme : (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("change", event => {
            const choice = event.target;
            if (!choice.matches('[data-category-palette] input[type="radio"]:checked')) return;
            choice.form.querySelector("[data-category-color-value]").value = choice.value;
        });

        const themeButtons = document.querySelectorAll("[data-theme-toggle], [data-theme-choice]");
        function setTheme(theme) {
            root.dataset.theme = theme;
            for (const button of themeButtons) {
                button.hidden = false;
                const choice = button.dataset.themeChoice ?? "dark";
                button.setAttribute("aria-pressed", String(theme === choice));
                if (button.hasAttribute("data-theme-toggle")) {
                    button.setAttribute("aria-label", theme === "dark" ? "Switch to light theme" : "Switch to dark theme");
                }
            }
        }
        setTheme(root.dataset.theme);
        for (const button of themeButtons) {
            button.addEventListener("click", () => {
                const theme = button.dataset.themeChoice ?? (root.dataset.theme === "dark" ? "light" : "dark");
                setTheme(theme);
                save(themeKey, theme);
            });
        }

        const sidebarToggle = document.querySelector("[data-sidebar-toggle]");
        const drawerToggle = document.querySelector("[data-drawer-open]");
        const drawer = document.querySelector("#navigation-drawer");
        const sidebar = document.querySelector("#sidebar-content");
        const sidebarSlot = document.querySelector("#sidebar-slot");
        const narrow = matchMedia("(max-width: 900px)");
        if (sidebarToggle && drawerToggle && drawer && sidebar && sidebarSlot && typeof drawer.showModal === "function") {
            document.body.classList.add("navigation-enhanced");
            function setCollapsed(collapsed) {
                document.body.classList.toggle("sidebar-collapsed", collapsed);
                sidebarToggle.setAttribute("aria-expanded", String(!collapsed));
                const label = collapsed ? "Expand sidebar" : "Collapse sidebar";
                sidebarToggle.setAttribute("aria-label", label);
                sidebarToggle.title = label;
            }
            setCollapsed(read(sidebarKey) === "collapsed");
            sidebarToggle.hidden = false;
            drawerToggle.hidden = false;
            sidebarToggle.addEventListener("click", () => {
                const collapsed = sidebarToggle.getAttribute("aria-expanded") === "true";
                setCollapsed(collapsed);
                save(sidebarKey, collapsed ? "collapsed" : "expanded");
            });
            drawerToggle.addEventListener("click", () => {
                drawer.querySelector("[data-drawer-content]").append(sidebar);
                drawer.showModal();
                drawerToggle.setAttribute("aria-expanded", "true");
            });
            drawer.addEventListener("close", () => {
                sidebarSlot.append(sidebar);
                drawerToggle.setAttribute("aria-expanded", "false");
                if (narrow.matches) drawerToggle.focus();
                else sidebar.querySelector("a").focus();
            });
            narrow.addEventListener("change", () => { if (!narrow.matches && drawer.open) drawer.close(); });
        }

        const passwordToggle = document.querySelector("[data-password-toggle]");
        const password = document.querySelector("#Password");
        if (passwordToggle && password) {
            passwordToggle.hidden = false;
            passwordToggle.addEventListener("click", () => {
                const visible = password.type === "password";
                password.type = visible ? "text" : "password";
                passwordToggle.setAttribute("aria-pressed", String(visible));
                passwordToggle.textContent = visible ? "Hide" : "Show";
            });
        }

        const account = document.querySelector(".account-menu");
        if (account) {
            account.addEventListener("keydown", event => {
                if (event.key === "Escape" && account.open) {
                    event.preventDefault();
                    account.open = false;
                    account.querySelector("summary").focus();
                }
            });
            document.addEventListener("click", event => {
                if (!account.contains(event.target)) account.open = false;
            });
        }

        const formDialog = document.querySelector("#form-dialog");
        let formTrigger = null;
        let formRequest = null;
        if (formDialog && typeof formDialog.showModal === "function") {
            const content = formDialog.querySelector("[data-dialog-content]");
            const title = formDialog.querySelector("[data-dialog-title]");
            formDialog.addEventListener("close", () => {
                if (formDialog.open) return;
                formRequest?.abort();
                formRequest = null;
                content.replaceChildren();
                formTrigger?.focus();
                formTrigger = null;
            });
            document.addEventListener("click", async event => {
                const link = event.target.closest("a[data-form-dialog]");
                if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey
                    || event.ctrlKey || event.shiftKey || event.altKey || link.origin !== location.origin) return;
                event.preventDefault();
                if (formDialog.open) return;
                formRequest?.abort();
                formTrigger = link;
                const request = new AbortController();
                formRequest = request;
                title.textContent = link.dataset.formDialog;
                const status = document.createElement("p");
                status.setAttribute("role", "status");
                status.textContent = "Loading form…";
                content.replaceChildren(status);
                formDialog.showModal();
                try {
                    const response = await fetch(link.href, { mode: "same-origin", cache: "no-store", signal: request.signal });
                    if (formRequest !== request || !formDialog.open) return;
                    if (!response.ok || response.redirected) {
                        location.assign(link.href);
                        return;
                    }
                    const page = new DOMParser().parseFromString(await response.text(), "text/html");
                    if (formRequest !== request || !formDialog.open) return;
                    const panel = page.querySelector("main [data-dialog-panel]");
                    if (!panel) { location.assign(link.href); return; }
                    panel.querySelector("h1")?.classList.add("visually-hidden");
                    content.replaceChildren(panel);
                    const active = panel.querySelector("[data-category-active]");
                    if (link.hasAttribute("data-dialog-status") && active?.form
                        && typeof active.form.requestSubmit === "function") {
                        active.checked = active.form.dataset.originalActive !== "true";
                        active.form.requestSubmit();
                    } else {
                        panel.querySelector("[data-dialog-focus]")?.focus();
                    }
                } catch {
                    if (formRequest === request && formDialog.open) location.assign(link.href);
                }
            });
        }

        document.addEventListener("click", event => {
            const dialog = event.target.closest("dialog");
            if (!dialog?.open) return;
            if (event.target === dialog || event.target.closest("[data-dialog-close]")) dialog.close();
            if (dialog === formDialog && event.target.closest("[data-form-dialog-cancel]")
                && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
                event.preventDefault();
                dialog.close();
            }
        });

        const busyForms = new Map();
        const confirmedForms = new WeakSet();
        const logoutDialog = document.querySelector("#logout-dialog");
        document.addEventListener("submit", event => {
            const form = event.target;
            if (form.method === "dialog") return;
            if (busyForms.has(form)) { event.preventDefault(); return; }

            if (form.hasAttribute("data-confirm-logout") && typeof logoutDialog?.showModal === "function"
                && !confirmedForms.delete(form)) {
                event.preventDefault();
                if (logoutDialog.open) return;
                logoutDialog.returnValue = "";
                logoutDialog.addEventListener("close", () => {
                    if (logoutDialog.returnValue === "confirm") {
                        confirmedForms.add(form);
                        form.requestSubmit(event.submitter ?? undefined);
                    }
                }, { once: true });
                logoutDialog.showModal();
                return;
            }

            const active = form.querySelector("[data-category-active]");
            const confirmation = active?.checked ? form.parentElement.querySelector("[data-confirm-activate]")
                : form.parentElement.querySelector("[data-confirm-deactivate]");
            if (active && String(active.checked) !== form.dataset.originalActive && confirmation
                && typeof confirmation.showModal === "function" && !confirmedForms.delete(form)) {
                event.preventDefault();
                confirmation.returnValue = "";
                confirmation.addEventListener("close", () => {
                    if (confirmation.returnValue !== "confirm") {
                        if (formTrigger?.hasAttribute("data-dialog-status")) formDialog.close();
                        else active.focus();
                        return;
                    }
                    if (!active.checked) form.querySelector("[name=ConfirmDeactivation][type=checkbox]").checked = true;
                    confirmedForms.add(form);
                    form.requestSubmit(event.submitter ?? undefined);
                }, { once: true });
                confirmation.showModal();
                return;
            }

            const message = form.dataset.busy;
            if (!message) return;
            const button = event.submitter;
            busyForms.set(form, { button, content: button ? [...button.childNodes] : [] });
            form.setAttribute("aria-busy", "true");
            if (button) {
                button.textContent = message;
                button.setAttribute("aria-disabled", "true");
            }
            const status = document.createElement("span");
            status.className = "visually-hidden";
            status.setAttribute("role", "status");
            status.textContent = message;
            status.dataset.submitStatus = "";
            form.after(status);
        });
        window.addEventListener("pageshow", () => {
            for (const [form, { button, content }] of busyForms) {
                form.removeAttribute("aria-busy");
                if (button) {
                    button.replaceChildren(...content);
                    button.removeAttribute("aria-disabled");
                }
                if (form.nextElementSibling?.hasAttribute("data-submit-status")) form.nextElementSibling.remove();
            }
            busyForms.clear();
        });
    });
})();

document.addEventListener("DOMContentLoaded", () => {
    const panel = document.querySelector("[data-admin-statistics]");
    if (!panel) return;

    const status = panel.querySelector("[data-stat-status]");
    const message = panel.querySelector("[data-stat-message]");
    const updated = panel.querySelector("[data-stat-updated]");
    const refreshButton = panel.querySelector("[data-stat-refresh]");
    const fields = [...panel.querySelectorAll("[data-stat]")];
    const formatTime = new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium", timeStyle: "medium"
    });
    const showTime = date => {
        updated.dateTime = date.toISOString();
        updated.textContent = formatTime.format(date);
        updated.title = date.toUTCString();
    };
    if (updated.dateTime) showTime(new Date(updated.dateTime));

    let pending = false;
    let accessRequired = false;
    const showStatus = (state, label, detail) => {
        status.dataset.state = state;
        if (status.textContent.trim() !== label) status.textContent = label;
        if (message.textContent.trim() !== detail) message.textContent = detail;
    };
    const refresh = async () => {
        if (pending || accessRequired) return;
        pending = true;
        refreshButton.setAttribute("aria-disabled", "true");
        try {
            const response = await fetch(panel.dataset.adminStatistics, {
                cache: "no-store", redirect: "manual", signal: AbortSignal.timeout(25000)
            });
            if (response.type === "opaqueredirect" || response.status === 401 || response.status === 403) {
                accessRequired = true;
                clearInterval(timer);
                showStatus("unavailable", "Sign-in required", "Refresh the page to restore Administrator access.");
                return;
            }
            if (response.status === 429) {
                showStatus("unavailable", "Temporarily limited", "Too many requests. Automatic refresh will retry.");
                return;
            }
            if (!response.ok) throw new Error("Statistics unavailable");
            const data = await response.json();
            const timestamp = new Date(data.updatedAtUtc);
            if (typeof data.updatedAtUtc !== "string" || !Number.isFinite(timestamp.getTime()) ||
                fields.some(field => !Number.isSafeInteger(data[field.dataset.stat]) || data[field.dataset.stat] < 0))
                throw new Error("Invalid statistics");

            for (const field of fields) field.textContent = data[field.dataset.stat].toLocaleString();
            showTime(timestamp);
            showStatus("connected", "Connected", "Refreshing every 10 seconds.");
        } catch {
            showStatus("unavailable", "Unavailable", updated.dateTime
                ? "Showing the last successful values. Retrying automatically."
                : "Live statistics could not be loaded. Retrying automatically.");
        } finally {
            pending = false;
            refreshButton.removeAttribute("aria-disabled");
        }
    };

    const timer = setInterval(refresh, 10000);
    refreshButton.addEventListener("click", event => {
        if (accessRequired || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        refresh();
    });
});
