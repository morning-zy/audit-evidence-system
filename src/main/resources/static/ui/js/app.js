/* ==========================================================================
   应用主逻辑：路由 + 各页面渲染
   单文件按页面顺序组织：
     Layout（骨架/导航） → 登录 → 工作台 → 项目 → 事项 → 证据 → 复核 → 日志
   ========================================================================== */

const App = {
    user: null,
    route: 'dashboard',
    ctx: {},          // 路由上下文（如 projectId）

    /* ---------- 登录态 ---------- */
    isAdmin() {
        return this.user && this.user.role === 'ADMIN';
    },

    canWrite() {
        return this.user && this.user.role !== 'VIEWER';
    }
};

/* ==========================================================================
   一、导航定义
   ========================================================================== */
const NAV = [
    {
        label: '概览', items: [
            {key: 'dashboard', name: '工作台', icon: 'dash', title: '工作台', desc: '全局统计概览与项目进度'}
        ]
    },
    {
        label: '审计执行', items: [
            {key: 'projects', name: '审计项目', icon: 'project', title: '审计项目', desc: '项目立项、状态流转与进度跟踪'},
            {key: 'issues', name: '审计事项', icon: 'issue', title: '审计事项', desc: '项目下的具体审计点与风险点'},
            {key: 'evidences', name: '审计证据', icon: 'evidence', title: '审计证据', desc: '证据采集、提交与流转轨迹'},
            {key: 'sampling', name: '审计抽样', icon: 'sampling', title: '审计抽样', desc: '控制测试抽样：样本量计算与偏差率推断'},
            {key: 'variable-sampling', name: '变量抽样', icon: 'sampling', title: '变量抽样', desc: '细节测试：三种估计方法推断总体错报上限'}
        ]
    },
    {
        label: '复核与留痕', items: [
            {key: 'review', name: '复核待办', icon: 'review', title: '复核待办', desc: '待复核证据清单，仅审计主管可操作'},
            {key: 'logs', name: '操作日志', icon: 'log', title: '操作日志', desc: '系统操作留痕（仅审计主管可见）', adminOnly: true}
        ]
    }
];

/* ==========================================================================
   二、登录
   ========================================================================== */
/* 演示账号：与 db/data.sql 的种子数据严格对应，密码统一 123456。
   改种子数据时这里必须同步，否则快捷登录会直接报"用户名或密码错误"。 */
const DEMO_ACCOUNTS = [
    {u: 'admin', role: '审计主管', tip: '全权限，可复核'},
    {u: 'lisi', role: '审计员', tip: '可编制证据'},
    {u: 'zhangsan', role: '审计主管', tip: '可复核证据'},
    {u: 'sunqi', role: '查阅人', tip: '只读，不能增删改'}
];

function renderLogin() {
    const host = document.getElementById('account-list');
    host.innerHTML = DEMO_ACCOUNTS.map(a => `
        <div class="account-chip" data-u="${a.u}">
            <span><span class="acc-name">${a.u}</span>
                <span class="acc-role" style="margin-left:9px">${a.role} · ${a.tip}</span></span>
            <span class="acc-role mono">/ 123456</span>
        </div>`).join('');

    host.querySelectorAll('.account-chip').forEach(chip => {
        chip.onclick = () => {
            document.getElementById('username').value = chip.dataset.u;
            document.getElementById('password').value = '123456';
            document.getElementById('login-form').requestSubmit();
        };
    });

    document.getElementById('login-form').onsubmit = async (e) => {
        e.preventDefault();
        const btn = document.getElementById('login-btn');
        const username = document.getElementById('username').value.trim();
        const password = document.getElementById('password').value;
        if (!username || !password) return Toast.error('请填写用户名和密码');

        btn.disabled = true;
        btn.textContent = '登录中…';
        try {
            const data = await API.login({username, password});
            Store.token = data.token;
            Store.user = data;
            enterApp(data);
        } catch (err) {
            Toast.error(err.message || '登录失败');
        } finally {
            btn.disabled = false;
            btn.textContent = '登录系统';
        }
    };
}

/* ==========================================================================
   三、进入主界面
   ========================================================================== */
function enterApp(user) {
    App.user = user;
    document.getElementById('login-view').classList.add('hidden');
    document.getElementById('app-view').classList.remove('hidden');

    document.getElementById('u-avatar').textContent = (user.realName || user.username || '?').slice(0, 1);
    document.getElementById('u-name').textContent = user.realName || user.username;
    document.getElementById('u-role').textContent = user.roleName || DICT.role[user.role] || user.role;

    renderNav();
    go('dashboard');
    startClock();
}

function renderNav() {
    const nav = document.getElementById('nav');
    nav.innerHTML = NAV.map(group => {
        const items = group.items.filter(it => !it.adminOnly || App.isAdmin());
        if (!items.length) return '';
        return `<div class="nav-label">${group.label}</div>` + items.map(it => `
            <div class="nav-item" data-key="${it.key}">
                ${Icon[it.icon]}<span class="lbl">${it.name}</span>
            </div>`).join('');
    }).join('');

    nav.querySelectorAll('.nav-item').forEach(el => {
        el.onclick = () => go(el.dataset.key);
    });
}

function markNav(key) {
    document.querySelectorAll('.nav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.key === key);
    });
}

function setCrumb(parts) {
    document.getElementById('crumb').innerHTML = parts
        .map(p => (p.action
            ? `<span style="cursor:pointer;color:var(--accent)" data-crumb="${Fmt.esc(p.action)}">${Fmt.esc(p.text)}</span>`
            : `<b>${Fmt.esc(p.text)}</b>`))
        .join('<span style="color:var(--tx-3)">/</span>');

    document.querySelectorAll('[data-crumb]').forEach(el => {
        el.onclick = () => go(el.dataset.crumb);
    });
}

function startClock() {
    const tick = () => {
        const d = new Date();
        document.getElementById('clock').textContent =
            [d.getHours(), d.getMinutes(), d.getSeconds()]
                .map(n => String(n).padStart(2, '0')).join(':');
    };
    tick();
    setInterval(tick, 1000);
}

/* ==========================================================================
   四、路由
   ========================================================================== */
const ROUTES = {};

function go(key, ctx = {}) {
    if (!ROUTES[key]) key = 'dashboard';
    App.route = key;
    App.ctx = ctx;
    Drawer.close();
    markNav(key === 'project-detail' ? 'projects' : key);
    document.getElementById('content').scrollTop = 0;
    ROUTES[key]();
}

const content = () => document.getElementById('content');

/* ==========================================================================
   五、工作台
   ========================================================================== */
