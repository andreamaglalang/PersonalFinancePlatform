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
    root.dataset.theme = read(themeKey) === "dark" ? "dark" : "light";

    document.addEventListener("DOMContentLoaded", () => {
        const themeButtons = document.querySelectorAll("[data-theme-toggle], [data-theme-choice]");
        function setTheme(theme) {
            root.dataset.theme = theme;
            for (const button of themeButtons) {
                button.hidden = false;
                const choice = button.dataset.themeChoice ?? "dark";
                button.setAttribute("aria-pressed", String(theme === choice));
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
                    panel.querySelector(link.hasAttribute("data-dialog-status") ? "[data-category-active]" : "[data-dialog-focus]")?.focus();
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
        document.addEventListener("submit", event => {
            const form = event.target;
            if (form.method === "dialog") return;
            if (busyForms.has(form)) { event.preventDefault(); return; }

            const active = form.querySelector("[data-category-active]");
            const confirmation = active?.checked ? form.parentElement.querySelector("[data-confirm-activate]")
                : form.parentElement.querySelector("[data-confirm-deactivate]");
            if (active && String(active.checked) !== form.dataset.originalActive && confirmation
                && typeof confirmation.showModal === "function" && !confirmedForms.delete(form)) {
                event.preventDefault();
                confirmation.returnValue = "";
                confirmation.addEventListener("close", () => {
                    if (confirmation.returnValue !== "confirm") {
                        active.focus();
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
            busyForms.set(form, { button, text: button?.textContent });
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
            for (const [form, { button, text }] of busyForms) {
                form.removeAttribute("aria-busy");
                if (button) {
                    button.textContent = text;
                    button.removeAttribute("aria-disabled");
                }
                if (form.nextElementSibling?.hasAttribute("data-submit-status")) form.nextElementSibling.remove();
            }
            busyForms.clear();
        });
    });
})();
