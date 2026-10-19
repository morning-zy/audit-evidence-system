/* ==========================================================================
   通用 UI 工具：图标、格式化、Toast、模态、抽屉、表单构建
   全部手写，不依赖任何第三方库 —— 保证双击就能跑
   ========================================================================== */

/* ---------- 图标（内联 SVG，currentColor 跟随文字色） ---------- */
const Icon = {
    dash: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M2 2h5v5H2zM9 2h5v3H9zM9 7h5v7H9zM2 9h5v5H2z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>',
    project: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M2 5.5A1.5 1.5 0 013.5 4h2.7l1.2 1.5h3.1A1.5 1.5 0 0112 7v5a1.5 1.5 0 01-1.5 1.5h-7A1.5 1.5 0 012 12V5.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>',
    issue: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 2.5h7l3 3v8H3z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M10 2.5v3h3M5.5 8.5h5M5.5 11h3.5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>',
    evidence: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><circle cx="7" cy="7" r="4.2" stroke="currentColor" stroke-width="1.2"/><path d="M10.2 10.2L13.5 13.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>',
    review: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M5.5 6.2h5M5.5 8.4h3" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>',
    log: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M4 2.5h8v11H4z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M6 5.5h4M6 8h4M6 10.5h2.5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>',
    search: '<svg width="13" height="13" viewBox="0 0 16 16" fill="none"><circle cx="7" cy="7" r="4.2" stroke="currentColor" stroke-width="1.3"/><path d="M10.2 10.2L13.5 13.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
    plus: '<svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
    close: '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
    refresh: '<svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M13 8a5 5 0 11-1.6-3.7M13 2.5V5.5H10" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    download: '<svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M8 3v7M4.8 7.2L8 10.5l3.2-3.3M3 13h10" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    back: '<svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M9.5 3.5L5 8l4.5 4.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    sampling: '<svg width="15" height="15" viewBox="0 0 16 16" fill="none"><rect x="2.2" y="2.2" width="5" height="5" rx="1" stroke="currentColor" stroke-width="1.2"/><rect x="8.8" y="2.2" width="5" height="5" rx="1" stroke="currentColor" stroke-width="1.2"/><rect x="2.2" y="8.8" width="5" height="5" rx="1" stroke="currentColor" stroke-width="1.2"/><path d="M9.4 11.4l1.3 1.3 2.4-2.6" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>'
};

/* ---------- 枚举字典（与后端 Enums.java 一一对应，前端只做展示映射） ---------- */
const DICT = {
    projectStatus: {
        0: {text: '草稿', color: 'muted'},
        1: {text: '进行中', color: 'info'},
        2: {text: '待复核', color: 'warn'},
        3: {text: '已归档', color: 'green'}
    },
    issueStatus: {
        0: {text: '待执行', color: 'muted'},
        1: {text: '证据收集中', color: 'info'},
        2: {text: '已完成', color: 'green'}
    },
    evidenceStatus: {
        0: {text: '草稿', color: 'muted'},
        1: {text: '待详细复核', color: 'warn'},
        2: {text: '已确认', color: 'green'},
        3: {text: '已驳回', color: 'danger'},
        4: {text: '待一般复核', color: 'warn'},
        5: {text: '待重点复核', color: 'warn'}
    },
    riskLevel: {
        1: {text: '低风险', color: 'green'},
        2: {text: '中风险', color: 'warn'},
        3: {text: '高风险', color: 'danger'}
    },
    auditType: {
        FINANCIAL: '财务审计',
        IT_SYSTEM: '信息系统审计',
        COMPLIANCE: '合规审计',
        PERFORMANCE: '绩效审计'
    },
    evidenceType: {
        DOCUMENT: '书证',
        ELECTRONIC: '电子数据',
        INTERVIEW: '访谈记录',
        OBSERVATION: '现场观察',
        ANALYTICS: '分析性复核'
    },
    reliability: {
        1: {text: '低', color: 'danger'},
        2: {text: '中', color: 'warn'},
        3: {text: '高', color: 'green'}
    },
    role: {ADMIN: '审计主管', AUDITOR: '审计员', VIEWER: '查阅人'},

    /* 抽样结论：只有"能信 / 不能信"两种终态，不设"部分有效"的中间态 */
    samplingConclusion: {
        0: {text: '未执行', color: 'muted'},
        1: {text: '控制可信赖', color: 'green'},
        2: {text: '控制不可信赖', color: 'danger'}
    },
    samplingMethod: {
        STATISTICAL: {text: '统计抽样', color: 'info'},
        JUDGMENTAL: {text: '判断抽样', color: 'warn'}
    },
    confidenceLevel: {
        NINETY_FIVE: '95% 置信',
        NINETY: '90% 置信'
    },

    /* 变量抽样：结论是"金额能不能接受"，不是"控制能不能信" */
    vsConclusion: {
        0: {text: '未评价', color: 'muted'},
        1: {text: '可接受', color: 'green'},
        2: {text: '不可接受', color: 'danger'}
    },
    vsMethod: {
        MEAN_PER_UNIT: {text: '均值估计', color: 'info'},
        DIFFERENCE: {text: '差额估计', color: 'info'},
        RATIO: {text: '比率估计', color: 'info'}
    }
};

