"use strict";
(() => {
    const key = "owli-personal-theme";
    const system = matchMedia("(prefers-color-scheme: dark)");
    let choice = "system";
    try {
        const stored = localStorage.getItem(key);
        if (stored === "light" || stored === "dark") choice = stored;
    } catch { /* Appearance still follows the device. */ }

    function apply() {
        document.documentElement.dataset.theme = choice === "system" ? (system.matches ? "dark" : "light") : choice;
    }
    apply();
    system.addEventListener("change", apply);
    document.addEventListener("DOMContentLoaded", () => {
        const picker = document.querySelector("[data-personal-theme-choice]");
        const toggle = document.querySelector("[data-personal-theme-toggle]");
        function setChoice(value) {
            choice = value;
            try { localStorage.setItem(key, value); } catch { /* Preference is optional. */ }
            if (picker) picker.value = choice;
            apply();
        }
        if (picker) {
            picker.closest("[data-personal-theme-settings]").hidden = false;
            picker.value = choice;
            picker.addEventListener("change", () => setChoice(picker.value));
        }
        if (toggle) {
            toggle.hidden = false;
            toggle.addEventListener("click", () => setChoice(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));
        }

        const swap = document.querySelector("[data-currency-swap]");
        if (swap) {
            const source = swap.form.elements.namedItem("Source");
            const target = swap.form.elements.namedItem("Target");
            swap.hidden = false;
            swap.addEventListener("click", () => {
                [source.value, target.value] = [target.value, source.value];
                swap.form.closest("section").querySelector(".android-converted")?.remove();
            });
        }

        for (const ring of document.querySelectorAll("[data-flow-ring]")) {
            const context = ring.getContext("2d");
            if (!context) continue;
            const start = -Math.PI / 2;
            context.lineWidth = 5;
            context.strokeStyle = "#ffffff38";
            context.beginPath();
            context.arc(32, 32, 27, 0, 2 * Math.PI);
            context.stroke();
            context.strokeStyle = getComputedStyle(ring).color;
            context.lineCap = "round";
            context.beginPath();
            context.arc(32, 32, 27, start, start + Number(ring.dataset.flowRing) / 100 * 2 * Math.PI);
            context.stroke();
        }

        function revealActivityFilter() {
            const hash = location.hash;
            if (!["#activity-filters", "#activity-search", "#activity-month"].includes(hash)) return;
            const filters = document.getElementById("activity-filters");
            if (!filters) return;
            filters.open = true;
            if (hash !== "#activity-filters") document.querySelector(hash)?.focus();
        }
        revealActivityFilter();
        window.addEventListener("hashchange", revealActivityFilter);
        document.querySelectorAll('.android-heading a[href="#activity-filters"], .android-heading a[href="#activity-search"], .android-month-strip a[href="#activity-month"]').forEach(link => {
            link.addEventListener("click", () => {
                if (location.hash === link.hash) revealActivityFilter();
            });
        });

        for (const chart of document.querySelectorAll("[data-spending-chart]")) {
            const canvas = chart.querySelector("canvas");
            const context = canvas?.getContext("2d");
            if (!context) continue;
            const buttons = [...chart.querySelectorAll("[data-spending-slice]")];
            const amounts = buttons.map(button => Number(button.dataset.spendingSlice));
            const total = amounts.reduce((sum, amount) => sum + amount, 0);
            if (!(total > 0)) continue;
            const colors = ["#6747c7", "#9f71e3", "#4165a1", "#54786d", "#b35785", "#9c632e"];
            const summary = chart.querySelector("[data-spending-selection]");
            const android = chart.classList.contains("android-donut");
            let active = -1;
            function render() {
                let offset = -Math.PI / 2;
                context.clearRect(0, 0, 240, 240);
                amounts.forEach((amount, index) => {
                    const end = offset + 2 * Math.PI * amount / total;
                    context.beginPath();
                    context.lineWidth = active === index ? 45 : 36;
                    context.globalAlpha = active < 0 || active === index ? 1 : 0.38;
                    context.strokeStyle = colors[index % colors.length];
                    context.arc(120, 120, 91, offset, end);
                    context.stroke();
                    offset = end;
                });
            }
            canvas.hidden = false;
            buttons.forEach((button, index) => {
                button.hidden = false;
                if (android) button.style.setProperty("--slice-color", colors[index % colors.length]);
                button.addEventListener("click", () => {
                    active = active === index ? -1 : index;
                    buttons.forEach((item, position) => item.setAttribute("aria-pressed", String(position === active)));
                    summary.textContent = active < 0 ? "" : buttons[active].parentElement.querySelector("[data-spending-label]").textContent;
                    render();
                });
            });
            if (android) {
                canvas.addEventListener("click", event => {
                    const bounds = canvas.getBoundingClientRect();
                    const x = 240 * (event.clientX - bounds.left) / bounds.width - 120;
                    const y = 240 * (event.clientY - bounds.top) / bounds.height - 120;
                    const radius = Math.hypot(x, y);
                    if (radius < 69 || radius > 114) return;
                    const turn = 2 * Math.PI;
                    const amountAtPoint = ((Math.atan2(y, x) + 2.5 * Math.PI) % turn) / turn * total;
                    let end = 0;
                    const index = amounts.findIndex(amount => {
                        end += amount;
                        return amountAtPoint < end;
                    });
                    buttons[index]?.click();
                });
            }
            render();
        }
    });
})();
