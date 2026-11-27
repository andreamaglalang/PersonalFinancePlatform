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
    const colorScheme = matchMedia("(prefers-color-scheme: dark)");
    let themeMode = read(themeKey);
    if (!["dark", "light", "system"].includes(themeMode)) themeMode = "system";
    const effectiveTheme = mode => mode === "system" ? (colorScheme.matches ? "dark" : "light") : mode;
    root.dataset.theme = effectiveTheme(themeMode);

    document.addEventListener("DOMContentLoaded", () => {
        document.addEventListener("change", event => {
            const choice = event.target;
            if (!choice.matches('[data-category-palette] input[type="radio"]:checked')) return;
            choice.form.querySelector("[data-category-color-value]").value = choice.value;
        });

        const themeButtons = document.querySelectorAll("[data-theme-toggle], [data-theme-choice]");
        function setTheme(mode) {
            themeMode = mode;
            document.querySelectorAll("[data-theme-preference]").forEach(label => { label.textContent = mode[0].toUpperCase() + mode.slice(1); });
            root.dataset.theme = effectiveTheme(mode);
            for (const button of themeButtons) {
                button.hidden = false;
                const choice = button.dataset.themeChoice ?? "dark";
                button.setAttribute("aria-pressed", String(mode === choice));
                if (button.hasAttribute("data-theme-toggle")) {
                    button.setAttribute("aria-label", root.dataset.theme === "dark" ? "Switch to light theme" : "Switch to dark theme");
                }
            }
        }
        setTheme(themeMode);
        for (const button of themeButtons) {
            button.addEventListener("click", () => {
                const mode = button.dataset.themeChoice ?? (root.dataset.theme === "dark" ? "light" : "dark");
                setTheme(mode);
                save(themeKey, mode);
            });
        }
        colorScheme.addEventListener?.("change", () => {
            if (themeMode === "system") setTheme("system");
        });

        document.querySelectorAll("[data-currency-swap]").forEach(button => {
            button.addEventListener("click", () => {
                const from = document.querySelector("#From");
                const to = document.querySelector("#To");
                if (!from || !to) return;
                const previous = from.value;
                from.value = to.value;
                to.value = previous;
                from.dispatchEvent(new Event("change", { bubbles: true }));
                to.dispatchEvent(new Event("change", { bubbles: true }));
            });
        });

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

        // Every Show/Hide control reveals the password field in its own box (sign-in, password and confirmation).
        document.querySelectorAll("[data-password-toggle]").forEach(passwordToggle => {
            const password = passwordToggle.closest(".box")?.querySelector("input");
            if (!password) return;
            passwordToggle.hidden = false;
            passwordToggle.addEventListener("click", () => {
                const visible = password.type === "password";
                password.type = visible ? "text" : "password";
                passwordToggle.setAttribute("aria-pressed", String(visible));
                passwordToggle.textContent = visible ? "Hide" : "Show";
            });
        });

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

        // Highlight the current Member destination (Tier 1).
        document.querySelectorAll("[data-nav]").forEach(item => {
            if (item.dataset.nav === "home" && location.pathname.replace(/\/$/, "").endsWith("/Member/Home"))
                item.classList.add("active");
        });
    });
})();

// Reports stay server-rendered: these handlers supply loading feedback and accessible exact chart values.
document.addEventListener("DOMContentLoaded", () => {
    const page = document.querySelector("[data-reports-page]");
    if (!page) return;
    const loading = page.querySelector(".insights-loading");
    const busy = () => { page.setAttribute("aria-busy", "true"); if (loading) loading.hidden = false; };
    page.querySelectorAll("[data-report-navigation], [data-report-refresh]").forEach(link => link.addEventListener("click", event => { if (!event.ctrlKey && !event.metaKey && !event.shiftKey) busy(); }));
    const form = page.querySelector("[data-report-month-form]");
    if (form) {
        form.addEventListener("submit", busy);
        const month = form.querySelector("input[type=month]");
        month.addEventListener("change", () => { if (month.checkValidity()) form.requestSubmit(); });
    }
    const value = page.querySelector(".insights-chart-value");
    page.querySelectorAll(".insights-chart-month").forEach(group => {
        const show = () => { if (value) value.textContent = group.getAttribute("aria-label"); };
        group.addEventListener("mouseenter", show); group.addEventListener("focus", show); group.addEventListener("click", show);
    });
});

// Activity controls keep the authoritative server query and provide progressive enhancement.
document.addEventListener("DOMContentLoaded", () => {
    const page = document.querySelector("[data-activity-page]");
    if (page) {
        const loading = page.querySelector(".activity-loading");
        const busy = () => { page.setAttribute("aria-busy", "true"); if (loading) loading.hidden = false; };
        page.querySelectorAll("a[href]").forEach(link => link.addEventListener("click", event => { if (!event.ctrlKey && !event.metaKey && !event.shiftKey && link.getAttribute("href")?.includes("Activity")) busy(); }));
        page.querySelectorAll("form[method=get]").forEach(form => form.addEventListener("submit", busy));
        const month = page.querySelector("[data-activity-month-form] input[type=month]");
        month?.addEventListener("change", () => { if (month.checkValidity()) month.form.requestSubmit(); });
        const segments = Array.from(page.querySelectorAll(".activity-type-segment"));
        segments.forEach((segment, index) => segment.addEventListener("keydown", event => {
            if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
            event.preventDefault(); segments[(index + (event.key === "ArrowRight" ? 1 : -1) + segments.length) % segments.length].focus();
        }));
    }
    const entry = document.querySelector("[data-transaction-entry]");
    if (entry) {
        const category = entry.querySelector("[name=CategoryId]");
        const update = () => {
            const type = entry.querySelector("[name=Type]:checked")?.value;
            Array.from(category.options).forEach(option => { const excluded = !!option.value && option.dataset.type !== type; option.hidden = excluded; option.disabled = excluded; });
            if (category.selectedOptions[0]?.disabled) category.value = "";
            const none = entry.querySelector("[data-no-categories]");
            if (none) none.hidden = Array.from(category.options).some(option => option.value && !option.disabled);
            const icon = entry.querySelector(`[data-category-icon="${category.value}"]`);
            const preview = entry.querySelector("[data-entry-category-icon]");
            if (preview && icon) preview.replaceChildren(icon.content.cloneNode(true));
            else if (preview) preview.replaceChildren();
        };
        entry.querySelectorAll("[name=Type]").forEach(input => input.addEventListener("change", update));
        category.addEventListener("change", update); update();
    }
});