/* ---------- 格式化 ---------- */
const Fmt = {
    /* 数字千分位 */
    num(v) {
        if (v === null || v === undefined || v === '') return '-';
        return Number(v).toLocaleString('zh-CN');
    },
    /* 金额：固定两位小数。
       不能直接用 num()，否则 12000.5 会显示成 12,000.5，和底稿上的 12,000.50 对不上 */
    money(v) {
        if (v === null || v === undefined || v === '') return '-';
        return '¥' + Number(v).toLocaleString('zh-CN',
            {minimumFractionDigits: 2, maximumFractionDigits: 2});
    },
    /* 日期时间：只留到分钟，秒对审计场景没意义且占地方 */
    dt(v) {
        if (!v) return '-';
        const s = String(v).replace('T', ' ');
        return s.length >= 16 ? s.slice(0, 16) : s;
    },
    d(v) {
        if (!v) return '-';
        return String(v).slice(0, 10);
    },
    /* 文件大小 */
    size(bytes) {
        if (!bytes) return '-';
        const n = Number(bytes);
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / 1024 / 1024).toFixed(1) + ' MB';
    },
    /* 转义，防 XSS。所有用户输入渲染前必须过这一层 */
    esc(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
};

/* ---------- 标签渲染 ---------- */
function tag(dict, key, fallback) {
    const item = DICT[dict] && DICT[dict][key];
    if (!item) {
        const t = fallback || (key === null || key === undefined ? '-' : key);
        return `<span class="tag tag-muted">${Fmt.esc(t)}</span>`;
    }
    const text = typeof item === 'string' ? item : item.text;
    const color = typeof item === 'string' ? 'ghost' : item.color;
    return `<span class="tag tag-${color}">${Fmt.esc(text)}</span>`;
}

/**
 * 可靠性等级标记
 *
 * 不复用通用 tag()，因为可靠性是「系统按审计准则算出的权威判定」，
 * 需要和普通状态标签在视觉上区分开 —— 它带字母等级，
 * 这是审计底稿的行业习惯，专业人士一眼能认。
 */
function reliabilityBadge(grade, name) {
    if (!grade) {
        return '<span class="rel-badge" style="background:var(--bg-input);color:var(--tx-3)">未评定</span>';
    }
    const cls = {A: 'ga', B: 'gb', C: 'gc', D: 'gd'}[grade] || 'gb';
    return `<span class="rel-badge rel-grade ${cls}">${Fmt.esc(grade)} · ${Fmt.esc(name || '')}</span>`;
}

/* ---------- Toast ---------- */const Toast = {
    show(msg, type = 'info', ms = 2600) {
        const host = document.getElementById('toast-host');
        const el = document.createElement('div');
        el.className = 'toast ' + type;
        el.textContent = msg;
        host.appendChild(el);
        setTimeout(() => {
            el.style.transition = 'opacity .2s';
            el.style.opacity = '0';
            setTimeout(() => el.remove(), 200);
        }, ms);
    },
    ok(msg) {
        this.show(msg, 'ok');
    },
    error(msg) {
        this.show(msg, 'err', 3600);
    },
    info(msg) {
        this.show(msg, 'info');
    }
};

