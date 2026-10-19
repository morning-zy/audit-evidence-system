/* ==========================================================================
   API 请求封装
   设计要点：
     ① 统一走 /api 前缀，与后端 context-path 一致
     ② 统一解包 Result{code,message,data}，业务成功才返回 data，失败抛异常
     ③ 401 / token 过期统一跳登录，不让每个调用点自己判断
     ④ 请求头自动带 Bearer token
   ========================================================================== */

const API_BASE = '/api';

/* ---------- 本地存储 ---------- */
const Store = {
    get token() {
        return localStorage.getItem('audit_token') || '';
    },
    set token(v) {
        v ? localStorage.setItem('audit_token', v) : localStorage.removeItem('audit_token');
    },
    get user() {
        try {
            return JSON.parse(localStorage.getItem('audit_user') || 'null');
        } catch (e) {
            return null;
        }
    },
    set user(v) {
        v ? localStorage.setItem('audit_user', JSON.stringify(v)) : localStorage.removeItem('audit_user');
    },
    clear() {
        localStorage.removeItem('audit_token');
        localStorage.removeItem('audit_user');
    }
};

/* ---------- 跳回登录 ---------- */
function toLogin(msg) {
    Store.clear();
    document.getElementById('app-view').classList.add('hidden');
    document.getElementById('login-view').classList.remove('hidden');
    if (msg) Toast.error(msg);
}

/* ---------- 核心请求 ---------- */
async function request(path, options = {}) {
    const opts = Object.assign({method: 'GET', headers: {}}, options);

    // 有 token 就带上。注意后端前缀是 "Bearer "（带空格）
    const token = Store.token;
    if (token) opts.headers['Authorization'] = 'Bearer ' + token;

    // JSON body 自动序列化
    if (opts.body && !(opts.body instanceof FormData)) {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(opts.body);
    }

    let resp;
    try {
        resp = await fetch(API_BASE + path, opts);
    } catch (e) {
        throw new Error('网络异常，请确认后端已启动');
    }

    // 文件下载类接口直接返回原始响应
    if (opts.raw) return resp;

    let json;
    try {
        json = await resp.json();
    } catch (e) {
        throw new Error('响应解析失败 (HTTP ' + resp.status + ')');
    }

    // 后端统一响应体：code=200 表示成功
    if (json.code === 200) return json.data;

    // 401 / token 过期：清掉本地状态回登录页
    if (json.code === 401 || json.code === 4010 || /登录|token/i.test(json.message || '')) {
        toLogin(json.message || '登录已失效，请重新登录');
        throw new Error(json.message);
    }

    throw new Error(json.message || '请求失败 (code ' + json.code + ')');
}

/* ---------- 查询参数拼接 ---------- */
function qs(params) {
    const parts = [];
    Object.keys(params || {}).forEach(k => {
        const v = params[k];
        if (v === null || v === undefined || v === '') return;
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    });
    return parts.length ? '?' + parts.join('&') : '';
}