document.addEventListener("DOMContentLoaded", () => {
 document.querySelectorAll("[data-account-password-toggle]").forEach(button => {
  const input = document.getElementById(button.dataset.accountPasswordToggle); if (!input) return;
  button.hidden=false; button.addEventListener("click", () => { const visible=input.type==="password"; input.type=visible?"text":"password"; button.textContent=visible?"Hide":"Show"; button.setAttribute("aria-pressed",String(visible)); button.setAttribute("aria-label",(visible?"Hide ":"Show ")+document.querySelector('label[for="'+input.id+'"]').textContent.toLowerCase()); });
 });
});

document.addEventListener("DOMContentLoaded", () => {
 const catalog=document.querySelector("[data-category-catalog]"), toggle=document.querySelector("[data-category-reorder-toggle]"); if(!catalog||!toggle) return;
 const status=document.querySelector("[data-category-order-status]"); let reordering=false;
 const lists=[...catalog.querySelectorAll("[data-category-order-list]")];
 const key=list=>"owli-category-order:"+catalog.dataset.categoryScope+":"+list.dataset.categoryOrderList;
 function update(){lists.forEach(list=>{const cards=[...list.children];cards.forEach((card,index)=>{card.querySelector(".category-reorder-actions").hidden=!reordering;card.querySelector('[data-category-move="-1"]').disabled=index===0;card.querySelector('[data-category-move="1"]').disabled=index===cards.length-1;});});}
 lists.forEach(list=>{try{const order=JSON.parse(localStorage.getItem(key(list))||"[]");if(!Array.isArray(order))return;const rank=new Map(order.map((id,i)=>[String(id),i]));[...list.children].sort((a,b)=>(rank.get(a.dataset.categoryId)??Number.MAX_SAFE_INTEGER)-(rank.get(b.dataset.categoryId)??Number.MAX_SAFE_INTEGER)).forEach(card=>list.append(card));}catch{ /* Optional browser preference. */ }});
 toggle.hidden=false;toggle.addEventListener("click",()=>{reordering=!reordering;toggle.textContent=reordering?"Done reordering":"Reorder";toggle.setAttribute("aria-pressed",String(reordering));update();});
 catalog.addEventListener("click",event=>{const button=event.target.closest("[data-category-move]");if(!button||!reordering)return;const card=button.closest("[data-category-id]"),list=card.parentElement,delta=Number(button.dataset.categoryMove),other=delta<0?card.previousElementSibling:card.nextElementSibling;if(!other)return;if(delta<0)list.insertBefore(card,other);else list.insertBefore(other,card);update();try{localStorage.setItem(key(list),JSON.stringify([...list.children].map(c=>c.dataset.categoryId)));status.textContent="Category order saved in this browser.";}catch{status.textContent="Order changed for this page. Browser storage is unavailable.";}const available=[...card.querySelectorAll("[data-category-move]")].find(b=>!b.disabled);(available||toggle).focus();});update();
});

// Password requirement checklists: mirror PasswordPolicy (ASCII classes, as the server counts them).
document.addEventListener("DOMContentLoaded", () => {
 const rules = {
  length: p => p.length >= 12 && p.length <= 128,
  upper: p => /[A-Z]/.test(p),
  lower: p => /[a-z]/.test(p),
  digit: p => /[0-9]/.test(p),
  symbol: p => /[^A-Za-z0-9]/.test(p),
  distinct: p => new Set(p).size >= 4
 };
 document.querySelectorAll("[data-password-rules]").forEach(input => {
  const list = document.getElementById(input.dataset.passwordRules); if (!list) return;
  const update = () => list.querySelectorAll("[data-rule]").forEach(item => {
   const met = input.value.length > 0 && rules[item.dataset.rule](input.value);
   item.classList.toggle("met", met);
   item.querySelector("[data-rule-state]").textContent = met ? " (done)" : " (still needed)";
  });
  input.addEventListener("input", update); update();
  // Collapsible lists start as a one-line hint and open on focus or typing; they open at once after a failed
  // submission so every rule is visible next to the error, and stay open once shown.
  if (list.classList.contains("is-collapsible")) {
   const message = input.form?.querySelector('[data-valmsg-for="' + input.name + '"]');
   const expand = () => list.classList.remove("is-collapsed");
   if (!input.value && input.getAttribute("aria-invalid") !== "true" && !message?.textContent.trim()) list.classList.add("is-collapsed");
   input.addEventListener("focus", expand); input.addEventListener("input", expand);
  }
 });
 // Clear a field's server message once the user changes that field, so outdated errors don't linger.
 document.querySelectorAll("form input").forEach(input => input.addEventListener("input", () => {
  const message = input.form.querySelector('[data-valmsg-for="' + input.name + '"]');
  if (message && message.textContent) { message.textContent = ""; input.removeAttribute("aria-invalid"); }
 }));
});