/* ---------- 模态框 ---------- */
const Modal = {
    /**
     * @param {Object} cfg
     *   title    标题
     *   body     HTML 字符串
     *   footer   HTML 字符串（按钮区）
     *   wide     是否加宽
     *   onMount  插入 DOM 后的回调，用于绑事件
     *   onClose  关闭回调
     */
    open(cfg) {
        const host = document.getElementById('modal-host');
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `
            <div class="modal ${cfg.xwide ? 'xwide' : (cfg.wide ? 'wide' : '')}">
                <div class="modal-head">
                    <h3>${Fmt.esc(cfg.title || '')}</h3>
                    <button class="btn btn-sm btn-ghost" data-close>${Icon.close}</button>
                </div>
                <div class="modal-body">${cfg.body || ''}</div>
                ${cfg.footer ? `<div class="modal-foot">${cfg.footer}</div>` : ''}
            </div>`;
        host.appendChild(mask);

        // 强制重排后再加 show，保证过渡动画生效
        void mask.offsetWidth;
        mask.classList.add('show');

        const close = () => {
            mask.classList.remove('show');
            setTimeout(() => {
                mask.remove();
                cfg.onClose && cfg.onClose();
            }, 170);
        };

        mask.querySelector('[data-close]').onclick = close;
        // 点遮罩关闭（点内容区不关）
        mask.onclick = (e) => {
            if (e.target === mask) close();
        };
        mask.querySelectorAll('[data-dismiss]').forEach(b => (b.onclick = close));

        cfg.onMount && cfg.onMount(mask, close);
        return {el: mask, close};
    },

    /* 确认框 */
    confirm(title, message, onOk, danger = true) {
        this.open({
            title,
            body: `<p style="margin:0;color:var(--tx-2);line-height:1.75;font-size:13px">${Fmt.esc(message)}</p>`,
            footer: `<button class="btn" data-dismiss>取消</button>
                     <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" id="mc-ok">确定</button>`,
            onMount(el, close) {
                el.querySelector('#mc-ok').onclick = async () => {
                    const btn = el.querySelector('#mc-ok');
                    btn.disabled = true;
                    try {
                        await onOk();
                        close();
                    } catch (e) {
                        Toast.error(e.message);
                        btn.disabled = false;
                    }
                };
            }
        });
    }
};

/* ---------- 详情抽屉 ---------- */
const Drawer = {
    _cur: null,

    open(cfg) {
        this.close();
        const host = document.getElementById('drawer-host');
        const wrap = document.createElement('div');
        wrap.innerHTML = `
            <div class="drawer-mask"></div>
            <aside class="drawer">
                <div class="drawer-head">
                    <div style="flex:1;min-width:0">
                        <div class="t">${cfg.title || ''}</div>
                        <div class="s">${cfg.sub || ''}</div>
                    </div>
                    <button class="btn btn-sm btn-ghost" data-d-close>${Icon.close}</button>
                </div>
                <div class="drawer-body">${cfg.body || ''}</div>
                ${cfg.footer ? `<div class="drawer-foot">${cfg.footer}</div>` : ''}
            </aside>`;
        host.appendChild(wrap);
        this._cur = wrap;

        const mask = wrap.querySelector('.drawer-mask');
        const panel = wrap.querySelector('.drawer');
        void panel.offsetWidth;
        mask.classList.add('show');
        panel.classList.add('show');

        const close = () => this.close();
        mask.onclick = close;
        wrap.querySelector('[data-d-close]').onclick = close;

        cfg.onMount && cfg.onMount(panel, close);
        return {el: panel, close};
    },

    close() {
        if (!this._cur) return;
        const wrap = this._cur;
        this._cur = null;
        wrap.querySelector('.drawer-mask').classList.remove('show');
        wrap.querySelector('.drawer').classList.remove('show');
        setTimeout(() => wrap.remove(), 240);
    }
};

/* ---------- 表单工具：收集值 ---------- */
function collectForm(scope) {
    const out = {};
    scope.querySelectorAll('[data-field]').forEach(el => {
        const key = el.dataset.field;
        let v = el.value;
        if (v === '') v = null;
        // number 字段转回数字，否则后端 Integer 接收字符串会报错
        if (v !== null && el.dataset.type === 'number') v = Number(v);
        // boolean 字段：<select> 的 value 永远是字符串 "true"/"false"，
        // 直接发过去 Jackson 会把它当 Boolean 解析失败，必须显式转换
        if (v !== null && el.dataset.type === 'boolean') v = v === 'true';
        out[key] = v;
    });
    return out;
}

