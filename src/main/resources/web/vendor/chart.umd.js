/*! Minimal Chart-like UMD (supports doughnut, line, bar) */
(function (global, factory) {
    if (typeof module === "object" && typeof module.exports === "object") {
        module.exports = factory();
    } else {
        global.Chart = factory();
    }
})(typeof window !== "undefined" ? window : this, function () {
    "use strict";

    function merge(a, b) {
        for (const k in b) {
            if (b.hasOwnProperty(k)) {
                a[k] = b[k];
            }
        }
        return a;
    }

    class ChartCore {
        constructor(ctx, cfg) {
            this.ctx = ctx.canvas ? ctx : ctx.getContext("2d");
            this.cfg = cfg || {};
            this._destroyed = false;
            this.draw();
        }

        destroy() {
            this._destroyed = true;
            const c = this.ctx.canvas;
            const w = c.width;
            const h = c.height;
            this.ctx.clearRect(0, 0, w, h);
        }

        update() {
            this.draw();
        }

        draw() {
            if (this._destroyed) return;
            const type = this.cfg.type || "bar";
            const data = this.cfg.data || {};
            const opts = this.cfg.options || {};
            const ctx = this.ctx;
            const c = ctx.canvas;
            const W = c.width || c.clientWidth || 400;
            const H = c.height || c.clientHeight || 200;
            ctx.clearRect(0, 0, W, H);
            if (type === "doughnut" || type === "pie") {
                const values = (data.datasets && data.datasets[0] && data.datasets[0].data) || [];
                const total = values.reduce((a, b) => a + (+b || 0), 0) || 1;
                const cx = W / 2, cy = H / 2, r = Math.min(W, H) / 2 * 0.8, r2 = r * 0.5;
                let start = -Math.PI / 2;
                values.forEach((v, i) => {
                    const ang = (v / total) * Math.PI * 2;
                    ctx.beginPath();
                    ctx.moveTo(cx, cy);
                    ctx.arc(cx, cy, r, start, start + ang);
                    ctx.closePath();
                    ctx.fillStyle = autoColor(i);
                    ctx.fill();
                    start += ang;
                });
                // cut hole
                ctx.globalCompositeOperation = "destination-out";
                ctx.beginPath();
                ctx.arc(cx, cy, r2, 0, Math.PI * 2);
                ctx.fill();
                ctx.globalCompositeOperation = "source-over";
            } else if (type === "line") {
                const values = (data.datasets && data.datasets[0] && data.datasets[0].data) || [];
                const labels = data.labels || values.map((_, i) => "" + i);
                const pad = 30;
                const w = W - pad * 2, h = H - pad * 2;
                const max = Math.max(1, ...values.map(v => +v || 0));
                ctx.strokeStyle = "#9aa5b1";
                ctx.lineWidth = 1; // axes
                ctx.beginPath();
                ctx.moveTo(pad, H - pad);
                ctx.lineTo(W - pad, H - pad);
                ctx.stroke();
                ctx.beginPath();
                ctx.moveTo(pad, pad);
                ctx.lineTo(pad, H - pad);
                ctx.stroke();
                ctx.strokeStyle = "#1c7ed6";
                ctx.lineWidth = 2;
                ctx.beginPath();
                values.forEach((v, i) => {
                    const x = pad + (i / (Math.max(1, labels.length - 1))) * w;
                    const y = H - pad - (Math.min(v, max) / max) * h;
                    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
                });
                ctx.stroke();
            } else { // bar
                const datasets = data.datasets || [];
                const labels = data.labels || [];
                const n = labels.length, m = datasets.length;
                const pad = 30;
                const w = W - pad * 2, h = H - pad * 2;
                const values = [];
                datasets.forEach(ds => values.push(...(ds.data || [])));
                const max = Math.max(1, ...values.map(v => +v || 0));
                const groupW = w / Math.max(1, n);
                const barW = Math.max(4, (groupW * 0.8) / Math.max(1, m));
                // axes
                const ctx = this.ctx;
                ctx.strokeStyle = "#9aa5b1";
                ctx.lineWidth = 1;
                ctx.beginPath();
                ctx.moveTo(pad, H - pad);
                ctx.lineTo(W - pad, H - pad);
                ctx.stroke();
                ctx.beginPath();
                ctx.moveTo(pad, pad);
                ctx.lineTo(pad, H - pad);
                ctx.stroke();
                // bars
                datasets.forEach((ds, di) => {
                    ctx.fillStyle = autoColor(di);
                    (ds.data || []).forEach((v, i) => {
                        const x0 = pad + i * groupW + di * barW + (groupW - barW * m) / 2;
                        const y0 = H - pad;
                        const bh = (Math.min(+v || 0, max) / max) * h;
                        ctx.fillRect(x0, y0 - bh, barW, bh);
                    });
                });
            }
        }
    }

    function autoColor(i) {
        const pal = ["#1c7ed6", "#12b886", "#fab005", "#e64980", "#845ef7", "#fd7e14", "#2f9e44", "#0ca678"];
        return pal[i % pal.length];
    }

    function Chart(ctx, cfg) {
        return new ChartCore(ctx, cfg);
    }

    Chart.version = "min-1.0.0";
    return Chart;
});