/* ---------- 业务接口 ---------- */
const API = {
    /* 认证 */
    login: (body) => request('/auth/login', {method: 'POST', body}),
    me: () => request('/auth/me'),
    users: () => request('/auth/users'),
    logout: () => request('/auth/logout', {method: 'POST'}),

    /* 统计 */
    dashboard: () => request('/stat/dashboard'),
    projectStat: (id) => request('/stat/project/' + id),
    evictCache: (id) => request('/stat/project/' + id + '/cache', {method: 'DELETE'}),

    /* 项目 */
    projectPage: (p) => request('/project/page' + qs(p)),
    projectDetail: (id) => request('/project/' + id),
    projectProgress: (limit = 8) => request('/project/progress' + qs({limit})),
    projectSave: (b) => request('/project', {method: 'POST', body: b}),
    projectUpdate: (b) => request('/project', {method: 'PUT', body: b}),
    projectDelete: (id) => request('/project/' + id, {method: 'DELETE'}),
    projectStatus: (id, status) => request('/project/' + id + '/status' + qs({status}), {method: 'PUT'}),

    /* 事项 */
    issuePage: (p) => request('/issue/page' + qs(p)),
    issueList: (projectId) => request('/issue/list' + qs({projectId})),
    issueSave: (b) => request('/issue', {method: 'POST', body: b}),
    issueUpdate: (b) => request('/issue', {method: 'PUT', body: b}),
    issueDelete: (id) => request('/issue/' + id, {method: 'DELETE'}),
    issueStatus: (id, status) => request('/issue/' + id + '/status' + qs({status}), {method: 'PUT'}),

    /* 证据 */
    evidencePage: (p) => request('/evidence/page' + qs(p)),
    evidenceDetail: (id) => request('/evidence/' + id),
    evidenceSave: (b) => request('/evidence', {method: 'POST', body: b}),
    evidenceUpdate: (b) => request('/evidence', {method: 'PUT', body: b}),
    evidenceDelete: (id) => request('/evidence/' + id, {method: 'DELETE'}),
    evidenceSubmit: (id) => request('/evidence/' + id + '/submit', {method: 'PUT'}),
    evidenceApprove: (id, comment) =>
        request('/evidence/' + id + '/approve' + qs({comment}), {method: 'PUT'}),
    evidenceReject: (id, comment) =>
        request('/evidence/' + id + '/reject' + qs({comment}), {method: 'PUT'}),
    evidenceFlows: (id) => request('/evidence/' + id + '/flows'),
    evidenceExport: (p) => request('/evidence/export' + qs(p), {raw: true}),

    /* 文件 */
    upload: (file) => {
        const fd = new FormData();
        fd.append('file', file);
        return request('/file/upload', {method: 'POST', body: fd});
    },

    /* 审计抽样（准则 1314 号） */
    samplingList: (projectId) => request('/sampling/list' + qs({projectId})),
    samplingDetail: (id) => request('/sampling/' + id),
    samplingCreate: (b) => request('/sampling', {method: 'POST', body: b}),
    samplingAddItems: (id, items) =>
        request('/sampling/' + id + '/items', {method: 'POST', body: items}),
    /* deviation 传 null 表示撤销检查。qs() 会丢掉 null，所以这里手拼空值，
       让后端收到一个显式的空参数而不是"参数缺失" */
    samplingMarkItem: (itemId, deviation, desc) => request(
        '/sampling/item/' + itemId
        + '?deviation=' + (deviation === null ? '' : deviation)
        + (desc ? '&deviationDesc=' + encodeURIComponent(desc) : ''),
        {method: 'PUT'}),
    samplingConclude: (id) => request('/sampling/' + id + '/conclude', {method: 'POST'}),
    samplingDelete: (id) => request('/sampling/' + id, {method: 'DELETE'}),

    /* 变量抽样（细节测试） */
    vsList: (projectId) => request('/variable-sampling/list' + qs({projectId})),
    vsDetail: (id) => request('/variable-sampling/' + id),
    vsCreate: (b) => request('/variable-sampling', {method: 'POST', body: b}),
    vsAddItems: (id, items) =>
        request('/variable-sampling/' + id + '/items', {method: 'POST', body: items}),
    /* auditedValue 传 null 表示撤销检查。qs() 会丢掉 null，所以手拼空值，
       让后端收到显式空参数而不是"参数缺失" */
    vsCheckItem: (itemId, auditedValue, remark) => request(
        '/variable-sampling/item/' + itemId
        + '?auditedValue=' + (auditedValue === null ? '' : auditedValue)
        + (remark ? '&remark=' + encodeURIComponent(remark) : ''),
        {method: 'PUT'}),
    vsEvaluate: (id) => request('/variable-sampling/' + id + '/evaluate', {method: 'POST'}),
    vsDelete: (id) => request('/variable-sampling/' + id, {method: 'DELETE'}),

    /* 日志 */
    logPage: (p) => request('/log/page' + qs(p))
};