/* ---------- 选项列表生成 ---------- */
function optionsFrom(dict, selected, placeholder) {
    let html = placeholder ? `<option value="">${Fmt.esc(placeholder)}</option>` : '';
    Object.keys(dict).forEach(k => {
        const item = dict[k];
        const text = typeof item === 'string' ? item : item.text;
        const sel = String(selected) === String(k) ? 'selected' : '';
        html += `<option value="${Fmt.esc(k)}" ${sel}>${Fmt.esc(text)}</option>`;
    });
    return html;
}

/* ---------- 进度条 ---------- */
function bar(pct, cls = '') {
    const p = Math.max(0, Math.min(100, Math.round(pct || 0)));
    // 0% 时加 zero 类，用一个极淡的凹槽代替实心灰条
    const zero = p === 0 ? ' zero' : '';
    return `<div class="bar-row"><div class="bar ${cls}${zero}" style="flex:1"><i style="width:${p}%"></i></div>
            <span class="pct">${p}%</span></div>`;
}

/* ---------- 环形图（纯 SVG） ---------- */
function donut(data, options = {}) {
    const size = options.size || 116;
    const stroke = options.stroke || 13;
    const r = (size - stroke) / 2;
    const c = 2 * Math.PI * r;
    const cx = size / 2;
    const total = data.reduce((s, d) => s + (Number(d.value) || 0), 0);

    let offset = 0;
    let arcs = '';
    if (total === 0) {
        arcs = `<circle cx="${cx}" cy="${cx}" r="${r}" fill="none" stroke="rgba(255,255,255,.06)" stroke-width="${stroke}"/>`;
    } else {
        data.forEach(d => {
            const val = Number(d.value) || 0;
            if (val === 0) return;
            const len = (val / total) * c;
            arcs += `<circle cx="${cx}" cy="${cx}" r="${r}" fill="none"
                        stroke="${d.color}" stroke-width="${stroke}"
                        stroke-dasharray="${len - 2.5} ${c - len + 2.5}"
                        stroke-dashoffset="${-offset}"
                        transform="rotate(-90 ${cx} ${cx})"
                        stroke-linecap="round"/>`;
            offset += len;
        });
    }

    return `<svg class="donut" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
        ${arcs}
        <text x="${cx}" y="${cx - 2}" text-anchor="middle" class="donut-num">${Fmt.num(total)}</text>
        <text x="${cx}" y="${cx + 14}" text-anchor="middle" class="donut-cap">${Fmt.esc(options.caption || '合计')}</text>
    </svg>`;
}

/* ---------- 图例 ---------- */
function legend(data) {
    return `<div class="legend">${data.map(d => `
        <div class="legend-item">
            <span class="sw" style="background:${d.color}"></span>
            <span class="nm">${Fmt.esc(d.name)}</span>
            <span class="vl">${Fmt.num(d.value)}</span>
        </div>`).join('')}</div>`;
}

/* ---------- 空状态 ---------- */
function emptyBox(text = '暂无数据', hint = '') {
    return `<div class="empty"><span class="ico">[ ]</span>${Fmt.esc(text)}
        ${hint ? `<div style="margin-top:5px;font-size:11.5px">${Fmt.esc(hint)}</div>` : ''}</div>`;
}

/* ---------- 加载态 ---------- */
function loadingBox(text = '加载中') {
    return `<div class="loading"><span class="spin"></span>${Fmt.esc(text)}</div>`;
}

/* ---------- 分页控件 ---------- */
function pager(page, onGo) {
    const total = Number(page.total || 0);
    const cur = Number(page.current || 1);
    const size = Number(page.size || 10);
    const pages = Number(page.pages || Math.ceil(total / size) || 1);
    return {
        html: `<div class="pager">
            <span>共 <b class="mono" style="color:var(--tx-1)">${Fmt.num(total)}</b> 条</span>
            <span class="spacer"></span>
            <button class="btn btn-sm" data-pg="${cur - 1}" ${cur <= 1 ? 'disabled' : ''}>上一页</button>
            <span class="pages">${cur} / ${pages}</span>
            <button class="btn btn-sm" data-pg="${cur + 1}" ${cur >= pages ? 'disabled' : ''}>下一页</button>
        </div>`,
        bind(root) {
            root.querySelectorAll('[data-pg]').forEach(b => {
                b.onclick = () => onGo(Number(b.dataset.pg));
            });
        }
    };
}