ROUTES.dashboard = async function () {
    setCrumb([{text: '概览'}, {text: '工作台'}]);
    content().innerHTML = loadingBox('正在汇总统计');

    let d;
    try {
        d = (await API.dashboard()) || {};
    } catch (e) {
        content().innerHTML = emptyBox('统计加载失败', e.message);
        return;
    }

    const statusColors = ['#7d8998', '#e3b341', '#2dd4a7', '#f2555a'];
    const statusData = (d.evidenceStatusDist || []).map((x, i) => ({
        name: x.name, value: x.value, color: statusColors[i % statusColors.length]
    }));

    const typeColors = ['#5b9dff', '#2dd4a7', '#7f77dd', '#e3b341', '#f2555a'];
    const typeData = (d.evidenceTypeDist || []).map((x, i) => ({
        name: x.name, value: x.value, color: typeColors[i % typeColors.length]
    }));

    const projects = d.projectProgressList || [];

    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">工作台</h1>
                <p class="page-desc">${Fmt.esc(App.user.realName)}，当前有
                    <b style="color:var(--warn)">${Fmt.num(d.pendingReviewCount)}</b> 条证据等待复核</p>
            </div>
            <div class="flex">
                <button class="btn btn-sm" id="btn-refresh">${Icon.refresh} 刷新</button>
            </div>
        </div>

        <div class="metric-grid">
            <div class="metric accent">
                <div class="lb">进行中项目</div>
                <div class="vl">${Fmt.num(d.activeProjectCount)}<span class="unit">个</span></div>
                <div class="foot">已立项且未归档</div>
            </div>
            <div class="metric info">
                <div class="lb">审计事项 / 已完成</div>
                <div class="vl">${Fmt.num(d.issueTotal)}<span class="unit">/ ${Fmt.num(d.issueCompleted)}</span></div>
                <div class="foot">${d.issueTotal ? Math.round((d.issueCompleted / d.issueTotal) * 100) : 0}% 完成率</div>
            </div>
            <div class="metric">
                <div class="lb">证据总数</div>
                <div class="vl">${Fmt.num(d.evidenceTotal)}<span class="unit">条</span></div>
                <div class="foot">我的贡献 ${Fmt.num(d.myEvidenceCount)} 条</div>
            </div>
            <div class="metric warn">
                <div class="lb">待复核</div>
                <div class="vl">${Fmt.num(d.pendingReviewCount)}<span class="unit">条</span></div>
                <div class="foot">需审计主管处理</div>
            </div>
        </div>

        <div class="grid-2">
            <div class="card">
                <div class="card-head">
                    <div class="card-title">项目进度</div>
                    <button class="btn btn-sm btn-ghost" data-go="projects" style="font-size:11.5px">全部项目</button>
                </div>
                <div class="card-body">
                    ${projects.length ? projects.map(p => `
                        <div style="padding:11px 0;border-bottom:1px solid var(--line)">
                            <div class="flex" style="justify-content:space-between;margin-bottom:7px">
                                <span style="font-size:12.5px;color:var(--tx-1);cursor:pointer"
                                      data-proj="${p.id}">${Fmt.esc(p.projectName)}</span>
                                ${tag('projectStatus', p.status)}
                            </div>
                            <div class="flex" style="justify-content:space-between;margin-bottom:6px">
                                <span class="txt-3" style="font-size:11px">
                                    ${Fmt.esc(p.auditedUnit || '-')} · 事项 ${Fmt.num(p.issueCompleted)}/${Fmt.num(p.issueTotal)}
                                    · 证据 ${Fmt.num(p.evidenceTotal)}</span>
                            </div>
                            ${bar(p.progress, p.progress >= 80 ? '' : (p.progress >= 40 ? 'info' : 'warn'))}
                        </div>`).join('').replace(/border-bottom:1px solid var\(--line\)"([^>]*)>$/, '">')
        : emptyBox('暂无项目')}
                </div>
            </div>

            <div style="display:grid;gap:16px">
                <div class="card">
                    <div class="card-head"><div class="card-title">证据状态分布</div></div>
                    <div class="card-body">
                        <div class="donut-wrap">
                            ${donut(statusData, {caption: '证据'})}
                            ${legend(statusData)}
                        </div>
                    </div>
                </div>
                <div class="card">
                    <div class="card-head"><div class="card-title">证据类型分布</div></div>
                    <div class="card-body">
                        <div class="donut-wrap">
                            ${donut(typeData, {caption: '类型'})}
                            ${legend(typeData)}
                        </div>
                    </div>
                </div>
            </div>
        </div>`;

    content().querySelector('#btn-refresh').onclick = () => go('dashboard');
    content().querySelectorAll('[data-go]').forEach(b => (b.onclick = () => go(b.dataset.go)));
    content().querySelectorAll('[data-proj]').forEach(b => {
        b.onclick = () => go('project-detail', {projectId: b.dataset.proj});
    });
};

/* ==========================================================================
   六、项目列表
   ========================================================================== */
const ProjectPage = {
    page: {pageNum: 1, pageSize: 10},
    filter: {}
};

ROUTES.projects = async function () {
    setCrumb([{text: '审计执行'}, {text: '审计项目'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">审计项目</h1>
                <p class="page-desc">项目立项、状态流转与进度跟踪</p>
            </div>
            ${App.canWrite() ? `<button class="btn btn-primary" id="btn-add">${Icon.plus} 新建项目</button>` : ''}
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="search-box">${Icon.search}
                    <input type="text" id="f-keyword" placeholder="搜索项目名称 / 被审计单位">
                </div>
                <div class="filter-inline">
                    <select id="f-status">${optionsFrom(DICT.projectStatus, '', '全部状态')}</select>
                    <select id="f-type">${optionsFrom(DICT.auditType, '', '全部类型')}</select>
                    <select id="f-risk">${optionsFrom(
        {1: '低风险', 2: '中风险', 3: '高风险'}, '', '全部风险')}</select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-search">${Icon.search} 查询</button>
            </div>
            <div id="list-host">${loadingBox()}</div>
            <div id="pager-host"></div>
        </div>`;

    if (App.canWrite()) content().querySelector('#btn-add').onclick = () => ProjectPage.edit(null);
    content().querySelector('#btn-search').onclick = () => {
        ProjectPage.page.pageNum = 1;
        ProjectPage.load();
    };
    content().querySelector('#f-keyword').onkeydown = (e) => {
        if (e.key === 'Enter') {
            ProjectPage.page.pageNum = 1;
            ProjectPage.load();
        }
    };

    ProjectPage.load();
};

ProjectPage.load = async function () {
    const host = document.getElementById('list-host');
    host.innerHTML = loadingBox();

    const kw = document.getElementById('f-keyword').value.trim();
    const params = {
        pageNum: ProjectPage.page.pageNum,
        pageSize: ProjectPage.page.pageSize,
        keyword: kw || '',
        status: document.getElementById('f-status').value,
        auditType: document.getElementById('f-type').value,
        riskLevel: document.getElementById('f-risk').value
    };

    let res;
    try {
        res = await API.projectPage(params);
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    const rows = res.records || [];
    if (!rows.length) {
        host.innerHTML = emptyBox('没有符合条件的项目', '换个筛选条件试试');
        document.getElementById('pager-host').innerHTML = '';
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr>
            <th style="width:26%">项目</th>
            <th style="width:110px">类型</th>
            <th style="width:18%">被审计单位</th>
            <th style="width:90px">负责人</th>
            <th style="width:90px">状态</th>
            <th style="width:90px">风险</th>
            <th style="width:15%">进度</th>
            <th style="width:110px">操作</th>
        </tr></thead>
        <tbody>${rows.map(p => `
            <tr>
                <td class="primary">
                    <div style="cursor:pointer" data-open="${p.id}">${Fmt.esc(p.projectName)}</div>
                    <div class="sub mono">${Fmt.esc(p.projectCode || '')}</div>
                </td>
                <td class="nowrap">${Fmt.esc(p.auditTypeName || DICT.auditType[p.auditType] || '-')}</td>
                <td class="ellipsis" title="${Fmt.esc(p.auditedUnit || '')}">${Fmt.esc(p.auditedUnit || '-')}</td>
                <td class="nowrap">${Fmt.esc(p.leaderName || '-')}</td>
                <td>${tag('projectStatus', p.status)}</td>
                <td>${tag('riskLevel', p.riskLevel)}</td>
                <td>${bar(p.progress)}</td>
                <td>
                    <div class="cell-actions">
                        <button class="btn-link" data-open="${p.id}">详情</button>
                        ${App.canWrite() ? `<button class="btn-link" data-edit="${p.id}">编辑</button>` : ''}
                    </div>
                </td>
            </tr>`).join('')}
        </tbody></table></div>`;

    const pg = pager(res, (n) => {
        ProjectPage.page.pageNum = n;
        ProjectPage.load();
    });
    const ph = document.getElementById('pager-host');
    ph.innerHTML = pg.html;
    pg.bind(ph);

    host.querySelectorAll('[data-open]').forEach(b => {
        b.onclick = () => go('project-detail', {projectId: b.dataset.open});
    });
    host.querySelectorAll('[data-edit]').forEach(b => {
        b.onclick = () => ProjectPage.edit(b.dataset.edit);
    });
};

ProjectPage.edit = async function (id) {
    let p = {status: 0, riskLevel: 1, auditType: 'FINANCIAL'};
    if (id) {
        try {
            p = await API.projectDetail(id);
        } catch (e) {
            return Toast.error(e.message);
        }
    }

    let users = [];
    try {
        users = await API.users() || [];
    } catch (e) {
        /* 无权限时降级为空 */
    }

    Modal.open({
        title: id ? '编辑项目' : '新建审计项目',
        wide: true,
        body: `
            <div class="form-row">
                <div class="field"><label>项目名称<span class="req">*</span></label>
                    <input type="text" data-field="projectName" value="${Fmt.esc(p.projectName || '')}"
                           placeholder="如：2026年度财务报表审计"></div>
                <div class="field"><label>审计类型<span class="req">*</span></label>
                    <select data-field="auditType">${optionsFrom(DICT.auditType, p.auditType)}</select></div>
            </div>
            <div class="field"><label>被审计单位<span class="req">*</span></label>
                <input type="text" data-field="auditedUnit" value="${Fmt.esc(p.auditedUnit || '')}"
                       placeholder="如：南京某某科技有限公司"></div>
            <div class="form-row">
                <div class="field"><label>项目负责人</label>
                    <select data-field="leaderId">
                        <option value="">未指定</option>
                        ${users.map(u => `<option value="${u.id}" ${String(p.leaderId) === String(u.id) ? 'selected' : ''}>
                            ${Fmt.esc(u.realName)}（${Fmt.esc(u.roleName || DICT.role[u.role] || u.role)}）</option>`).join('')}
                    </select></div>
                <div class="field"><label>风险等级</label>
                    <select data-field="riskLevel" data-type="number">
                        ${optionsFrom({1: '低风险', 2: '中风险', 3: '高风险'}, p.riskLevel)}
                    </select></div>
            </div>
            <div class="form-row">
                <div class="field"><label>开始日期</label>
                    <input type="date" data-field="startDate" value="${Fmt.d(p.startDate) === '-' ? '' : Fmt.d(p.startDate)}"></div>
                <div class="field"><label>结束日期</label>
                    <input type="date" data-field="endDate" value="${Fmt.d(p.endDate) === '-' ? '' : Fmt.d(p.endDate)}"></div>
            </div>
            <div class="field mb-16"><label>项目描述</label>
                <textarea data-field="description" placeholder="审计范围、目标、依据等">${Fmt.esc(p.description || '')}</textarea></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="m-save">保存</button>`,
        onMount(el, close) {
            el.querySelector('#m-save').onclick = async () => {
                const body = collectForm(el);
                if (!body.projectName || !body.auditedUnit) return Toast.error('项目名称与被审计单位为必填');
                if (id) body.id = id;
                const btn = el.querySelector('#m-save');
                btn.disabled = true;
                try {
                    if (id) {
                        await API.projectUpdate(body);
                        Toast.ok('修改成功');
                    } else {
                        await API.projectSave(body);
                        Toast.ok('创建成功');
                    }
                    close();
                    if (App.route === 'project-detail') go('project-detail', {projectId: id});
                    else ProjectPage.load();
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                }
            };
        }
    });
};

/* ==========================================================================
   七、项目详情
   ========================================================================== */
ROUTES['project-detail'] = async function () {
    const pid = App.ctx.projectId;
    content().innerHTML = loadingBox('加载项目详情');

    let p, stat, issues;
    try {
        [p, stat, issues] = await Promise.all([
            API.projectDetail(pid),
            API.projectStat(pid).catch(() => null),
            API.issueList(pid).catch(() => [])
        ]);
    } catch (e) {
        content().innerHTML = emptyBox('项目不存在或无权访问', e.message);
        return;
    }

    setCrumb([{text: '审计项目', action: 'projects'}, {text: p.projectName}]);

    const statusFlow = [
        {code: 0, name: '草稿'}, {code: 1, name: '进行中'}, {code: 2, name: '待复核'}, {code: 3, name: '已归档'}
    ];
    const cur = Number(p.status);

    content().innerHTML = `
        <div class="page-head">
            <div>
                <div class="flex" style="gap:10px;margin-bottom:7px">
                    <h1 class="page-title" style="margin:0">${Fmt.esc(p.projectName)}</h1>
                    ${tag('projectStatus', p.status)}
                    ${tag('riskLevel', p.riskLevel)}
                </div>
                <p class="page-desc"><span class="mono">${Fmt.esc(p.projectCode || '')}</span>
                    · ${Fmt.esc(p.auditTypeName || '')} · ${Fmt.esc(p.auditedUnit || '')}</p>
            </div>
            <div class="flex">
                <button class="btn btn-sm" data-back>${Icon.back} 返回</button>
                ${App.canWrite() ? `<button class="btn btn-sm" id="btn-edit">编辑</button>` : ''}
                ${App.canWrite() ? `<button class="btn btn-sm btn-danger" id="btn-del">删除</button>` : ''}
            </div>
        </div>

        <div class="metric-grid">
            <div class="metric accent">
                <div class="lb">整体进度</div>
                <div class="vl">${p.progress || 0}<span class="unit">%</span></div>
                <div class="foot">按事项完成率计算</div>
            </div>
            <div class="metric info">
                <div class="lb">审计事项</div>
                <div class="vl">${Fmt.num(p.issueTotal)}<span class="unit">个</span></div>
                <div class="foot">已完成 ${Fmt.num(p.issueCompleted)} 个</div>
            </div>
            <div class="metric">
                <div class="lb">审计证据</div>
                <div class="vl">${Fmt.num(p.evidenceTotal)}<span class="unit">条</span></div>
                <div class="foot">已确认 ${Fmt.num(p.evidenceConfirmed)} 条</div>
            </div>
            <div class="metric warn">
                <div class="lb">当前状态</div>
                <div class="vl" style="font-size:19px;padding-top:5px">${Fmt.esc(p.statusName || '')}</div>
                <div class="foot">${Fmt.d(p.startDate)} 至 ${Fmt.d(p.endDate)}</div>
            </div>
        </div>

        <div class="grid-2">
            <div style="display:grid;gap:16px">
                <div class="card">
                    <div class="card-head">
                        <div class="card-title">项目信息</div>
                        ${App.canWrite() ? `<button class="btn btn-sm btn-ghost" id="btn-status">流转状态</button>` : ''}
                    </div>
                    <div class="card-body">
                        <div class="kv-grid">
                            <div class="kv"><div class="k">项目编号</div><div class="v mono">${Fmt.esc(p.projectCode || '-')}</div></div>
                            <div class="kv"><div class="k">项目负责人</div><div class="v">${Fmt.esc(p.leaderName || '-')}</div></div>
                            <div class="kv"><div class="k">审计类型</div><div class="v">${Fmt.esc(p.auditTypeName || '-')}</div></div>
                            <div class="kv"><div class="k">风险等级</div><div class="v">${Fmt.esc(p.riskLevelName || '-')}</div></div>
                            <div class="kv"><div class="k">开始日期</div><div class="v mono">${Fmt.d(p.startDate)}</div></div>
                            <div class="kv"><div class="k">结束日期</div><div class="v mono">${Fmt.d(p.endDate)}</div></div>
                            <div class="kv full"><div class="k">被审计单位</div><div class="v">${Fmt.esc(p.auditedUnit || '-')}</div></div>
                            <div class="kv full"><div class="k">项目描述</div>
                                <div class="v" style="color:var(--tx-2);line-height:1.7">${Fmt.esc(p.description || '暂无描述')}</div></div>
                        </div>

                        <div class="section-label">状态流转路径</div>
                        <div class="flex" style="gap:6px;flex-wrap:wrap">
                            ${statusFlow.map((s, i) => `
                                ${i ? '<span style="color:var(--tx-3)">→</span>' : ''}
                                <span class="tag ${Number(s.code) === cur ? 'tag-green' : 'tag-ghost'} ${Number(s.code) === cur ? '' : 'plain'}"
                                      style="${Number(s.code) < cur ? 'opacity:.55' : ''}">${s.name}</span>
                            `).join('')}
                        </div>
                    </div>
                </div>

                <div class="card">
                    <div class="card-head">
                        <div class="card-title">审计事项 <span class="cnt">${issues.length}</span></div>
                        ${App.canWrite() ? `<button class="btn btn-sm" id="btn-add-issue">${Icon.plus} 新增事项</button>` : ''}
                    </div>
                    <div class="table-wrap">
                        ${issues.length ? `<table>
                            <thead><tr><th>事项</th><th>风险点</th><th>承办人</th><th>状态</th><th>证据</th><th></th></tr></thead>
                            <tbody>${issues.map(i => `
                                <tr>
                                    <td class="primary">${Fmt.esc(i.issueTitle)}
                                        <div class="sub mono">${Fmt.esc(i.issueNo || '')}</div></td>
                                    <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
                                        title="${Fmt.esc(i.riskPoint || '')}">${Fmt.esc(i.riskPoint || '-')}</td>
                                    <td>${Fmt.esc(i.auditorName || '-')}</td>
                                    <td>${tag('issueStatus', i.status)}</td>
                                    <td class="mono">${Fmt.num(i.evidenceCount)}</td>
                                    <td><button class="btn-link" data-issue="${i.id}">查看证据</button></td>
                                </tr>`).join('')}</tbody></table>`
            : emptyBox('该项目下还没有事项')}
                    </div>
                </div>
            </div>

            <div class="card">
                <div class="card-head"><div class="card-title">证据状态分布
                    <span class="cnt">${stat ? 'Redis 缓存' : ''}</span></div></div>
                <div class="card-body">
                    ${stat && stat.evidenceStatusDist && stat.evidenceStatusDist.length ? `
                        <div class="donut-wrap" style="margin-bottom:20px">
                            ${donut(stat.evidenceStatusDist.map((x, i) => ({
        name: x.name, value: x.value,
        color: ['#7d8998', '#e3b341', '#2dd4a7', '#f2555a'][i % 4]
    })), {caption: '证据', size: 108, stroke: 12})}
                            ${legend(stat.evidenceStatusDist.map((x, i) => ({
        name: x.name, value: x.value,
        color: ['#7d8998', '#e3b341', '#2dd4a7', '#f2555a'][i % 4]
    })))}
                        </div>
                        <div class="hbar-list">
                            ${(stat.evidenceTypeDist || []).map((t, i) => {
        const max = Math.max(...stat.evidenceTypeDist.map(x => Number(x.value) || 0), 1);
        return `<div class="hbar-item">
                                    <div class="top"><span class="nm">${Fmt.esc(t.name)}</span>
                                        <span class="vl">${Fmt.num(t.value)}</span></div>
                                    ${bar((t.value / max) * 100)}
                                </div>`;
    }).join('')}
                        </div>`
        : `<div class="txt-3" style="font-size:12px;line-height:1.8">
                            该项目暂无证据。<br>可以先在上方"审计事项"里建事项，再到"审计证据"页采集证据。</div>`}
                </div>
            </div>
        </div>`;

    const c = content();
    c.querySelector('[data-back]').onclick = () => go('projects');
    if (App.canWrite()) {
        c.querySelector('#btn-edit').onclick = () => ProjectPage.edit(pid);
        c.querySelector('#btn-del').onclick = () => {
            Modal.confirm('删除项目', `确定删除「${p.projectName}」？项目下有事项时无法删除。`, async () => {
                await API.projectDelete(pid);
                Toast.ok('已删除');
                go('projects');
            });
        };
        c.querySelector('#btn-status').onclick = () => ProjectPage.changeStatus(p);
        c.querySelector('#btn-add-issue').onclick = () => IssuePage.edit(null, pid);
    } else {
        c.querySelector('#btn-edit') && (c.querySelector('#btn-edit').onclick = null);
    }
    c.querySelectorAll('[data-issue]').forEach(b => {
        b.onclick = () => go('evidences', {issueId: b.dataset.issue});
    });
};

ProjectPage.changeStatus = function (p) {
    const cur = Number(p.status);
    const next = {0: [1], 1: [2], 2: [1, 3], 3: []}[cur] || [];
    if (!next.length) return Toast.info('当前状态已无可流转的下一步');

    Modal.open({
        title: '流转项目状态',
        body: `<div class="notice"><span class="nk">当前状态</span>
                   <span>${Fmt.esc(p.statusName)}，请选择目标状态</span></div>
               <div class="field mb-16"><label>目标状态</label>
                   <select id="m-target">${next.map(n =>
        `<option value="${n}">${DICT.projectStatus[n].text}</option>`).join('')}</select></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="m-ok">确认流转</button>`,
        onMount(el, close) {
            el.querySelector('#m-ok').onclick = async () => {
                const target = el.querySelector('#m-target').value;
                try {
                    await API.projectStatus(p.id, target);
                    Toast.ok('状态已更新');
                    close();
                    go('project-detail', {projectId: p.id});
                } catch (e) {
                    Toast.error(e.message);
                }
            };
        }
    });
};

/* ==========================================================================
   八、审计事项列表
   ========================================================================== */
const IssuePage = {
    page: {pageNum: 1, pageSize: 10}
};

ROUTES.issues = async function () {
    setCrumb([{text: '审计执行'}, {text: '审计事项'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">审计事项</h1>
                <p class="page-desc">项目下的具体审计点，是证据的归属单位</p>
            </div>
            ${App.canWrite() ? `<button class="btn btn-primary" id="btn-add-issue">${Icon.plus} 新增事项</button>` : ''}
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="filter-inline">
                    <select id="f-project" style="min-width:200px"><option value="">全部项目</option></select>
                    <select id="f-status">${optionsFrom(DICT.issueStatus, '', '全部状态')}</select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-search">${Icon.search} 查询</button>
            </div>
            <div id="list-host">${loadingBox()}</div>
            <div id="pager-host"></div>
        </div>`;

    // 项目下拉
    try {
        const projects = await API.projectProgress(50) || [];
        const sel = content().querySelector('#f-project');
        sel.innerHTML = '<option value="">全部项目</option>' + projects.map(p =>
            `<option value="${p.id}">${Fmt.esc(p.projectName)}</option>`).join('');
    } catch (e) { /* 忽略 */
    }

    if (App.canWrite()) content().querySelector('#btn-add-issue').onclick = () => IssuePage.edit(null, null);
    content().querySelector('#btn-search').onclick = () => {
        IssuePage.page.pageNum = 1;
        IssuePage.load();
    };
    IssuePage.load();
};

IssuePage.load = async function () {
    const host = document.getElementById('list-host');
    host.innerHTML = loadingBox();
    let res;
    try {
        res = await API.issuePage({
            pageNum: IssuePage.page.pageNum,
            pageSize: IssuePage.page.pageSize,
            projectId: document.getElementById('f-project').value,
            status: document.getElementById('f-status').value
        });
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    const rows = res.records || [];
    if (!rows.length) {
        host.innerHTML = emptyBox('暂无审计事项', '先在项目下新增事项');
        document.getElementById('pager-host').innerHTML = '';
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr><th style="width:22%">事项</th><th>所属项目</th><th>风险点</th>
            <th>承办人</th><th>状态</th><th>证据数</th><th style="width:120px">操作</th></tr></thead>
        <tbody>${rows.map(i => `
            <tr>
                <td class="primary">${Fmt.esc(i.issueTitle)}
                    <div class="sub mono">${Fmt.esc(i.issueNo || '')}</div></td>
                <td>${Fmt.esc(i.projectName || '-')}</td>
                <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
                    title="${Fmt.esc(i.riskPoint || '')}">${Fmt.esc(i.riskPoint || '-')}</td>
                <td>${Fmt.esc(i.auditorName || '-')}</td>
                <td>${tag('issueStatus', i.status)}</td>
                <td class="mono">${Fmt.num(i.evidenceCount)}</td>
                <td><div class="cell-actions">
                    <button class="btn-link" data-evi="${i.id}">证据</button>
                    ${App.canWrite() ? `<button class="btn-link" data-edit="${i.id}">编辑</button>` : ''}
                </div></td>
            </tr>`).join('')}</tbody></table></div>`;

    const pg = pager(res, (n) => {
        IssuePage.page.pageNum = n;
        IssuePage.load();
    });
    const ph = document.getElementById('pager-host');
    ph.innerHTML = pg.html;
    pg.bind(ph);

    host.querySelectorAll('[data-evi]').forEach(b => {
        b.onclick = () => go('evidences', {issueId: b.dataset.evi});
    });
    host.querySelectorAll('[data-edit]').forEach(b => {
        const item = rows.find(x => String(x.id) === b.dataset.edit);
        b.onclick = () => IssuePage.edit(item, null);
    });
};

IssuePage.edit = async function (issue, projectId) {
    let users = [];
    try {
        users = await API.users() || [];
    } catch (e) { /* 忽略 */
    }

    let projects = [];
    try {
        projects = await API.projectProgress(50) || [];
    } catch (e) { /* 忽略 */
    }

    const it = issue || {status: 0};

    Modal.open({
        title: issue ? '编辑事项' : '新增审计事项',
        wide: true,
        body: `
            <div class="field"><label>所属项目<span class="req">*</span></label>
                <select data-field="projectId" ${issue ? 'disabled' : ''}>
                    <option value="">请选择项目</option>
                    ${projects.map(p => `<option value="${p.id}"
                        ${String(projectId || it.projectId) === String(p.id) ? 'selected' : ''}>
                        ${Fmt.esc(p.projectName)}</option>`).join('')}
                </select></div>
            <div class="field"><label>事项标题<span class="req">*</span></label>
                <input type="text" data-field="issueTitle" value="${Fmt.esc(it.issueTitle || '')}"
                       placeholder="如：货币资金真实性核查"></div>
            <div class="field"><label>风险点描述</label>
                <textarea data-field="riskPoint" placeholder="该事项关注的风险与可能的错报形式">${Fmt.esc(it.riskPoint || '')}</textarea></div>
            <div class="field mb-16"><label>承办审计员</label>
                <select data-field="auditorId">
                    <option value="">未指定</option>
                    ${users.map(u => `<option value="${u.id}" ${String(it.auditorId) === String(u.id) ? 'selected' : ''}>
                        ${Fmt.esc(u.realName)}（${Fmt.esc(u.roleName || DICT.role[u.role] || u.role)}）</option>`).join('')}
                </select></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="m-save">保存</button>`,
        onMount(el, close) {
            el.querySelector('#m-save').onclick = async () => {
                const body = collectForm(el);
                if (!issue) body.id = null;
                else body.id = issue.id;
                // disabled 的 select 不会被 collectForm 读到，这里补上
                if (issue) body.projectId = it.projectId;
                if (!body.issueTitle || !body.projectId) return Toast.error('项目与事项标题为必填');
                const btn = el.querySelector('#m-save');
                btn.disabled = true;
                try {
                    if (issue) {
                        await API.issueUpdate(body);
                        Toast.ok('已保存');
                    } else {
                        await API.issueSave(body);
                        Toast.ok('已创建');
                    }
                    close();
                    go('project-detail', {projectId: Number(body.projectId)});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                }
            };
        }
    });
};

/* ==========================================================================
   九、审计证据
   ========================================================================== */
const EvidencePage = {
    page: {pageNum: 1, pageSize: 10}
};

ROUTES.evidences = async function () {
    const presetIssue = App.ctx.issueId || '';
    setCrumb([{text: '审计执行'}, {text: '审计证据'}]);

    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">审计证据</h1>
                <p class="page-desc">证据采集、提交复核与流转轨迹 · 提交后必须经主管复核才能确认</p>
            </div>
            <div class="flex">
                <button class="btn btn-sm" id="btn-export">${Icon.download} 导出清单</button>
                ${App.canWrite() ? `<button class="btn btn-primary" id="btn-add-evi">${Icon.plus} 采集证据</button>` : ''}
            </div>
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="search-box">${Icon.search}<input type="text" id="f-evi-kw" placeholder="搜索证据名称"></div>
                <div class="filter-inline">
                    <select id="f-evi-status">${optionsFrom(DICT.evidenceStatus, '', '全部状态')}</select>
                    <select id="f-evi-type">${optionsFrom(DICT.evidenceType, '', '全部类型')}</select>
                    <select id="f-evi-reli">${optionsFrom(DICT.reliability, '', '全部可靠性')}</select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-search">${Icon.search} 查询</button>
            </div>
            <div id="list-host">${loadingBox()}</div>
            <div id="pager-host"></div>
        </div>`;

    if (App.canWrite()) content().querySelector('#btn-add-evi').onclick = () => EvidencePage.edit(null);
    content().querySelector('#btn-export').onclick = () => EvidencePage.export();
    content().querySelector('#btn-search').onclick = () => {
        EvidencePage.page.pageNum = 1;
        EvidencePage.load();
    };
    content().querySelector('#f-evi-kw').onkeydown = (e) => {
        if (e.key === 'Enter') {
            EvidencePage.page.pageNum = 1;
            EvidencePage.load();
        }
    };

    EvidencePage.load(presetIssue);
};

EvidencePage.query = function () {
    return {
        pageNum: EvidencePage.page.pageNum,
        pageSize: EvidencePage.page.pageSize,
        status: document.getElementById('f-evi-status').value,
        evidenceType: document.getElementById('f-evi-type').value,
        reliability: document.getElementById('f-evi-reli').value
    };
};

EvidencePage.load = async function (issueId) {
    const host = document.getElementById('list-host');
    host.innerHTML = loadingBox();

    const params = EvidencePage.query();
    if (issueId) params.issueId = issueId;

    let res;
    try {
        res = await API.evidencePage(params);
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    const rows = res.records || [];
    if (!rows.length) {
        host.innerHTML = emptyBox('暂无审计证据', App.canWrite() ? '点击右上角「采集证据」开始' : '');
        document.getElementById('pager-host').innerHTML = '';
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr>
            <th style="width:24%">证据</th><th>所属项目 / 事项</th><th>类型</th>
            <th>可靠性</th><th>状态</th><th>编制 / 复核</th><th style="width:130px">操作</th>
        </tr></thead>
        <tbody>${rows.map(e => `
            <tr>
                <td class="primary">
                    <div style="cursor:pointer" data-detail="${e.id}">${Fmt.esc(e.evidenceName)}</div>
                    <div class="sub mono">${Fmt.esc(e.evidenceNo || '')}</div>
                </td>
                <td>${Fmt.esc(e.projectName || '-')}
                    <div class="sub">${Fmt.esc(e.issueTitle || '')}</div></td>
                <td>${tag('evidenceType', e.evidenceType, e.evidenceTypeName)}</td>
                <td>${reliabilityBadge(e.reliabilityGrade, e.reliabilityName)}</td>
                <td>${tag('evidenceStatus', e.status, e.statusName)}</td>
                <td>${Fmt.esc(e.submitByName || '-')}
                    <div class="sub">${e.reviewByName ? '复核：' + Fmt.esc(e.reviewByName) : Fmt.dt(e.submitTime) === '-' ? '' : Fmt.dt(e.submitTime)}</div></td>
                <td><div class="cell-actions">
                    <button class="btn-link" data-detail="${e.id}">详情</button>
                    ${App.canWrite() && e.canEdit ? `<button class="btn-link" data-edit="${e.id}">编辑</button>` : ''}
                </div></td>
            </tr>`).join('')}</tbody></table></div>`;

    const pg = pager(res, (n) => {
        EvidencePage.page.pageNum = n;
        EvidencePage.load(issueId);
    });
    const ph = document.getElementById('pager-host');
    ph.innerHTML = pg.html;
    pg.bind(ph);

    host.querySelectorAll('[data-detail]').forEach(b => {
        b.onclick = () => EvidencePage.detail(b.dataset.detail);
    });
    host.querySelectorAll('[data-edit]').forEach(b => {
        const item = rows.find(x => String(x.id) === b.dataset.edit);
        b.onclick = () => EvidencePage.edit(item);
    });
};

EvidencePage.export = async function () {
    try {
        const resp = await API.evidenceExport(EvidencePage.query());
        if (!resp.ok) throw new Error('导出失败 HTTP ' + resp.status);
        const blob = await resp.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = '审计证据清单_' + new Date().toISOString().slice(0, 10) + '.xlsx';
        a.click();
        URL.revokeObjectURL(url);
        Toast.ok('已开始下载');
    } catch (e) {
        Toast.error(e.message);
    }
};

/* ---- 证据详情：信息 + 流转时间轴 + 可执行动作 ---- */
EvidencePage.detail = async function (id) {
    Drawer.open({title: '加载中…', body: loadingBox()});

    let e, flows;
    try {
        [e, flows] = await Promise.all([API.evidenceDetail(id), API.evidenceFlows(id).catch(() => [])]);
    } catch (err) {
        Drawer.close();
        return Toast.error(err.message);
    }

    const tlIcon = (a) => (a && a.startsWith('APPROVE')) ? 'ok'
        : (a && a.startsWith('REJECT')) ? 'bad' : 'now';

    /* ---- 三级复核进度条 ----
     * 三级复核最有价值的展示不是"当前是什么状态"，而是"走到第几级、每一级谁签的"。
     * 所以这里不显示状态徽章，而是画一条三级进度，每级都带上签字人和意见。
     * 已驳回时，驳回发生在哪一级就标红哪一级 —— 让人一眼看出是在哪一层被打回来的。
     */
    const reviewProgress = (e, flows) => {
        // 从流转记录里反查每一级的审理结果
        const byLevel = {};
        flows.forEach(f => {
            if (!f.reviewLevel) return;
            byLevel[f.reviewLevel] = f;   // 同级别多次时取最后一次
        });

        const levels = [
            {lv: 1, name: '详细复核', role: '项目负责人'},
            {lv: 2, name: '一般复核', role: '部门经理'},
            {lv: 3, name: '重点复核', role: '主任会计师'}
        ];

        // 判断每级状态：done / rejected / active / waiting
        const items = levels.map(l => {
            const f = byLevel[l.lv];
            let st;
            if (f && f.action && f.action.startsWith('REJECT')) st = 'rejected';
            else if (f && f.action && f.action.startsWith('APPROVE')) st = 'done';
            else if (e.reviewing && e.currentLevel === l.lv) st = 'active';
            else st = 'waiting';
            return {...l, st, flow: f};
        });

        // 未提交（草稿/已驳回无流转）时不画进度条，避免三条灰线造成噪音
        const started = e.status !== 0 && (e.submitTime || flows.some(f => f.reviewLevel));
        if (!started) return '';

        // 老数据兼容提示：三级复核改造前确认的证据只有一条旧版 APPROVE 记录，
        // 按级别反查会得到"三级全灰"的观感，容易被误认为数据出了问题。
        // 这种情况必须显式说明，而不是让人去猜。
        const hasLegacyApprove = flows.some(f => f.action === 'APPROVE' || f.action === 'REJECT');
        const legacyNote = (hasLegacyApprove && !flows.some(f => f.reviewLevel))
            ? `<div class="notice" style="margin-top:10px;margin-bottom:0">
                 <span class="nk">历史说明</span>
                 <span>该证据在<b>三级复核制度上线前</b>完成复核，当时为单级复核，
                 因此没有分级签字记录。历史记录按原样保留，未做改写。</span></div>`
            : '';

        const icon = (st) => st === 'done' ? '✓' : st === 'rejected' ? '✕' : st === 'active' ? '●' : '○';

        return `<div class="section-label">三级复核进度
            ${e.reviewFinished ? '<span class="tag tag-green plain" style="letter-spacing:0">已完成</span>' : ''}</div>
        <div class="rv-flow">
            ${items.map(it => `<div class="rv-node ${it.st}">
                <div class="rv-dot">${icon(it.st)}</div>
                <div class="rv-body">
                    <div class="rv-name">${it.lv}. ${it.name}</div>
                    <div class="rv-role">${it.role}</div>
                    ${it.flow ? `<div class="rv-sign">${Fmt.esc(it.flow.operatorName)}
                        <span class="txt-3">${Fmt.dt(it.flow.createTime)}</span></div>` : ''}
                    ${it.st === 'active' ? `<div class="rv-hint">当前环节</div>` : ''}
                    ${it.st === 'waiting' && e.reviewing === false && e.status === 3 ? '' : ''}
                </div>
            </div>`).join('<div class="rv-line"></div>')}
        </div>
        ${e.reviewing ? `<div class="notice" style="margin-top:10px;margin-bottom:0">
            <span class="nk">待办</span>
            <span>当前处于【${Fmt.esc(e.currentLevelName || '')}】，应由
            <b>${Fmt.esc(e.pendingRoleName || '')}</b> 处理。
            您的角色是【${Fmt.esc(e.myRoleName || '')}】${e.canReview
                ? '，可执行复核' : '，本环节无法操作'}。</span></div>` : ''}
        ${legacyNote}`;
    };

    // 可执行操作，严格按后端状态机规则在前端做一次展示层预判（真正的校验在后端）
    const acts = [];
    if (App.canWrite() && (e.status === 0 || e.status === 3)) {
        acts.push(`<button class="btn btn-primary btn-sm" data-act="submit">提交${e.status === 3 ? '重新复核' : '复核'}</button>`);
    }
    if (e.canReview) {
        // 按钮写明"这是第几级复核"，避免出现"复核通过"却不清楚是哪一层
        const lvName = e.currentLevelName || '复核';
        acts.push(`<button class="btn btn-sm" data-act="approve">${lvName}通过</button>`);
        acts.push(`<button class="btn btn-danger btn-sm" data-act="reject">${lvName}驳回</button>`);
    }

    Drawer.open({
        title: Fmt.esc(e.evidenceName),
        sub: `${Fmt.esc(e.evidenceNo || '')} · ${Fmt.esc(e.projectName || '')} / ${Fmt.esc(e.issueTitle || '')}`,
        body: `
            <div class="flex" style="gap:8px;margin-bottom:16px;flex-wrap:wrap">
                ${tag('evidenceStatus', e.status, e.statusName)}
                ${tag('evidenceType', e.evidenceType, e.evidenceTypeName)}
                ${reliabilityBadge(e.reliabilityGrade, e.reliabilityName)}
                ${e.canReview ? '<span class="tag tag-info plain">可复核</span>' : ''}
            </div>

            ${e.reviewComment ? `<div class="notice ${e.status === 3 ? 'warn' : ''}">
                <span class="nk">复核意见</span><span>${Fmt.esc(e.reviewComment)}</span></div>` : ''}

            ${reviewProgress(e, flows)}

            <div class="section-label">可靠性评定依据</div>
            <div class="rel-box" style="margin-top:0">
                <div class="rel-hd">
                    <span class="rel-title">按准则 1301 号第三条五原则计算</span>
                    ${reliabilityBadge(e.reliabilityGrade, e.reliabilityName)}
                </div>
                <div class="kv-grid" style="margin-top:10px">
                    <div class="kv"><div class="k">来源</div><div class="v">${Fmt.esc(e.sourceTypeName || '-')}</div></div>
                    <div class="kv"><div class="k">获取方式</div><div class="v">${Fmt.esc(e.acquireMethodName || '-')}</div></div>
                    <div class="kv"><div class="k">存在形式</div><div class="v">${Fmt.esc(e.formTypeName || '-')}</div></div>
                    <div class="kv"><div class="k">原件性</div><div class="v">${Fmt.esc(e.originalityName || '-')}</div></div>
                    ${e.sourceType === 'INTERNAL' ? `<div class="kv"><div class="k">内部控制</div>
                        <div class="v">${e.controlEffective === false
                            ? '<span style="color:var(--warn)">薄弱</span>' : '有效'}</div></div>` : ''}
                </div>
                <div class="rel-basis">${Fmt.esc(e.reliabilityBasis || '暂无判定依据')}</div>
                ${e.requiresSupplementary ? `<div class="notice warn" style="margin-top:10px;margin-bottom:0">
                    <span class="nk">注意</span>
                    <span>该证据证明力有限，提交复核前需在备注中说明补充佐证方案。</span></div>` : ''}
            </div>

            <div class="kv-grid">
                <div class="kv"><div class="k">来源描述</div><div class="v">${Fmt.esc(e.source || '-')}</div></div>
                <div class="kv"><div class="k">附件</div>
                    <div class="v">            ${e.filePath
            ? `<a href="/api/files${Fmt.esc(e.filePath)}" target="_blank">查看附件</a> <span class="txt-3 mono">${Fmt.size(e.fileSize)}</span>`
            : '<span class="txt-3">未上传</span>'}</div></div>
                <div class="kv"><div class="k">编制人</div><div class="v">${Fmt.esc(e.submitByName || '-')}</div></div>
                <div class="kv"><div class="k">提交时间</div><div class="v mono">${Fmt.dt(e.submitTime)}</div></div>
                <div class="kv"><div class="k">复核人</div><div class="v">${Fmt.esc(e.reviewByName || '-')}</div></div>
                <div class="kv"><div class="k">复核时间</div><div class="v mono">${Fmt.dt(e.reviewTime)}</div></div>
                <div class="kv full"><div class="k">备注</div>
                    <div class="v" style="color:var(--tx-2);line-height:1.7">${Fmt.esc(e.remark || '无')}</div></div>
            </div>

            <div class="section-label">流转轨迹 <span style="letter-spacing:0">${flows.length} 条</span></div>
            ${flows.length ? `<div class="timeline">${flows.map(f => `
                <div class="tl-item ${tlIcon(f.action)}">
                    <div class="tl-top">
                        <span class="tl-act">${Fmt.esc(f.actionName || f.action)}</span>
                        <span class="tag tag-ghost plain">${Fmt.esc(f.fromStatusName || '-')} → ${Fmt.esc(f.toStatusName || '-')}</span>
                        <span class="tl-time">${Fmt.dt(f.createTime)}</span>
                    </div>
                    <div class="tl-meta">${Fmt.esc(f.operatorName || '-')}
                        ${f.reviewLevelName ? `<span class="tag tag-ghost plain" style="margin-left:6px">${Fmt.esc(f.reviewLevelName)}</span>` : ''}</div>
                    ${f.comment ? `<div class="tl-cmt ${(f.action || '').startsWith('REJECT') ? 'bad' : ''}">${Fmt.esc(f.comment)}</div>` : ''}
                </div>`).join('')}</div>`
            : `<div class="txt-3" style="font-size:12px">暂无流转记录</div>`}`,
        footer: acts.length ? acts.join('') : `<button class="btn" data-d-close>关闭</button>`,
        onMount(panel, close) {
            panel.querySelectorAll('[data-act]').forEach(b => {
                b.onclick = async () => {
                    const act = b.dataset.act;
                    try {
                        if (act === 'submit') {
                            await API.evidenceSubmit(e.id);
                            Toast.ok('已提交复核');
                        } else if (act === 'approve') {
                            await EvidencePage.reviewThen(e, 'approve');
                        } else if (act === 'reject') {
                            await EvidencePage.reviewThen(e, 'reject');
                        }
                        close();
                        EvidencePage.load(App.ctx.issueId || undefined);
                    } catch (err) {
                        Toast.error(err.message);
                    }
                };
            });
        }
    });
};

/* 复核（通过/驳回）：都要填意见，驳回时必填由后端强校验，前端也做一次提示 */
EvidencePage.reviewThen = function (e, type) {
    return new Promise((resolve, reject) => {
        const isReject = type === 'reject';
        const lvName = e.currentLevelName || '复核';
        const isLast = e.currentLevel === 3;   // 第三级通过后证据才生效

        // 提示语必须区分"通过"和"走完"：一级通过只是往上送，不是结论成立。
        // 如果这里统一说"通过后状态变为已确认"，使用者会以为二级三级形同虚设。
        let tip;
        if (isReject) {
            tip = '驳回后该证据退回"已驳回"，编制人可修改后重新提交，需从第一级重新走起。';
        } else if (isLast) {
            tip = '这是<b>最后一级</b>复核。通过后三级复核全部完成，证据状态变为"已确认"，不可再修改。';
        } else {
            tip = `通过后证据将流转至下一级复核（当前为<b>${Fmt.esc(lvName)}</b>），尚不生效。`;
        }

        Modal.open({
            title: isReject ? `${lvName}驳回` : `${lvName}通过`,
            body: `
                <div class="notice ${isReject ? 'warn' : ''}">
                    <span class="nk">证据</span>
                    <span>${Fmt.esc(e.evidenceName)}<br>${tip}</span>
                </div>
                <div class="field mb-16">
                    <label>复核意见${isReject ? '<span class="req">*</span>' : ''}</label>
                    <textarea id="m-cmt" placeholder="${isReject ? '请说明驳回理由，将推送给编制人' : '可选：说明复核依据与结论'}"></textarea>
                </div>`,
            footer: `<button class="btn" data-dismiss>取消</button>
                     <button class="btn ${isReject ? 'btn-danger' : 'btn-primary'}" id="m-ok">确认${isReject ? '驳回' : '通过'}</button>`,
            onMount(el, close) {
                el.querySelector('#m-ok').onclick = async () => {
                    const cmt = el.querySelector('#m-cmt').value.trim();
                    if (isReject && !cmt) return Toast.error('驳回必须填写复核意见');
                    const btn = el.querySelector('#m-ok');
                    btn.disabled = true;
                    try {
                        if (isReject) {
                            await API.evidenceReject(e.id, cmt);
                            Toast.ok('已驳回至编制人');
                        } else {
                            // 后端返回流转后的去向描述，直接展示比写死"复核通过"准确
                            const next = await API.evidenceApprove(e.id, cmt);
                            Toast.ok(typeof next === 'string' && next ? next : '复核通过');
                        }
                        close();
                        Drawer.close();
                        resolve();
                    } catch (err) {
                        Toast.error(err.message);
                        btn.disabled = false;
                        reject(err);
                    }
                };
            }
        });
    });
};

/* ---- 证据新增 / 编辑 ---- */
EvidencePage.edit = async function (e) {
    let issues = [];
    try {
        const projects = await API.projectProgress(50) || [];
        const all = await Promise.all(projects.map(p => API.issueList(p.id).catch(() => [])));
        issues = all.flat();
    } catch (err) { /* 忽略 */ }

    const it = e || {reliability: 2, evidenceType: 'DOCUMENT'};
    let filePath = it.filePath || '';
    let fileSize = it.fileSize || null;

    Modal.open({
        title: e ? '编辑证据' : '采集审计证据',
        wide: true,
        body: `
            <div class="notice"><span class="nk">提示</span>
                <span>证据需先选择所属审计事项，并上传附件后才能提交复核——空壳证据进不了复核队列。</span></div>
            <div class="field"><label>所属审计事项<span class="req">*</span></label>
                <select data-field="issueId">
                    <option value="">请选择事项</option>
                    ${issues.map(i => `<option value="${i.id}" ${String(it.issueId) === String(i.id) ? 'selected' : ''}>
                        ${Fmt.esc(i.projectName || '')} / ${Fmt.esc(i.issueTitle)}</option>`).join('')}
                </select></div>
            <div class="field"><label>证据名称<span class="req">*</span></label>
                <input type="text" data-field="evidenceName" value="${Fmt.esc(it.evidenceName || '')}"
                       placeholder="如：2025年12月银行对账单"></div>
            <div class="form-row">
                <div class="field"><label>证据类型<span class="req">*</span></label>
                    <select data-field="evidenceType">${optionsFrom(DICT.evidenceType, it.evidenceType)}</select></div>
                <div class="field"><label>证据来源方描述</label>
                    <input type="text" data-field="source" value="${Fmt.esc(it.source || '')}"
                           placeholder="如：仓储部提供 / XX银行回函"></div>
            </div>

            <div class="rel-box">
                <div class="rel-hd">
                    <span class="rel-title">证据可靠性评定</span>
                    <span class="rel-grade" id="rel-grade">—</span>
                </div>
                <div class="rel-hint">依据《审计准则第1301号——审计证据》第三条五原则，由系统计算，无需手工选择</div>

                <div class="form-row">
                    <div class="field"><label>来源<span class="req">*</span></label>
                        <select data-field="sourceType" id="rel-src">
                            <option value="EXTERNAL" ${it.sourceType === 'EXTERNAL' ? 'selected' : ''}>外部独立来源（函证、第三方报告）</option>
                            <option value="INTERNAL" ${it.sourceType === 'INTERNAL' ? 'selected' : ''}>被审计单位内部生成</option>
                            <option value="MANAGEMENT" ${it.sourceType === 'MANAGEMENT' ? 'selected' : ''}>管理层声明/口头答复</option>
                        </select></div>
                    <div class="field"><label>原件性<span class="req">*</span></label>
                        <select data-field="originality" id="rel-ori">
                            <option value="ORIGINAL" ${it.originality === 'ORIGINAL' ? 'selected' : ''}>原件</option>
                            <option value="SCANNED" ${it.originality === 'SCANNED' ? 'selected' : ''}>扫描件（原件数字化）</option>
                            <option value="COPY" ${it.originality === 'COPY' ? 'selected' : ''}>复印件/传真件/照片</option>
                        </select></div>
                </div>
                <div class="form-row">
                    <div class="field"><label>获取方式<span class="req">*</span></label>
                        <select data-field="acquireMethod" id="rel-acq">
                            <option value="DIRECT" ${it.acquireMethod === 'DIRECT' ? 'selected' : ''}>审计人员直接获取</option>
                            <option value="INDIRECT" ${it.acquireMethod === 'INDIRECT' ? 'selected' : ''}>被审计单位提供</option>
                            <option value="INFERRED" ${it.acquireMethod === 'INFERRED' ? 'selected' : ''}>推算/推论得出</option>
                        </select></div>
                    <div class="field"><label>存在形式<span class="req">*</span></label>
                        <select data-field="formType" id="rel-form">
                            <option value="WRITTEN" ${it.formType === 'WRITTEN' ? 'selected' : ''}>书面文件</option>
                            <option value="ELECTRONIC" ${it.formType === 'ELECTRONIC' ? 'selected' : ''}>电子记录</option>
                            <option value="ORAL" ${it.formType === 'ORAL' ? 'selected' : ''}>口头说明</option>
                        </select></div>
                </div>
                <div class="field" id="rel-ctl-wrap">
                    <label>内部控制是否有效</label>
                    <select data-field="controlEffective" id="rel-ctl" data-type="boolean">
                        <option value="true" ${it.controlEffective !== false ? 'selected' : ''}>有效（仅对内部来源生效）</option>
                        <option value="false" ${it.controlEffective === false ? 'selected' : ''}>薄弱</option>
                    </select>
                </div>
                <div class="rel-basis" id="rel-basis"></div>
            </div>
            <div class="field"><label>附件</label>
                <div class="upload-box" id="up-box">
                    <div class="t">点击选择文件上传</div>
                    <div class="h">支持 pdf / doc / xls / 图片 / 压缩包，单文件 ≤ 20MB</div>
                </div>
                <input type="file" id="up-input" style="display:none">
                <div id="up-file"></div>
            </div>
            <div class="field mb-16"><label>备注</label>
                <textarea data-field="remark" placeholder="证据的取得过程、证明事项等">${Fmt.esc(it.remark || '')}</textarea></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="m-save">保存</button>`,
        onMount(el, close) {
            const box = el.querySelector('#up-box');
            const input = el.querySelector('#up-input');
            const host = el.querySelector('#up-file');

            /* ---- 可靠性实时预估 ----
             * 前端复刻后端的加权规则，只为让用户边填边看到结果。
             * 真正的判定以后端为准 —— 前端算错顶多显示不准，不能作为入库依据。
             * 这种做法叫「前后端同算一遍」：前端算是为了体验，后端算才是安全边界。
             */
            const W = {SRC: 3.0, ORI: 2.0, ACQ: 1.0, FORM: 1.0, PENALTY: 4.0};
            const SCORE = {
                EXTERNAL: 3, INTERNAL: 2, MANAGEMENT: 1,
                ORIGINAL: 3, SCANNED: 2, COPY: 1,
                DIRECT: 3, INDIRECT: 2, INFERRED: 1,
                WRITTEN: 3, ELECTRONIC: 2, ORAL: 1
            };
            const GRADE_STYLE = {
                A: {text: 'A 级 · 很高', cls: 'ga'},
                B: {text: 'B 级 · 高', cls: 'gb'},
                C: {text: 'C 级 · 中（需补充佐证）', cls: 'gc'},
                D: {text: 'D 级 · 低', cls: 'gd'}
            };

            const refreshReliability = () => {
                const src = el.querySelector('#rel-src').value;
                const ori = el.querySelector('#rel-ori').value;
                const acq = el.querySelector('#rel-acq').value;
                const form = el.querySelector('#rel-form').value;
                const ctl = el.querySelector('#rel-ctl').value === 'true';

                let score = SCORE[src] * W.SRC + SCORE[ori] * W.ORI
                          + SCORE[acq] * W.ACQ + SCORE[form] * W.FORM;
                const penalized = src === 'INTERNAL' && !ctl;
                if (penalized) score -= W.PENALTY;
                score = Math.max(0, score);

                // 分档规则与后端 EvidenceReliabilityEvaluator 保持一致（第三版）
                // 关键：A/B 两级看性质门槛，不看分数，避免分数漂移把弱证据顶上去
                let grade;
                if (src === 'EXTERNAL' && ori !== 'COPY') grade = 'A';
                else if (ori === 'ORIGINAL' && src !== 'MANAGEMENT' && score >= 15) grade = 'B';
                else if (score >= 8) grade = 'C';
                else grade = 'D';

                const g = GRADE_STYLE[grade];
                const badge = el.querySelector('#rel-grade');
                badge.textContent = g.text;
                badge.className = 'rel-grade ' + g.cls;

                // 控制有效性只对内部来源有意义，外部来源时灰掉，避免误解
                el.querySelector('#rel-ctl-wrap').style.opacity = src === 'INTERNAL' ? '1' : '.45';

                let basis = `得分 ${score.toFixed(1)}/21`;
                if (penalized) basis += '，因内部控制薄弱按第三条（二）扣 4 分';
                if (grade === 'C' || grade === 'D') basis += '。提交复核前须在备注中说明补充佐证方案';
                el.querySelector('#rel-basis').textContent = basis;
            };

            ['#rel-src', '#rel-ori', '#rel-acq', '#rel-form', '#rel-ctl'].forEach(sel => {
                el.querySelector(sel).addEventListener('change', refreshReliability);
            });
            refreshReliability();

            const showFile = () => {
                if (!filePath) {
                    host.innerHTML = '';
                    return;
                }
                host.innerHTML = `<div class="file-chip">
                    <span class="nm">${Fmt.esc(filePath.split('/').pop())}</span>
                    <span class="sz">${Fmt.size(fileSize)}</span>
                    <button class="btn-link danger" id="m-rmfile">移除</button></div>`;
                host.querySelector('#m-rmfile').onclick = () => {
                    filePath = '';
                    fileSize = null;
                    showFile();
                };
            };
            showFile();

            box.onclick = () => input.click();
            input.onchange = async () => {
                const f = input.files[0];
                if (!f) return;
                box.innerHTML = `<div class="t"><span class="spin"></span>上传中…</div>`;
                try {
                    const res = await API.upload(f);
                    filePath = res.filePath;
                    fileSize = res.size || f.size;
                    box.innerHTML = `<div class="t">点击重新选择文件</div>
                        <div class="h">支持 pdf / doc / xls / 图片 / 压缩包</div>`;
                    showFile();
                    Toast.ok('上传成功');
                } catch (err) {
                    box.innerHTML = `<div class="t">点击选择文件上传</div><div class="h">上传失败，请重试</div>`;
                    Toast.error(err.message);
                }
                input.value = '';
            };

            el.querySelector('#m-save').onclick = async () => {
                const body = collectForm(el);
                body.filePath = filePath || null;
                body.fileSize = fileSize || null;
                if (e) body.id = e.id;
                if (!body.issueId || !body.evidenceName) return Toast.error('事项与证据名称为必填');

                const btn = el.querySelector('#m-save');
                btn.disabled = true;
                try {
                    if (e) {
                        await API.evidenceUpdate(body);
                        Toast.ok('已保存');
                    } else {
                        await API.evidenceSave(body);
                        Toast.ok('已创建为草稿，记得提交复核');
                    }
                    close();
                    EvidencePage.load(App.ctx.issueId || undefined);
                } catch (err) {
                    Toast.error(err.message);
                    btn.disabled = false;
                }
            };
        }
    });
};

/* ==========================================================================
   十、复核待办
   ========================================================================== */
ROUTES.review = async function () {
    setCrumb([{text: '复核与留痕'}, {text: '复核待办'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">复核待办</h1>
                <p class="page-desc">等待复核的证据队列${App.isAdmin() ? '' : ' · 只有审计主管可以操作复核'}</p>
            </div>
            <button class="btn btn-sm" id="btn-refresh">${Icon.refresh} 刷新</button>
        </div>
        ${App.isAdmin() ? '' : `<div class="notice warn"><span class="nk">权限说明</span>
            <span>当前账号为「${Fmt.esc(App.user.roleName)}」，只能查看列表。复核操作需审计主管账号。</span></div>`}
        <div class="card">
            <div id="list-host">${loadingBox()}</div>
            <div id="pager-host"></div>
        </div>`;

    content().querySelector('#btn-refresh').onclick = () => go('review');

    const host = document.getElementById('list-host');
    let res;
    try {
        res = await API.evidencePage({pageNum: 1, pageSize: 50, status: 1});
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    const rows = res.records || [];
    if (!rows.length) {
        host.innerHTML = emptyBox('复核队列已清空', '当前没有待复核的证据');
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr><th style="width:26%">证据</th><th>所属项目</th><th>类型</th>
            <th>编制人</th><th>提交时间</th><th style="width:130px">操作</th></tr></thead>
        <tbody>${rows.map(e => `
            <tr>
                <td class="primary">${Fmt.esc(e.evidenceName)}
                    <div class="sub mono">${Fmt.esc(e.evidenceNo || '')}</div></td>
                <td>${Fmt.esc(e.projectName || '-')}<div class="sub">${Fmt.esc(e.issueTitle || '')}</div></td>
                <td>${tag('evidenceType', e.evidenceType, e.evidenceTypeName)}</td>
                <td>${Fmt.esc(e.submitByName || '-')}</td>
                <td class="mono">${Fmt.dt(e.submitTime)}</td>
                <td><div class="cell-actions">
                    <button class="btn-link" data-detail="${e.id}">查看</button>
                    ${e.canReview ? `<button class="btn-link" data-review="${e.id}">复核</button>` : ''}
                </div></td>
            </tr>`).join('')}</tbody></table></div>`;

    host.querySelectorAll('[data-detail]').forEach(b => {
        b.onclick = () => EvidencePage.detail(b.dataset.detail);
    });
    host.querySelectorAll('[data-review]').forEach(b => {
        const item = rows.find(x => String(x.id) === b.dataset.review);
        b.onclick = () => EvidencePage.detail(item.id);
    });
};

/* ==========================================================================
   十一、操作日志
   ========================================================================== */
ROUTES.logs = async function () {
    if (!App.isAdmin()) {
        return go('dashboard');
    }
    setCrumb([{text: '复核与留痕'}, {text: '操作日志'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">操作日志</h1>
                <p class="page-desc">由 AOP 切面自动记录，业务代码零侵入</p>
            </div>
            <button class="btn btn-sm" id="btn-refresh">${Icon.refresh} 刷新</button>
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="search-box">${Icon.search}<input type="text" id="f-log-kw" placeholder="搜索操作内容"></div>
                <div class="filter-inline">
                    <select id="f-log-module">
                        <option value="">全部模块</option>
                        <option>审计项目</option><option>审计事项</option><option>审计证据</option>
                    </select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-search">${Icon.search} 查询</button>
            </div>
            <div id="list-host">${loadingBox()}</div>
            <div id="pager-host"></div>
        </div>`;

    content().querySelector('#btn-refresh').onclick = () => go('logs');
    content().querySelector('#btn-search').onclick = () => LogPage.load();
    content().querySelector('#f-log-kw').onkeydown = (e) => {
        if (e.key === 'Enter') LogPage.load();
    };
    LogPage.page = {pageNum: 1, pageSize: 15};
    LogPage.load();
};

const LogPage = {page: {pageNum: 1, pageSize: 15}};

LogPage.load = async function () {
    const host = document.getElementById('list-host');
    host.innerHTML = loadingBox();

    let res;
    try {
        res = await API.logPage({
            pageNum: LogPage.page.pageNum,
            pageSize: LogPage.page.pageSize,
            keyword: document.getElementById('f-log-kw').value.trim(),
            module: document.getElementById('f-log-module').value
        });
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    const rows = res.records || [];
    if (!rows.length) {
        host.innerHTML = emptyBox('暂无操作日志');
        document.getElementById('pager-host').innerHTML = '';
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr><th style="width:110px">时间</th><th>模块 / 操作</th><th>操作人</th>
            <th>请求</th><th>IP</th><th>耗时</th><th>结果</th></tr></thead>
        <tbody>${rows.map(l => `
            <tr>
                <td class="mono">${Fmt.dt(l.createTime)}</td>
                <td class="primary">${Fmt.esc(l.module || '-')}
                    <div class="sub">${Fmt.esc(l.operation || '')}</div></td>
                <td>${Fmt.esc(l.operatorName || '-')}</td>
                <td class="mono" style="font-size:11px;color:var(--tx-3)">${Fmt.esc(l.requestMethod || '')} ${Fmt.esc(l.requestUri || '')}</td>
                <td class="mono" style="font-size:11px">${Fmt.esc(l.ip || '-')}</td>
                <td class="mono">${l.costMs != null ? l.costMs + ' ms' : '-'}</td>
                <td>${Number(l.success) === 1
            ? '<span class="tag tag-green">成功</span>'
            : `<span class="tag tag-danger" title="${Fmt.esc(l.errorMsg || '')}">失败</span>`}</td>
            </tr>`).join('')}</tbody></table></div>`;

    const pg = pager(res, (n) => {
        LogPage.page.pageNum = n;
        LogPage.load();
    });
    const ph = document.getElementById('pager-host');
    ph.innerHTML = pg.html;
    pg.bind(ph);
};

/* ==========================================================================
   十二、审计抽样
   准则 1314 号的控制测试抽样：参数 → 样本量 → 样本明细 → 偏差率上限 → 结论。
   页面刻意把三种口径（统计抽样 / 判断抽样 / 全查）在视觉上区分开，
   因为它们的结论文本不能互换 —— 这是本模块和普通"抽样计算器"的差别。
   ========================================================================== */
const SamplingPage = {
    projects: [],
    projectId: null
};

SamplingPage.pct = function (v) {
    if (v === null || v === undefined || v === '') return '—';
    return (Number(v) * 100).toFixed(2) + '%';
};

SamplingPage.methodTag = function (s) {
    if (s.isCensus) {
        return '<span class="tag tag-info">全查</span>';
    }
    return tag('samplingMethod', s.method)
        + ' <span class="tag tag-ghost plain">' + Fmt.esc(s.confidenceDesc || '') + '</span>';
};

/**
 * 结论卡。
 *
 * 未出结论时显示进度和"还差几项"，不显示任何推断数字 ——
 * 样本没抽满就给上限，是伪造统计基础；出结论后直接把后端写好的
 * conclusionText 原样展示，保证页面措辞和底稿口径完全一致。
 */
SamplingPage.conclusionCard = function (s) {
    const c = Number(s.conclusion);
    const checked = Number(s.checkedCount || 0);
    const dev = Number(s.deviationCount || 0);
    const allowed = Number(s.allowedDeviations || 0);

    if (c === 0) {
        const p = s.sampleSize ? (checked / s.sampleSize) * 100 : 0;
        return `
        <div class="samp-concl wait">
            <div class="samp-concl-hd">
                <span class="samp-badge">进行中</span>
                <b>尚未出具结论</b>
                <span class="grow"></span>
                <span class="mono txt-3">${checked} / ${s.sampleSize}</span>
            </div>
            <div class="samp-concl-txt">
                已检查 <b>${checked}</b> 项，发现偏差 <b>${dev}</b> 项（允许 ${allowed} 项）。
                ${s.remaining > 0
            ? `还需检查 <b>${s.remaining}</b> 项。`
            : '样本已按计划检查完成。'}
                抽满后系统自动按${s.isCensus ? '全部检查' : (Fmt.esc(s.methodDesc || '抽样'))}口径出具结论。
            </div>
            <div style="margin-top:12px">${bar(p)}</div>
        </div>`;
    }

    const ok = c === 1;
    return `
        <div class="samp-concl ${ok ? 'ok' : 'bad'}">
            <div class="samp-concl-hd">
                <span class="samp-badge">抽样结论</span>
                <b>${Fmt.esc(s.conclusionDesc || (ok ? '控制可信赖' : '控制不可信赖'))}</b>
                <span class="grow"></span>
                <span class="mono">偏差率 <b>${SamplingPage.pct(s.deviationRate)}</b></span>
            </div>
            <div class="samp-concl-txt">${Fmt.esc(s.conclusionText || '')}</div>
            <div class="samp-concl-foot">
                偏差率上限 <b>${SamplingPage.pct(s.upperLimit)}</b>
                · 可容忍偏差率 ${SamplingPage.pct(s.tolerableRate)}
                · 已检查 ${checked} 项 / 偏差 ${dev} 项
                ${s.upperLimit == null ? ' · 本口径不推断上限' : ''}
            </div>
        </div>`;
};

/* ---------- 抽样计划列表 ---------- */
ROUTES.sampling = async function () {
    setCrumb([{text: '审计执行'}, {text: '审计抽样'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">审计抽样</h1>
                <p class="page-desc">控制测试抽样：按二项分布确定样本量，用偏差率上限判断控制是否可信赖</p>
            </div>
            ${App.canWrite() ? `<button class="btn btn-primary" id="btn-new-samp">${Icon.plus} 新建抽样计划</button>` : ''}
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="filter-inline">
                    <select id="f-samp-project" style="min-width:280px"></select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-samp-refresh">${Icon.refresh} 刷新</button>
            </div>
            <div id="samp-list">${loadingBox()}</div>
        </div>`;

    let res;
    try {
        res = await API.projectPage({pageNum: 1, pageSize: 100});
    } catch (e) {
        document.getElementById('samp-list').innerHTML = emptyBox('项目加载失败', e.message);
        return;
    }

    SamplingPage.projects = (res && res.records) || [];
    const sel = document.getElementById('f-samp-project');
    const host = document.getElementById('samp-list');

    if (!SamplingPage.projects.length) {
        sel.innerHTML = '<option value="">暂无项目</option>';
        host.innerHTML = emptyBox('还没有审计项目', '抽样计划必须挂在项目下，先到「审计项目」新建一个');
        return;
    }

    // 优先用路由上下文带来的项目（从项目详情跳进来时），否则沿用上次选择
    const prefer = App.ctx.projectId || SamplingPage.projectId;
    const cur = SamplingPage.projects.some(p => String(p.id) === String(prefer))
        ? String(prefer) : String(SamplingPage.projects[0].id);

    sel.innerHTML = SamplingPage.projects.map(p =>
        `<option value="${p.id}" ${String(p.id) === cur ? 'selected' : ''}>${Fmt.esc(p.projectName)}</option>`).join('');
    SamplingPage.projectId = Number(cur);

    sel.onchange = () => {
        SamplingPage.projectId = Number(sel.value);
        SamplingPage.load();
    };
    document.getElementById('btn-samp-refresh').onclick = () => SamplingPage.load();

    const addBtn = document.getElementById('btn-new-samp');
    if (addBtn) addBtn.onclick = () => SamplingPage.create();

    SamplingPage.load();
};

SamplingPage.load = async function () {
    const host = document.getElementById('samp-list');
    if (!host) return;
    host.innerHTML = loadingBox();

    let rows;
    try {
        rows = await API.samplingList(SamplingPage.projectId) || [];
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    if (!rows.length) {
        host.innerHTML = emptyBox('该项目还没有抽样计划',
            '点右上角「新建抽样计划」，填总体规模与可容忍偏差率，系统会算出应抽多少项');
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr>
            <th style="width:26%">控制 / 计划编号</th>
            <th style="width:130px">抽样口径</th>
            <th style="width:110px">已检查 / 应抽</th>
            <th style="width:100px">偏差 / 允许</th>
            <th style="width:120px">偏差率上限</th>
            <th style="width:110px">结论</th>
            <th style="width:100px">操作</th>
        </tr></thead>
        <tbody>${rows.map(s => `
            <tr>
                <td class="primary">
                    <div style="cursor:pointer" data-open="${s.id}">${Fmt.esc(s.controlName)}</div>
                    <div class="sub mono">${Fmt.esc(s.samplingNo)}</div>
                </td>
                <td class="nowrap">${SamplingPage.methodTag(s)}</td>
                <td class="mono">${Fmt.num(s.checkedCount)} / ${Fmt.num(s.sampleSize)}</td>
                <td class="mono">${Fmt.num(s.deviationCount)} / ${Fmt.num(s.allowedDeviations)}</td>
                <td class="mono">${s.upperLimit == null
            ? '<span class="txt-3">—</span>'
            : SamplingPage.pct(s.upperLimit)}</td>
                <td>${tag('samplingConclusion', s.conclusion)}</td>
                <td><div class="cell-actions">
                    <button class="btn-link" data-open="${s.id}">详情</button>
                    ${App.canWrite() && Number(s.conclusion) === 0
            ? `<button class="btn-link danger" data-del="${s.id}">删除</button>` : ''}
                </div></td>
            </tr>`).join('')}
        </tbody></table></div>`;

    host.querySelectorAll('[data-open]').forEach(b => {
        b.onclick = () => go('sampling-detail', {samplingId: b.dataset.open});
    });
    host.querySelectorAll('[data-del]').forEach(b => {
        b.onclick = () => SamplingPage.remove(b.dataset.del, () => SamplingPage.load());
    });
};

/* ---------- 新建抽样计划 ---------- */
SamplingPage.create = function () {
    if (!App.canWrite()) return Toast.error('当前账号为只读账号，不能创建抽样计划');
    if (!SamplingPage.projects.length) return Toast.error('请先创建审计项目');

    const pid = SamplingPage.projectId || SamplingPage.projects[0].id;
    const projOptions = SamplingPage.projects.map(p =>
        `<option value="${p.id}" ${String(p.id) === String(pid) ? 'selected' : ''}>${Fmt.esc(p.projectName)}</option>`).join('');

    Modal.open({
        title: '新建抽样计划',
        wide: true,
        body: `
            <div class="notice"><span class="nk">比率填百分比</span><span>
                可容忍偏差率、预期偏差率都按 <b>%</b> 填写（例如 5 表示 5%）。
                接口内部要求小数 0.05，这个换算由页面完成，不用手工折算。</span></div>

            <div class="form-row">
                <div class="field"><label>所属项目<span class="req">*</span></label>
                    <select id="np-project">${projOptions}</select></div>
                <div class="field"><label>关联审计事项</label>
                    <select id="np-issue"><option value="">不关联</option></select></div>
            </div>
            <div class="field"><label>被测试的控制<span class="req">*</span></label>
                <input type="text" id="np-control" placeholder="如：采购付款审批 / 销售发货授权"></div>

            <div class="form-row">
                <div class="field"><label>总体规模（笔）<span class="req">*</span></label>
                    <input type="number" id="np-pop" min="1" placeholder="本期该控制涉及的全部笔数"></div>
                <div class="field"><label>置信水平</label>
                    <select id="np-conf">
                        <option value="NINETY_FIVE">95%（风险 5%）</option>
                        <option value="NINETY">90%（风险 10%）</option>
                    </select></div>
            </div>

            <div class="form-row">
                <div class="field"><label>可容忍偏差率（%）<span class="req">*</span></label>
                    <input type="number" id="np-tol" step="0.1" min="0.01" max="99.99" value="5"></div>
                <div class="field"><label>预期偏差率（%）</label>
                    <input type="number" id="np-exp" step="0.1" min="0" max="99.99" value="0"></div>
            </div>

            <div class="field"><label>抽样方法</label>
                <select id="np-method">
                    <option value="STATISTICAL">统计抽样（结论可推断总体）</option>
                    <option value="JUDGMENTAL">判断抽样（结论不可外推）</option>
                </select></div>

            <div class="notice" id="np-hint"><span class="nk">口径提示</span>
                <span>统计抽样按二项分布计算样本量，结论在所选置信水平下可推断总体。</span></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="np-save">创建并计算样本量</button>`,
        onMount(el, close) {
            const issueSel = el.querySelector('#np-issue');
            const hint = el.querySelector('#np-hint');

            const loadIssues = async (projectId) => {
                issueSel.innerHTML = '<option value="">不关联</option>';
                if (!projectId) return;
                try {
                    const list = await API.issueList(projectId) || [];
                    issueSel.innerHTML = '<option value="">不关联</option>' + list.map(i =>
                        `<option value="${i.id}">${Fmt.esc(i.issueTitle)}</option>`).join('');
                } catch (e) {
                    /* 无权限或项目无事项时保持"不关联" */
                }
            };

            el.querySelector('#np-project').onchange = (e) => loadIssues(e.target.value);
            loadIssues(el.querySelector('#np-project').value);

            el.querySelector('#np-method').onchange = (e) => {
                const judgmental = e.target.value === 'JUDGMENTAL';
                hint.className = 'notice' + (judgmental ? ' warn' : '');
                hint.innerHTML = `<span class="nk">口径提示</span><span>${judgmental
                    ? '判断抽样没有概率抽样基础，结论不能推断总体，系统不会给出偏差率上限。'
                    : '统计抽样按二项分布计算样本量，结论在所选置信水平下可推断总体。'}</span>`;
            };

            el.querySelector('#np-save').onclick = async () => {
                const btn = el.querySelector('#np-save');
                const projectId = Number(el.querySelector('#np-project').value);
                const issueId = el.querySelector('#np-issue').value;
                const controlName = el.querySelector('#np-control').value.trim();
                const population = Number(el.querySelector('#np-pop').value);
                const tol = Number(el.querySelector('#np-tol').value);
                const expRaw = el.querySelector('#np-exp').value;
                const exp = expRaw === '' ? 0 : Number(expRaw);

                if (!controlName) return Toast.error('请填写被测试的控制名称');
                if (!population || population < 1) return Toast.error('总体规模必须大于 0');
                if (!(tol > 0 && tol < 100)) return Toast.error('可容忍偏差率需在 0 到 100 之间');
                if (!(exp >= 0 && exp < 100)) return Toast.error('预期偏差率需在 0 到 100 之间');
                if (exp >= tol) return Toast.error('预期偏差率应低于可容忍偏差率，否则无需抽样即可判定不可信赖');

                const body = {
                    projectId,
                    issueId: issueId || null,
                    controlName,
                    populationSize: population,
                    tolerableRate: tol / 100,
                    expectedRate: exp / 100,
                    confidenceLevel: el.querySelector('#np-conf').value,
                    method: el.querySelector('#np-method').value
                };

                btn.disabled = true;
                btn.textContent = '计算中…';
                try {
                    const vo = await API.samplingCreate(body);
                    close();
                    Toast.ok(`样本量 ${vo.sampleSize} 项，允许偏差 ${vo.allowedDeviations} 项`);
                    go('sampling-detail', {samplingId: vo.id});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                    btn.textContent = '创建并计算样本量';
                }
            };
        }
    });
};

/* ---------- 抽样计划详情 ---------- */
ROUTES['sampling-detail'] = async function () {
    const id = App.ctx.samplingId;
    content().innerHTML = loadingBox('加载抽样计划');

    let s, p;
    try {
        s = await API.samplingDetail(id);
    } catch (e) {
        content().innerHTML = emptyBox('抽样计划不存在或无权访问', e.message);
        return;
    }
    p = await API.projectDetail(s.projectId).catch(() => null);

    setCrumb([
        {text: '审计执行'},
        {text: '审计抽样', action: 'sampling'},
        {text: s.samplingNo}
    ]);

    const items = s.items || [];
    const recorded = items.length;
    const cap = Math.max(0, Number(s.sampleSize) - recorded);
    const pending = Number(s.conclusion) === 0;
    const canWrite = App.canWrite() && pending;
    const upperFoot = s.upperLimit == null
        ? (Number(s.conclusion) === 2 ? '提前终止，不推断上限'
            : Number(s.conclusion) === 0 ? '样本抽满后推断' : '本口径不推断上限')
        : '单侧置信上限';

    const itemRows = items.map(it => {
        const dv = it.deviation;
        const dvTag = dv === null || dv === undefined
            ? '<span class="tag tag-muted">未检查</span>'
            : (dv ? '<span class="tag tag-danger">偏差</span>' : '<span class="tag tag-green">正常</span>');

        let act = '';
        if (canWrite) {
            act = (dv === null || dv === undefined)
                ? `<button class="btn-link" data-mark="${it.id}" data-dev="false">正常</button>
                   <button class="btn-link danger" data-mark="${it.id}" data-dev="true">偏差</button>`
                : `<button class="btn-link" data-mark="${it.id}" data-dev="${dv ? 'false' : 'true'}">改为${dv ? '正常' : '偏差'}</button>
                   <button class="btn-link" data-mark="${it.id}" data-dev="">撤销</button>`;
        }

        return `<tr>
            <td class="mono" style="color:var(--tx-3)">${String(it.seqNo).padStart(2, '0')}</td>
            <td class="primary mono">${Fmt.esc(it.voucherNo)}</td>
            <td class="mono">${Fmt.d(it.voucherDate)}</td>
            <td class="mono" style="text-align:right">${it.amount == null ? '-' : '¥' + Fmt.num(it.amount)}</td>
            <td>${dvTag}</td>
            <td style="color:var(--tx-2)">${it.deviationDesc ? Fmt.esc(it.deviationDesc)
                : '<span class="txt-3">—</span>'}</td>
            <td class="mono">${it.checkedTime ? Fmt.dt(it.checkedTime) : '-'}</td>
            <td><div class="cell-actions">${act || '<span class="txt-3">—</span>'}</div></td>
        </tr>`;
    }).join('');

    content().innerHTML = `
        <div class="page-head">
            <div>
                <div class="flex" style="gap:10px;margin-bottom:7px">
                    <h1 class="page-title" style="margin:0">${Fmt.esc(s.controlName)}</h1>
                    ${tag('samplingConclusion', s.conclusion)}
                    ${SamplingPage.methodTag(s)}
                </div>
                <p class="page-desc"><span class="mono">${Fmt.esc(s.samplingNo)}</span>
                    · ${Fmt.esc(p ? p.projectName : ('项目 #' + s.projectId))}
                    · ${s.isCensus ? '总体较小，已退化为全查' : '计划样本量 ' + s.sampleSize + ' 项'}</p>
            </div>
            <div class="flex">
                <button class="btn btn-sm" data-back>${Icon.back} 返回</button>
                ${canWrite && cap > 0
        ? `<button class="btn btn-sm" id="btn-add-items">${Icon.plus} 录入样本</button>` : ''}
                ${canWrite && s.readyToConclude
        ? `<button class="btn btn-primary btn-sm" id="btn-conclude">出具结论</button>` : ''}
                ${canWrite ? `<button class="btn btn-sm btn-danger" id="btn-del-samp">删除</button>` : ''}
            </div>
        </div>

        <div class="metric-grid">
            <div class="metric accent">
                <div class="lb">${s.isCensus ? '总体规模' : '应抽样本'}</div>
                <div class="vl">${Fmt.num(s.sampleSize)}<span class="unit">项</span></div>
                <div class="foot">${s.isCensus ? '总体全部检查' : '总体 ' + Fmt.num(s.populationSize) + ' 项'}</div>
            </div>
            <div class="metric info">
                <div class="lb">已检查</div>
                <div class="vl">${Fmt.num(s.checkedCount)}<span class="unit">/ ${Fmt.num(s.sampleSize)}</span></div>
                <div class="foot">${s.remaining > 0 ? '还需检查 ' + s.remaining + ' 项' : '样本已检查完毕'}</div>
            </div>
            <div class="metric ${Number(s.deviationCount) > Number(s.allowedDeviations) ? 'danger' : ''}">
                <div class="lb">发现偏差</div>
                <div class="vl">${Fmt.num(s.deviationCount)}<span class="unit">/ 允许 ${Fmt.num(s.allowedDeviations)}</span></div>
                <div class="foot">超出允许值即不可信赖</div>
            </div>
            <div class="metric ${s.upperLimit == null ? '' : (Number(s.upperLimit) > Number(s.tolerableRate) ? 'danger' : 'accent')}">
                <div class="lb">偏差率上限</div>
                <div class="vl" style="font-size:21px;padding-top:3px">${SamplingPage.pct(s.upperLimit)}</div>
                <div class="foot">${upperFoot} · 可容忍 ${SamplingPage.pct(s.tolerableRate)}</div>
            </div>
        </div>

        ${SamplingPage.conclusionCard(s)}

        <div class="card">
            <div class="card-head">
                <div class="card-title">样本明细 <span class="cnt">${recorded} / ${Fmt.num(s.sampleSize)}</span></div>
                <div class="flex" style="gap:8px">
                    ${pending
        ? `<span class="txt-3" style="font-size:11.5px">还可录入 ${cap} 项</span>`
        : '<span class="tag tag-ghost plain">结论已出具，样本锁定</span>'}
                </div>
            </div>
            ${items.length
        ? `<div class="table-wrap"><table>
                    <thead><tr>
                        <th style="width:46px">序号</th><th style="width:15%">凭证号</th>
                        <th style="width:100px">凭证日期</th><th style="width:110px;text-align:right">金额</th>
                        <th style="width:90px">检查结果</th><th>偏差描述</th>
                        <th style="width:130px">检查时间</th><th style="width:130px">操作</th>
                    </tr></thead>
                    <tbody>${itemRows}</tbody>
                </table></div>`
        : emptyBox('还没有录入样本', pending ? '点右上角「录入样本」，逐条登记凭证号和检查结果' : '该计划没有样本记录')}
        </div>

        <div class="grid-2">
            <div class="card">
                <div class="card-head"><div class="card-title">抽样设计</div></div>
                <div class="card-body">
                    <div class="kv-grid">
                        <div class="kv"><div class="k">抽样方法</div>
                            <div class="v">${Fmt.esc(s.methodDesc || '-')}${s.isCensus ? '（退化为全查）' : ''}</div></div>
                        <div class="kv"><div class="k">置信水平</div>
                            <div class="v">${Fmt.esc(s.confidenceDesc || '-')}</div></div>
                        <div class="kv"><div class="k">总体规模</div>
                            <div class="v mono">${Fmt.num(s.populationSize)} 笔</div></div>
                        <div class="kv"><div class="k">应抽样本量</div>
                            <div class="v mono">${Fmt.num(s.sampleSize)} 项</div></div>
                        <div class="kv"><div class="k">可容忍偏差率</div>
                            <div class="v mono">${SamplingPage.pct(s.tolerableRate)}</div></div>
                        <div class="kv"><div class="k">预期偏差率</div>
                            <div class="v mono">${SamplingPage.pct(s.expectedRate)}</div></div>
                        <div class="kv"><div class="k">允许偏差数</div>
                            <div class="v mono">${Fmt.num(s.allowedDeviations)} 项</div></div>
                        <div class="kv"><div class="k">实际偏差率</div>
                            <div class="v mono">${SamplingPage.pct(s.deviationRate)}</div></div>
                        <div class="kv full"><div class="k">计算依据</div>
                            <div class="v" style="color:var(--tx-2);line-height:1.8;font-size:12px">
                                ${Fmt.esc(s.basis || '—')}</div></div>
                    </div>
                </div>
            </div>

            <div class="card">
                <div class="card-head"><div class="card-title">判定口径</div></div>
                <div class="card-body" style="font-size:12.5px;color:var(--tx-2);line-height:1.9">
                    <div class="section-label" style="margin-top:0">统计抽样</div>
                    按二项分布计算样本量，用 Clopper-Pearson 单侧置信上限推断总体偏差率。
                    上限低于可容忍偏差率 → 控制可信赖，结论可外推。

                    <div class="section-label">判断抽样</div>
                    没有概率抽样基础，<b>不给置信上限</b>。结论只能基于偏差数是否超过允许值
                    和审计人员职业判断，措辞上明确"不能推断总体"。

                    <div class="section-label">全查</div>
                    总体规模小于计划样本量时退化为全部检查，此时偏差率就是总体真实偏差率，
                    不存在"推断"，也不应出现"95% 置信水平"的表述。

                    <div class="samp-note">
                        停止-前进：偏差数一旦超过允许值，立即终止并判为不可信赖，
                        不再继续抽完剩余样本 —— 结论不会因为多抽几项而翻转。
                    </div>
                </div>
            </div>
        </div>`;

    const c = content();
    c.querySelector('[data-back]').onclick = () => go('sampling');

    const addBtn = c.querySelector('#btn-add-items');
    if (addBtn) addBtn.onclick = () => SamplingPage.addItems(s);

    const concludeBtn = c.querySelector('#btn-conclude');
    if (concludeBtn) {
        concludeBtn.onclick = () => Modal.confirm('出具抽样结论',
            `样本已检查 ${s.checkedCount} 项、发现偏差 ${s.deviationCount} 项。出具结论后样本将锁定，不能再修改。`,
            async () => {
                await API.samplingConclude(s.id);
                Toast.ok('结论已出具');
                go('sampling-detail', {samplingId: s.id});
            }, false);
    }

    const delBtn = c.querySelector('#btn-del-samp');
    if (delBtn) delBtn.onclick = () => SamplingPage.remove(s.id, () => go('sampling'));

    c.querySelectorAll('[data-mark]').forEach(b => {
        b.onclick = () => SamplingPage.mark(s.id, b.dataset.mark, b.dataset.dev);
    });
};

/* ---------- 单条样本判定 ---------- */
SamplingPage.mark = function (planId, itemId, dev) {
    const done = () => {
        Toast.ok('样本已更新');
        go('sampling-detail', {samplingId: planId});
    };
    const fail = (e) => Toast.error(e.message);

    // 撤销检查：deviation 传空，后端把 checkedTime 一并清空
    if (dev === '') {
        API.samplingMarkItem(itemId, null, null).then(done).catch(fail);
        return;
    }

    if (dev === 'false') {
        API.samplingMarkItem(itemId, false, null).then(done).catch(fail);
        return;
    }

    // 判定为偏差：描述是必填，没有描述就没有底稿证明力
    Modal.open({
        title: '判定为偏差',
        body: `<div class="notice warn"><span class="nk">必须写清偏差</span>
                   <span>偏差描述是复核时唯一能追溯的依据。写"不合规"不算描述，要写清缺了什么审批、金额差多少。</span></div>
               <div class="field mb-16"><label>偏差描述<span class="req">*</span></label>
                   <textarea id="mk-desc" placeholder="如：付款审批单缺少部门负责人签字"></textarea></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="mk-save">确认判定</button>`,
        onMount(el, close) {
            el.querySelector('#mk-save').onclick = async () => {
                const desc = el.querySelector('#mk-desc').value.trim();
                if (!desc) return Toast.error('请填写偏差描述');
                const btn = el.querySelector('#mk-save');
                btn.disabled = true;
                try {
                    await API.samplingMarkItem(itemId, true, desc);
                    close();
                    done();
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                }
            };
        }
    });
};

/* ---------- 批量录入样本 ---------- */
SamplingPage.addItems = function (s) {
    const recorded = (s.items || []).length;
    const cap = Math.max(0, Number(s.sampleSize) - recorded);
    if (cap <= 0) return Toast.info('样本已按计划录满');

    const rowHtml = () => `<tr>
        <td class="mono idx" style="color:var(--tx-3)"></td>
        <td><input type="text" data-k="voucherNo" placeholder="记-0012"></td>
        <td><input type="date" data-k="voucherDate"></td>
        <td><input type="number" data-k="amount" step="0.01" placeholder="0.00"></td>
        <td><select data-k="deviation">
            <option value="">未检查</option>
            <option value="false">无偏差</option>
            <option value="true">偏差</option>
        </select></td>
        <td><input type="text" data-k="deviationDesc" placeholder="判定为偏差时必填"></td>
        <td><button class="btn-link danger" data-del-row title="删除本行">×</button></td>
    </tr>`;

    Modal.open({
        title: '录入样本检查结果',
        xwide: true,
        body: `
            <div class="notice"><span class="nk">录入说明</span><span>
                应抽 <b>${s.sampleSize}</b> 项，已录入 <b>${recorded}</b> 项，本次最多录入 <b>${cap}</b> 项。
                检查结果留空表示"抽到了但还没看"，不计入已检查数。</span></div>
            <div class="entry-wrap">
                <table class="entry-table">
                    <thead><tr>
                        <th style="width:38px">#</th>
                        <th style="width:22%">凭证号</th>
                        <th style="width:135px">凭证日期</th>
                        <th style="width:115px">金额</th>
                        <th style="width:110px">检查结果</th>
                        <th>偏差描述</th>
                        <th style="width:34px"></th>
                    </tr></thead>
                    <tbody id="entry-body"></tbody>
                </table>
            </div>
            <button class="btn btn-sm" id="add-row" style="margin-top:10px">${Icon.plus} 添加一行</button>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="entry-save">提交录入</button>`,
        onMount(el, close) {
            const tbody = el.querySelector('#entry-body');

            const renumber = () => [...tbody.children].forEach((tr, i) => {
                tr.querySelector('.idx').textContent = String(i + 1).padStart(2, '0');
            });

            const bindRow = (tr) => {
                const dev = tr.querySelector('[data-k="deviation"]');
                const desc = tr.querySelector('[data-k="deviationDesc"]');
                dev.onchange = () => {
                    desc.classList.toggle('need', dev.value === 'true');
                    if (dev.value === 'true') desc.focus();
                };
            };

            const addRow = () => {
                tbody.insertAdjacentHTML('beforeend', rowHtml());
                bindRow(tbody.lastElementChild);
                renumber();
            };

            for (let i = 0; i < Math.min(3, cap); i++) addRow();

            el.querySelector('#add-row').onclick = () => {
                if (tbody.children.length >= cap) {
                    return Toast.info(`本次最多录入 ${cap} 项`);
                }
                addRow();
            };

            // 事件委托：删除行后重新编号，避免行号跳号
            tbody.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-del-row]');
                if (!btn) return;
                if (tbody.children.length <= 1) return Toast.info('至少保留一行');
                btn.closest('tr').remove();
                renumber();
            });

            el.querySelector('#entry-save').onclick = async () => {
                const list = [];
                let err = null;

                [...tbody.children].forEach((tr, i) => {
                    if (err) return;
                    const get = (k) => tr.querySelector(`[data-k="${k}"]`).value.trim();
                    const voucherNo = get('voucherNo');
                    const date = get('voucherDate');
                    const amountRaw = get('amount');
                    const devRaw = get('deviation');
                    const desc = get('deviationDesc');

                    // 整行空白视为用户没用上的预留行，直接跳过
                    if (!voucherNo && !date && !amountRaw && !devRaw && !desc) return;

                    if (!voucherNo) {
                        err = `第 ${i + 1} 行缺少凭证号`;
                        return;
                    }
                    if (devRaw === 'true' && !desc) {
                        err = `第 ${i + 1} 行判定为偏差，请填写偏差描述`;
                        return;
                    }
                    list.push({
                        voucherNo,
                        voucherDate: date || null,
                        amount: amountRaw === '' ? null : Number(amountRaw),
                        deviation: devRaw === '' ? null : devRaw === 'true',
                        deviationDesc: desc || null
                    });
                });

                if (err) return Toast.error(err);
                if (!list.length) return Toast.error('请至少填写一条样本');
                if (list.length > cap) return Toast.error(`本次最多录入 ${cap} 项`);

                const btn = el.querySelector('#entry-save');
                btn.disabled = true;
                btn.textContent = '提交中…';
                try {
                    await API.samplingAddItems(s.id, list);
                    close();
                    Toast.ok(`已录入 ${list.length} 项样本`);
                    go('sampling-detail', {samplingId: s.id});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                    btn.textContent = '提交录入';
                }
            };
        }
    });
};

/* ---------- 删除计划 ---------- */
SamplingPage.remove = function (id, after) {
    Modal.confirm('删除抽样计划', '确定删除该抽样计划？计划下的样本明细会一起逻辑删除，操作不可恢复。',
        async () => {
            await API.samplingDelete(id);
            Toast.ok('已删除');
            after && after();
        });
};

/* ==========================================================================
   十二之二、变量抽样（细节测试）
   ========================================================================== */
const VS = {
    projects: [],
    projectId: null
};

/** 金额标签；null 一律显示破折号，不要显示 ¥0.00 —— 0 和"还没检查"是两件事 */
VS.money = (v) => (v === null || v === undefined || v === '' ? '—' : Fmt.money(v));

VS.methodTag = function (s) {
    return tag('vsMethod', s.method)
        + ' <span class="tag tag-ghost plain">' + Fmt.esc(s.confidenceDesc || '') + '</span>';
};

/**
 * 结论卡。
 *
 * 未评价时只说进度，不给任何金额上限 —— 样本没抽满就报错报上限，
 * 等于拿 30 项的标准差去代表 3000 项总体的离散度，数字看着精确，其实是编的。
 */
VS.conclusionCard = function (s) {
    const c = Number(s.conclusion);
    const checked = Number(s.checkedCount || 0);

    if (c === 0) {
        const p = s.sampleSize ? (checked / s.sampleSize) * 100 : 0;
        return `
        <div class="samp-concl wait">
            <div class="samp-concl-hd">
                <span class="samp-badge">进行中</span>
                <b>${s.isPilot ? '试点样本阶段' : '尚未出具评价结论'}</b>
                <span class="grow"></span>
                <span class="mono txt-3">${checked} / ${s.sampleSize}</span>
            </div>
            <div class="samp-concl-txt">
                ${s.isPilot
            ? `未提供预估标准差，先抽 <b>${s.sampleSize}</b> 项试点。抽满后系统用实际标准差重算正式样本量，
                       不够会自动扩大 —— 30 项试点证明不了整个总体。`
            : `已检查 <b>${checked}</b> 项，已发现错报 <b>${Fmt.num(s.misstatementCount)}</b> 笔。
                       ${s.remaining > 0 ? `还需检查 <b>${s.remaining}</b> 项。` : '样本已检查完成。'}`}
            </div>
            <div style="margin-top:12px">${bar(p)}</div>
        </div>`;
    }

    const ok = c === 1;
    return `
        <div class="samp-concl ${ok ? 'ok' : 'bad'}">
            <div class="samp-concl-hd">
                <span class="samp-badge">细节测试结论</span>
                <b>${Fmt.esc(s.conclusionDesc || (ok ? '可接受' : '不可接受'))}</b>
                <span class="grow"></span>
                <span class="mono">错报上限 <b>${VS.money(s.upperMisstatement)}</b></span>
            </div>
            <div class="samp-concl-txt">${Fmt.esc(s.conclusionText || '')}</div>
            <div class="samp-concl-foot">
                估计错报 ${VS.money(s.projectedMisstatement)}
                + 抽样风险允许度 ${VS.money(s.precisionAmount)}
                = 上限 ${VS.money(s.upperMisstatement)}
                · 可容忍错报 ${VS.money(s.tolerableMisstatement)}
                · 样本标准差 ${VS.money(s.sampleStdDev)}
            </div>
        </div>`;
};

/* ---------- 列表 ---------- */
ROUTES['variable-sampling'] = async function () {
    setCrumb([{text: '审计执行'}, {text: '变量抽样'}]);
    content().innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">变量抽样</h1>
                <p class="page-desc">细节测试：用样本的账面/审定金额推断总体错报上限，判断金额是否可接受</p>
            </div>
            ${App.canWrite() ? `<button class="btn btn-primary" id="btn-new-vs">${Icon.plus} 新建抽样计划</button>` : ''}
        </div>
        <div class="card">
            <div class="toolbar">
                <div class="filter-inline">
                    <select id="f-vs-project" style="min-width:280px"></select>
                </div>
                <span class="grow"></span>
                <button class="btn btn-sm" id="btn-vs-refresh">${Icon.refresh} 刷新</button>
            </div>
            <div id="vs-list">${loadingBox()}</div>
        </div>`;

    let res;
    try {
        res = await API.projectPage({pageNum: 1, pageSize: 100});
    } catch (e) {
        document.getElementById('vs-list').innerHTML = emptyBox('项目加载失败', e.message);
        return;
    }

    VS.projects = (res && res.records) || [];
    const sel = document.getElementById('f-vs-project');
    const host = document.getElementById('vs-list');

    if (!VS.projects.length) {
        sel.innerHTML = '<option value="">暂无项目</option>';
        host.innerHTML = emptyBox('还没有审计项目', '抽样计划必须挂在项目下，先到「审计项目」新建一个');
        return;
    }

    const prefer = App.ctx.projectId || VS.projectId;
    const cur = VS.projects.some(p => String(p.id) === String(prefer))
        ? String(prefer) : String(VS.projects[0].id);

    sel.innerHTML = VS.projects.map(p =>
        `<option value="${p.id}" ${String(p.id) === cur ? 'selected' : ''}>${Fmt.esc(p.projectName)}</option>`).join('');
    VS.projectId = cur;

    sel.onchange = () => {
        VS.projectId = sel.value;
        VS.load();
    };
    document.getElementById('btn-vs-refresh').onclick = () => VS.load();

    const addBtn = document.getElementById('btn-new-vs');
    if (addBtn) addBtn.onclick = () => VS.create();

    VS.load();
};

VS.load = async function () {
    const host = document.getElementById('vs-list');
    if (!host) return;
    host.innerHTML = loadingBox();

    let rows;
    try {
        rows = await API.vsList(VS.projectId) || [];
    } catch (e) {
        host.innerHTML = emptyBox('加载失败', e.message);
        return;
    }

    if (!rows.length) {
        host.innerHTML = emptyBox('该项目还没有变量抽样计划',
            '点右上角「新建抽样计划」，填总体账面金额与可容忍错报，系统按公式算出应抽多少项');
        return;
    }

    host.innerHTML = `<div class="table-wrap"><table>
        <thead><tr>
            <th style="width:26%">科目 / 计划编号</th>
            <th style="width:150px">估计方法</th>
            <th style="width:120px">已检查 / 应抽</th>
            <th style="width:150px">错报上限</th>
            <th style="width:150px">可容忍错报</th>
            <th style="width:100px">结论</th>
            <th style="width:100px">操作</th>
        </tr></thead>
        <tbody>${rows.map(s => `
            <tr>
                <td class="primary">
                    <div style="cursor:pointer" data-open="${s.id}">${Fmt.esc(s.accountName)}</div>
                    <div class="sub mono">${Fmt.esc(s.samplingNo)}</div>
                </td>
                <td class="nowrap">${VS.methodTag(s)}</td>
                <td class="mono">${Fmt.num(s.checkedCount)} / ${Fmt.num(s.sampleSize)}</td>
                <td class="mono">${s.upperMisstatement == null
            ? '<span class="txt-3">—</span>' : VS.money(s.upperMisstatement)}</td>
                <td class="mono">${VS.money(s.tolerableMisstatement)}</td>
                <td>${tag('vsConclusion', s.conclusion)}</td>
                <td><div class="cell-actions">
                    <button class="btn-link" data-open="${s.id}">详情</button>
                    ${App.canWrite() && Number(s.conclusion) === 0
            ? `<button class="btn-link danger" data-del="${s.id}">删除</button>` : ''}
                </div></td>
            </tr>`).join('')}
        </tbody></table></div>`;

    host.querySelectorAll('[data-open]').forEach(b => {
        b.onclick = () => go('vs-detail', {vsId: b.dataset.open});
    });
    host.querySelectorAll('[data-del]').forEach(b => {
        b.onclick = () => VS.remove(b.dataset.del, () => VS.load());
    });
};

/* ---------- 新建计划 ---------- */
VS.create = function () {
    if (!App.canWrite()) return Toast.error('当前账号为只读账号，不能创建抽样计划');
    if (!VS.projects.length) return Toast.error('请先创建审计项目');

    const pid = VS.projectId || VS.projects[0].id;
    const projOptions = VS.projects.map(p =>
        `<option value="${p.id}" ${String(p.id) === String(pid) ? 'selected' : ''}>${Fmt.esc(p.projectName)}</option>`).join('');

    Modal.open({
        title: '新建变量抽样计划',
        xwide: true,
        body: `
            <div class="notice"><span class="nk">金额单位：元</span><span>
                所有金额按 <b>元</b> 填写（例如 100 万填 1000000）。可容忍错报不能大于总体账面金额，
                它是判断"错报上限是否超标"的那条线。</span></div>

            <div class="form-row">
                <div class="field"><label>所属项目<span class="req">*</span></label>
                    <select id="nv-project">${projOptions}</select></div>
                <div class="field"><label>关联审计事项</label>
                    <select id="nv-issue"><option value="">不关联</option></select></div>
            </div>
            <div class="field"><label>被测试的科目 / 项目<span class="req">*</span></label>
                <input type="text" id="nv-account" placeholder="如：存货—原材料 / 应收账款"></div>

            <div class="form-row">
                <div class="field"><label>总体账面金额（元）<span class="req">*</span></label>
                    <input type="number" id="nv-book" step="0.01" min="0.01" placeholder="如 5000000"></div>
                <div class="field"><label>总体项数<span class="req">*</span></label>
                    <input type="number" id="nv-pop" min="1" placeholder="如 2000"></div>
            </div>

            <div class="form-row">
                <div class="field"><label>可容忍错报（元）<span class="req">*</span></label>
                    <input type="number" id="nv-tol" step="0.01" min="0.01" placeholder="如 150000"></div>
                <div class="field"><label>预期错报（元）</label>
                    <input type="number" id="nv-exp" step="0.01" min="0" value="0"></div>
            </div>

            <div class="form-row">
                <div class="field"><label>预估单笔标准差（元）</label>
                    <input type="number" id="nv-sd" step="0.01" min="0" placeholder="留空则先抽 30 项试点">
                    <div class="hint">不知道就别填。系统会用试点样本的实际标准差重算样本量。</div></div>
                <div class="field"><label>置信水平</label>
                    <select id="nv-conf">
                        <option value="NINETY_FIVE">95%（风险 5%）</option>
                        <option value="NINETY">90%（风险 10%）</option>
                    </select></div>
            </div>

            <div class="field"><label>估计方法</label>
                <select id="nv-method">
                    <option value="DIFFERENCE">差额估计（错报与金额无关时用）</option>
                    <option value="RATIO">比率估计（错报与金额成比例时用）</option>
                    <option value="MEAN_PER_UNIT">均值估计（最稳健，样本量最大）</option>
                </select></div>

            <div class="notice" id="nv-hint"><span class="nk">方法前提</span>
                <span>差额估计以样本平均单笔错报推断总体，要求错报与项目金额无关。</span></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="nv-save">创建并计算样本量</button>`,
        onMount(el, close) {
            const issueSel = el.querySelector('#nv-issue');
            const hint = el.querySelector('#nv-hint');

            const notes = {
                DIFFERENCE: '差额估计以样本平均单笔错报推断总体，要求错报与项目金额无关。若错报集中在少数大额项目，会低估总体错报。',
                RATIO: '比率估计以样本"错报/账面"的比率推断，要求错报与金额成比例。通常最精确，但样本账面金额过于集中时不稳定。',
                MEAN_PER_UNIT: '均值估计直接用审定金额均值推断总体，不依赖错报分布，最稳健；代价是方差最大、样本量最多。'
            };

            const loadIssues = async (projectId) => {
                issueSel.innerHTML = '<option value="">不关联</option>';
                if (!projectId) return;
                try {
                    const list = await API.issueList(projectId) || [];
                    issueSel.innerHTML = '<option value="">不关联</option>' + list.map(i =>
                        `<option value="${i.id}">${Fmt.esc(i.issueTitle)}</option>`).join('');
                } catch (e) {
                    /* 无权限或项目无事项时保持"不关联" */
                }
            };

            el.querySelector('#nv-project').onchange = (e) => loadIssues(e.target.value);
            loadIssues(el.querySelector('#nv-project').value);

            el.querySelector('#nv-method').onchange = (e) => {
                hint.innerHTML = `<span class="nk">方法前提</span><span>${notes[e.target.value]}</span>`;
            };

            el.querySelector('#nv-save').onclick = async () => {
                const btn = el.querySelector('#nv-save');
                const projectId = el.querySelector('#nv-project').value;
                const issueId = el.querySelector('#nv-issue').value;
                const accountName = el.querySelector('#nv-account').value.trim();
                const book = Number(el.querySelector('#nv-book').value);
                const pop = Number(el.querySelector('#nv-pop').value);
                const tol = Number(el.querySelector('#nv-tol').value);
                const expRaw = el.querySelector('#nv-exp').value;
                const sdRaw = el.querySelector('#nv-sd').value;

                if (!accountName) return Toast.error('请填写被测试的科目名称');
                if (!(book > 0)) return Toast.error('总体账面金额必须大于 0');
                if (!(pop >= 1)) return Toast.error('总体项数必须大于 0');
                if (!(tol > 0)) return Toast.error('可容忍错报必须大于 0');
                if (tol > book) return Toast.error('可容忍错报不能大于总体账面金额');
                const exp = expRaw === '' ? 0 : Number(expRaw);
                if (!(exp >= 0)) return Toast.error('预期错报不能为负');

                const body = {
                    projectId,
                    issueId: issueId || null,
                    accountName,
                    populationBookValue: book,
                    populationSize: pop,
                    tolerableMisstatement: tol,
                    expectedMisstatement: exp,
                    assumedStdDev: sdRaw === '' ? null : Number(sdRaw),
                    confidenceLevel: el.querySelector('#nv-conf').value,
                    method: el.querySelector('#nv-method').value
                };

                btn.disabled = true;
                btn.textContent = '计算中…';
                try {
                    const vo = await API.vsCreate(body);
                    close();
                    Toast.ok(vo.isPilot
                        ? `先抽 ${vo.sampleSize} 项试点，抽满后自动重算样本量`
                        : `样本量 ${vo.sampleSize} 项`);
                    go('vs-detail', {vsId: vo.id});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                    btn.textContent = '创建并计算样本量';
                }
            };
        }
    });
};

/* ---------- 详情 ---------- */
ROUTES['vs-detail'] = async function () {
    const id = App.ctx.vsId;
    content().innerHTML = loadingBox('加载变量抽样计划');

    let s, p;
    try {
        s = await API.vsDetail(id);
    } catch (e) {
        content().innerHTML = emptyBox('抽样计划不存在或无权访问', e.message);
        return;
    }
    p = await API.projectDetail(s.projectId).catch(() => null);

    setCrumb([
        {text: '审计执行'},
        {text: '变量抽样', action: 'variable-sampling'},
        {text: s.samplingNo}
    ]);

    const items = s.items || [];
    const recorded = items.length;
    const cap = Math.max(0, Number(s.sampleSize) - recorded);
    const pending = Number(s.conclusion) === 0;
    const canWrite = App.canWrite() && pending;
    const upperCls = s.upperMisstatement == null ? ''
        : (Number(s.upperMisstatement) > Number(s.tolerableMisstatement) ? 'danger' : 'accent');

    const itemRows = items.map(it => {
        const checked = it.auditedValue !== null && it.auditedValue !== undefined;
        const diff = it.misstatement;
        const diffCell = !checked
            ? '<span class="tag tag-muted">未检查</span>'
            : (Number(diff) === 0
                ? '<span class="txt-3">0.00</span>'
                : `<span class="${Number(diff) > 0 ? 'txt-danger' : 'txt-warn'}">${Fmt.money(diff)}</span>`);

        let act = '';
        if (canWrite) {
            act = `<button class="btn-link" data-check="${it.id}" data-book="${it.bookValue}"
                        data-audit="${checked ? it.auditedValue : ''}"
                        data-remark="${Fmt.esc(it.remark || '')}">${checked ? '修改' : '录入结果'}</button>`
                + (checked ? `<button class="btn-link danger" data-undo="${it.id}">撤销</button>` : '');
        }

        return `<tr>
            <td class="mono" style="color:var(--tx-3)">${String(it.seqNo).padStart(2, '0')}</td>
            <td class="primary mono">${Fmt.esc(it.voucherNo)}</td>
            <td class="mono">${Fmt.d(it.voucherDate)}</td>
            <td class="mono" style="text-align:right">${Fmt.money(it.bookValue)}</td>
            <td class="mono" style="text-align:right">${checked ? Fmt.money(it.auditedValue)
            : '<span class="txt-3">—</span>'}</td>
            <td class="mono" style="text-align:right">${diffCell}</td>
            <td style="color:var(--tx-2)">${it.remark ? Fmt.esc(it.remark)
            : '<span class="txt-3">—</span>'}</td>
            <td class="mono">${it.checkedTime ? Fmt.dt(it.checkedTime) : '-'}</td>
            <td><div class="cell-actions">${act || '<span class="txt-3">—</span>'}</div></td>
        </tr>`;
    }).join('');

    content().innerHTML = `
        <div class="page-head">
            <div>
                <div class="flex" style="gap:10px;margin-bottom:7px">
                    <h1 class="page-title" style="margin:0">${Fmt.esc(s.accountName)}</h1>
                    ${tag('vsConclusion', s.conclusion)}
                    ${VS.methodTag(s)}
                    ${s.isPilot ? '<span class="tag tag-warn">试点阶段</span>' : ''}
                </div>
                <p class="page-desc"><span class="mono">${Fmt.esc(s.samplingNo)}</span>
                    · ${Fmt.esc(p ? p.projectName : ('项目 #' + s.projectId))}
                    · 总体 ${Fmt.num(s.populationSize)} 项 / ${Fmt.money(s.populationBookValue)}</p>
            </div>
            <div class="flex">
                <button class="btn btn-sm" data-back>${Icon.back} 返回</button>
                ${canWrite && cap > 0
        ? `<button class="btn btn-sm" id="btn-vs-add">${Icon.plus} 录入样本</button>` : ''}
                ${canWrite && s.readyToConclude
        ? `<button class="btn btn-primary btn-sm" id="btn-vs-eval">出具结论</button>` : ''}
                ${canWrite ? `<button class="btn btn-sm btn-danger" id="btn-vs-del">删除</button>` : ''}
            </div>
        </div>

        <div class="metric-grid">
            <div class="metric accent">
                <div class="lb">总体账面金额</div>
                <div class="vl" style="font-size:20px;padding-top:3px">${Fmt.money(s.populationBookValue)}</div>
                <div class="foot">共 ${Fmt.num(s.populationSize)} 项</div>
            </div>
            <div class="metric info">
                <div class="lb">${s.isPilot ? '试点样本' : '应抽样本'}</div>
                <div class="vl">${Fmt.num(s.sampleSize)}<span class="unit">项</span></div>
                <div class="foot">已检查 ${Fmt.num(s.checkedCount)} 项
                    ${s.remaining > 0 ? '· 还差 ' + s.remaining : '· 已完成'}</div>
            </div>
            <div class="metric">
                <div class="lb">估计错报</div>
                <div class="vl" style="font-size:20px;padding-top:3px">${VS.money(s.projectedMisstatement)}</div>
                <div class="foot">点估计，不是判断依据</div>
            </div>
            <div class="metric ${upperCls}">
                <div class="lb">错报上限</div>
                <div class="vl" style="font-size:20px;padding-top:3px">${VS.money(s.upperMisstatement)}</div>
                <div class="foot">精度 ${VS.money(s.precisionAmount)}
                    · 可容忍 ${Fmt.money(s.tolerableMisstatement)}</div>
            </div>
        </div>

        ${VS.conclusionCard(s)}

        <div class="card">
            <div class="card-head">
                <div class="card-title">样本明细 <span class="cnt">${recorded} / ${Fmt.num(s.sampleSize)}</span></div>
                <div class="flex" style="gap:8px">
                    ${pending
        ? `<span class="txt-3" style="font-size:11.5px">还可录入 ${cap} 项</span>`
        : '<span class="tag tag-ghost plain">结论已出具，样本锁定</span>'}
                </div>
            </div>
            ${items.length
        ? `<div class="table-wrap"><table>
                    <thead><tr>
                        <th style="width:46px">序号</th><th style="width:14%">凭证号</th>
                        <th style="width:95px">凭证日期</th>
                        <th style="width:130px;text-align:right">账面金额</th>
                        <th style="width:130px;text-align:right">审定金额</th>
                        <th style="width:120px;text-align:right">错报</th>
                        <th>检查说明</th>
                        <th style="width:125px">检查时间</th>
                        <th style="width:130px">操作</th>
                    </tr></thead>
                    <tbody>${itemRows}</tbody>
                </table></div>`
        : emptyBox('还没有录入样本', pending ? '点右上角「录入样本」，先登记抽样清单，审定金额可以之后再回填' : '该计划没有样本记录')}
        </div>

        <div class="grid-2">
            <div class="card">
                <div class="card-head"><div class="card-title">抽样设计</div></div>
                <div class="card-body">
                    <div class="kv-grid">
                        <div class="kv"><div class="k">估计方法</div>
                            <div class="v">${Fmt.esc(s.methodDesc || '-')}</div></div>
                        <div class="kv"><div class="k">置信水平</div>
                            <div class="v">${Fmt.esc(s.confidenceDesc || '-')}</div></div>
                        <div class="kv"><div class="k">总体项数</div>
                            <div class="v mono">${Fmt.num(s.populationSize)} 项</div></div>
                        <div class="kv"><div class="k">总体账面金额</div>
                            <div class="v mono">${Fmt.money(s.populationBookValue)}</div></div>
                        <div class="kv"><div class="k">可容忍错报</div>
                            <div class="v mono">${Fmt.money(s.tolerableMisstatement)}</div></div>
                        <div class="kv"><div class="k">预期错报</div>
                            <div class="v mono">${Fmt.money(s.expectedMisstatement)}</div></div>
                        <div class="kv"><div class="k">预估标准差</div>
                            <div class="v mono">${s.assumedStdDev == null ? '未提供（试点）' : Fmt.money(s.assumedStdDev)}</div></div>
                        <div class="kv"><div class="k">样本标准差</div>
                            <div class="v mono">${VS.money(s.sampleStdDev)}</div></div>
                        <div class="kv full"><div class="k">计算依据</div>
                            <div class="v" style="color:var(--tx-2);line-height:1.8;font-size:12px">
                                ${Fmt.esc(s.basis || '—')}</div></div>
                    </div>
                </div>
            </div>

            <div class="card">
                <div class="card-head"><div class="card-title">判定口径</div></div>
                <div class="card-body" style="font-size:12.5px;color:var(--tx-2);line-height:1.9">
                    <div class="section-label" style="margin-top:0">错报上限 = 估计错报 + 精度</div>
                    判断依据是<b>上限</b>，不是点估计。样本里没抽到错报，
                    不等于总体没有错报；精度这一项就是为"没抽到"留的余地。

                    <div class="section-label">三种估计方法</div>
                    差额估计要求错报与金额无关，比率估计要求错报与金额成比例，
                    均值估计不依赖错报分布但样本量最大。前提不成立时，算出来的数再精确也是错的。

                    <div class="section-label">零错报</div>
                    样本零错报时标准差为 0，t 区间会退化成 0，系统改用泊松基本精度兜底
                    （系数约 3），所以"抽 50 项没发现问题"依然可能证明不了总体金额没问题。

                    <div class="samp-note">
                        试点样本：不知道标准差时先抽 30 项，抽满后系统用实际标准差重算正式样本量。
                        不够就扩大 —— 而不是拿 30 项直接下结论。
                    </div>
                </div>
            </div>
        </div>`;

    const c = content();
    c.querySelector('[data-back]').onclick = () => go('variable-sampling');

    const addBtn = c.querySelector('#btn-vs-add');
    if (addBtn) addBtn.onclick = () => VS.addItems(s);

    const evalBtn = c.querySelector('#btn-vs-eval');
    if (evalBtn) {
        evalBtn.onclick = () => Modal.confirm('出具评价结论',
            `已检查 ${s.checkedCount} 项样本。出具结论后样本将锁定，不能再修改。`,
            async () => {
                await API.vsEvaluate(s.id);
                Toast.ok('评价结论已出具');
                go('vs-detail', {vsId: s.id});
            }, false);
    }

    const delBtn = c.querySelector('#btn-vs-del');
    if (delBtn) delBtn.onclick = () => VS.remove(s.id, () => go('variable-sampling'));

    c.querySelectorAll('[data-check]').forEach(b => {
        b.onclick = () => VS.checkItem(s, b.dataset.check, b.dataset.book,
            b.dataset.audit, b.dataset.remark);
    });
    c.querySelectorAll('[data-undo]').forEach(b => {
        b.onclick = () => {
            API.vsCheckItem(b.dataset.undo, null, null)
                .then(() => {
                    Toast.ok('已撤销检查');
                    go('vs-detail', {vsId: s.id});
                })
                .catch(e => Toast.error(e.message));
        };
    });
};

/* ---------- 回填单条检查结果 ---------- */
VS.checkItem = function (s, itemId, book, audited, remark) {
    Modal.open({
        title: '回填检查结果',
        body: `
            <div class="notice"><span class="nk">金额单位：元</span><span>
                账面金额 <b>${Fmt.money(book)}</b>。审定金额与账面不一致时必须写明原因 ——
                只写差多少、不写为什么差，底稿在复核时是不成立的。</span></div>
            <div class="form-row">
                <div class="field"><label>审定金额<span class="req">*</span></label>
                    <input type="number" id="ck-audit" step="0.01" min="0" value="${audited}"></div>
                <div class="field"><label>错报金额</label>
                    <input type="text" id="ck-diff" readonly value="—"></div>
            </div>
            <div class="field"><label>检查说明</label>
                <textarea id="ck-remark" placeholder="如：计价错误，单价按上月价目表多记">${Fmt.esc(remark || '')}</textarea></div>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="ck-save">保存</button>`,
        onMount(el, close) {
            const auditInput = el.querySelector('#ck-audit');
            const diffInput = el.querySelector('#ck-diff');

            const refreshDiff = () => {
                const v = auditInput.value;
                if (v === '') {
                    diffInput.value = '—';
                    return;
                }
                const d = Number(book) - Number(v);
                diffInput.value = Number(d).toFixed(2);
            };
            auditInput.oninput = refreshDiff;
            refreshDiff();

            el.querySelector('#ck-save').onclick = async () => {
                const v = auditInput.value;
                if (v === '') return Toast.error('请填写审定金额（撤销检查请用列表里的「撤销」）');
                const auditedValue = Number(v);
                const rmk = el.querySelector('#ck-remark').value.trim();
                if (Math.abs(Number(book) - auditedValue) > 0.001 && !rmk) {
                    return Toast.error('审定金额与账面不一致，必须填写检查说明');
                }

                const btn = el.querySelector('#ck-save');
                btn.disabled = true;
                try {
                    await API.vsCheckItem(itemId, auditedValue, rmk || null);
                    close();
                    Toast.ok('检查结果已保存');
                    go('vs-detail', {vsId: s.id});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                }
            };
        }
    });
};

/* ---------- 批量录入样本 ---------- */
VS.addItems = function (s) {
    const recorded = (s.items || []).length;
    const cap = Math.max(0, Number(s.sampleSize) - recorded);
    if (cap <= 0) return Toast.info('样本已按计划录满');

    const rowHtml = () => `<tr>
        <td class="mono idx" style="color:var(--tx-3)"></td>
        <td><input type="text" data-k="voucherNo" placeholder="记-0012"></td>
        <td><input type="date" data-k="voucherDate"></td>
        <td><input type="number" data-k="bookValue" step="0.01" placeholder="0.00"></td>
        <td><input type="number" data-k="auditedValue" step="0.01" placeholder="留空=未检查"></td>
        <td><input type="text" data-k="remark" placeholder="有错报时必填"></td>
        <td><button class="btn-link danger" data-del-row title="删除本行">×</button></td>
    </tr>`;

    Modal.open({
        title: '录入样本清单',
        xwide: true,
        body: `
            <div class="notice"><span class="nk">录入说明</span><span>
                应抽 <b>${s.sampleSize}</b> 项，已录入 <b>${recorded}</b> 项，本次最多录入 <b>${cap}</b> 项。
                <b>审定金额留空表示"还没检查"</b>，不计入已检查数 —— 这与"审定金额为 0"是两件完全不同的事。</span></div>
            <div class="entry-wrap">
                <table class="entry-table">
                    <thead><tr>
                        <th style="width:38px">#</th>
                        <th style="width:20%">凭证号</th>
                        <th style="width:125px">凭证日期</th>
                        <th style="width:125px">账面金额</th>
                        <th style="width:135px">审定金额</th>
                        <th>检查说明</th>
                        <th style="width:34px"></th>
                    </tr></thead>
                    <tbody id="vs-entry-body"></tbody>
                </table>
            </div>
            <button class="btn btn-sm" id="vs-add-row" style="margin-top:10px">${Icon.plus} 添加一行</button>`,
        footer: `<button class="btn" data-dismiss>取消</button>
                 <button class="btn btn-primary" id="vs-entry-save">提交录入</button>`,
        onMount(el, close) {
            const tbody = el.querySelector('#vs-entry-body');

            const renumber = () => [...tbody.children].forEach((tr, i) => {
                tr.querySelector('.idx').textContent = String(i + 1).padStart(2, '0');
            });

            const addRow = () => {
                tbody.insertAdjacentHTML('beforeend', rowHtml());
                renumber();
            };

            for (let i = 0; i < Math.min(3, cap); i++) addRow();

            el.querySelector('#vs-add-row').onclick = () => {
                if (tbody.children.length >= cap) return Toast.info(`本次最多录入 ${cap} 项`);
                addRow();
            };

            tbody.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-del-row]');
                if (!btn) return;
                if (tbody.children.length <= 1) return Toast.info('至少保留一行');
                btn.closest('tr').remove();
                renumber();
            });

            el.querySelector('#vs-entry-save').onclick = async () => {
                const list = [];
                let err = null;

                [...tbody.children].forEach((tr, i) => {
                    if (err) return;
                    const get = (k) => tr.querySelector(`[data-k="${k}"]`).value.trim();
                    const voucherNo = get('voucherNo');
                    const date = get('voucherDate');
                    const bookRaw = get('bookValue');
                    const auditRaw = get('auditedValue');
                    const remark = get('remark');

                    if (!voucherNo && !date && !bookRaw && !auditRaw && !remark) return;

                    if (!voucherNo) {
                        err = `第 ${i + 1} 行缺少凭证号`;
                        return;
                    }
                    if (bookRaw === '') {
                        err = `第 ${i + 1} 行缺少账面金额`;
                        return;
                    }
                    const book = Number(bookRaw);
                    const audited = auditRaw === '' ? null : Number(auditRaw);
                    if (audited !== null && Math.abs(book - audited) > 0.001 && !remark) {
                        err = `第 ${i + 1} 行有错报（${(book - audited).toFixed(2)} 元），请填写检查说明`;
                        return;
                    }
                    list.push({
                        voucherNo,
                        voucherDate: date || null,
                        bookValue: book,
                        auditedValue: audited,
                        remark: remark || null
                    });
                });

                if (err) return Toast.error(err);
                if (!list.length) return Toast.error('请至少填写一条样本');
                if (list.length > cap) return Toast.error(`本次最多录入 ${cap} 项`);

                const btn = el.querySelector('#vs-entry-save');
                btn.disabled = true;
                btn.textContent = '提交中…';
                try {
                    const vo = await API.vsAddItems(s.id, list);
                    close();
                    Toast.ok(vo && vo.sampleSize !== s.sampleSize
                        ? `已录入 ${list.length} 项；按实际标准差重算，样本量调整为 ${vo.sampleSize} 项`
                        : `已录入 ${list.length} 项样本`);
                    go('vs-detail', {vsId: s.id});
                } catch (e) {
                    Toast.error(e.message);
                    btn.disabled = false;
                    btn.textContent = '提交录入';
                }
            };
        }
    });
};

VS.remove = function (id, after) {
    Modal.confirm('删除变量抽样计划', '确定删除该抽样计划？计划下的样本明细会一起逻辑删除，操作不可恢复。',
        async () => {
            await API.vsDelete(id);
            Toast.ok('已删除');
            after && after();
        });
};

/* ==========================================================================
   十三、启动
   ========================================================================== */
(function bootstrap() {
    document.getElementById('logout-btn').onclick = async () => {
        try {
            await API.logout();
        } catch (e) { /* 忽略 */
        }
        toLogin('已退出登录');
        Toast.info('已退出登录');
    };

    renderLogin();

    // 已有 token：直接进主界面，用 /auth/me 校验一次有效性
    if (Store.token) {
        API.me()
            .then(u => enterApp(u || Store.user))
            .catch(() => {
                /* 拦截层已经处理跳登录 */
            });
    }
})();
