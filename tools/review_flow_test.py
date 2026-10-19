#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
三级复核流转 —— 全链路实测脚本

覆盖 12 个场景，包括正常流转和各类该被拦截的越权/越级操作。
用法: python docs/review_flow_test.py [baseUrl]
"""
import json
import sys
import urllib.request
import urllib.error

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api").rstrip("/")
PWD = "123456"
EVIDENCE_ID = None
TOKENS = {}
passed = failed = 0


def call(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"code": e.code, "message": "HTTP %s" % e.code}


def login(user):
    r = call("POST", "/auth/login", {"username": user, "password": PWD})
    if r.get("code") != 200:
        raise SystemExit("登录失败 %s: %s" % (user, r))
    TOKENS[user] = r["data"]["token"]
    return r["data"]


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [PASS] " + name)
    else:
        failed += 1
        print("  [FAIL] " + name + ("  -> " + str(detail) if detail else ""))


def detail(uid, token):
    return call("GET", "/evidence/%s" % uid, token=token)


print("=" * 62)
print(" 三级复核流转实测")
print("=" * 62)

# ---------- 0. 登录四个角色 ----------
print("\n[0] 角色账号登录")
for u in ["lisi", "leader", "manager", "partner", "admin"]:
    d = login(u)
    print("  %-8s -> %s (%s)" % (u, d["realName"], d["roleName"]))

# ---------- 1. 用审计员身份建证据并提交 ----------
print("\n[1] 审计员李四创建证据并提交")
r = call("POST", "/evidence", {
    "projectId": 1, "issueId": 1,
    "evidenceName": "三级复核流转测试-银行询证函",
    "evidenceType": "DOCUMENT", "source": "招商银行南京分行",
    "sourceType": "EXTERNAL", "acquireMethod": "DIRECT",
    "formType": "WRITTEN", "originality": "ORIGINAL", "controlEffective": True,
    "filePath": "/2026/10/test.pdf", "fileSize": 1024,
    "remark": ""
}, token=TOKENS["lisi"])
EVIDENCE_ID = r.get("data")
check("证据创建成功", r.get("code") == 200, r)
print("  证据ID = %s" % EVIDENCE_ID)

d = detail(EVIDENCE_ID, TOKENS["lisi"])["data"]
check("新建为草稿", d["status"] == 0, d["status"])
check("可靠性为 A 级（外部独立来源原件）", d["reliabilityGrade"] == "A", d["reliabilityGrade"])

r = call("PUT", "/evidence/%s/submit" % EVIDENCE_ID, token=TOKENS["lisi"])
check("提交复核成功", r.get("code") == 200, r)

d = detail(EVIDENCE_ID, TOKENS["lisi"])["data"]
check("状态 -> 待详细复核(1)", d["status"] == 1, d["status"])
check("当前级别 = 1", d["currentLevel"] == 1, d["currentLevel"])
check("待办岗位为项目负责人", d["pendingRoleName"] == "项目负责人", d["pendingRoleName"])

# ---------- 2. 越权拦截 ----------
print("\n[2] 越级/越权拦截（这是三级复核最关键的部分）")

r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "我是部门经理，直接批了"}, token=TOKENS["manager"])
check("部门经理不能审批一级", r.get("code") == 3008, r)
print("    -> " + str(r.get("message")))

r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "我越级批"}, token=TOKENS["partner"])
check("主任会计师不能越过二级直接批一级", r.get("code") == 3008, r)

r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "我审自己的"}, token=TOKENS["lisi"])
check("审计员无复核权", r.get("code") == 3008, r)

# ---------- 3. 一级通过 ----------
print("\n[3] 项目负责人执行详细复核")
r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "询证函回函与账面余额核对一致，程序执行充分"}, token=TOKENS["leader"])
check("一级通过成功", r.get("code") == 200, r)
print("    -> %s" % r.get("data"))

d = detail(EVIDENCE_ID, TOKENS["leader"])["data"]
check("状态 -> 待一般复核(4)", d["status"] == 4, d["status"])
check("当前级别 = 2", d["currentLevel"] == 2, d["currentLevel"])
check("待办岗位为部门经理", d["pendingRoleName"] == "部门经理", d["pendingRoleName"])
check("一级通过后证据尚未生效", d["reviewFinished"] is False, d["reviewFinished"])

# ---------- 4. 二级越权与通过 ----------
print("\n[4] 部门经理执行一般复核")
r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "项目负责人想再批一次"}, token=TOKENS["leader"])
check("项目负责人不能重复批二级", r.get("code") == 3008, r)

r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "重要账项调整已复核，无异常"}, token=TOKENS["manager"])
check("二级通过成功", r.get("code") == 200, r)
print("    -> %s" % r.get("data"))

d = detail(EVIDENCE_ID, TOKENS["manager"])["data"]
check("状态 -> 待重点复核(5)", d["status"] == 5, d["status"])
check("当前级别 = 3", d["currentLevel"] == 3, d["currentLevel"])
check("待办岗位为主任会计师", d["pendingRoleName"] == "主任会计师", d["pendingRoleName"])

# ---------- 5. 三级通过，落定为已确认 ----------
print("\n[5] 主任会计师执行重点复核（末级）")
r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID,
         {"comment": "重大审计判断已复核，同意形成审计结论"}, token=TOKENS["partner"])
check("三级通过成功", r.get("code") == 200, r)
print("    -> %s" % r.get("data"))

d = detail(EVIDENCE_ID, TOKENS["partner"])["data"]
check("状态 -> 已确认(2)", d["status"] == 2, d["status"])
check("三级已完成", d["reviewFinished"] is True, d["reviewFinished"])
check("不再处于复核中", d["reviewing"] is False, d["reviewing"])

# ---------- 6. 流转轨迹完整性 ----------
print("\n[6] 流转轨迹可追溯性")
fl = call("GET", "/evidence/%s/flows" % EVIDENCE_ID, token=TOKENS["admin"])["data"]
print("  共 %d 条流转记录：" % len(fl))
for f in fl:
    print("    %-12s %-10s %-8s %s" % (
        f.get("actionName"), f.get("reviewLevelName") or "-",
        f.get("operatorName"), (f.get("comment") or "")[:34]))

actions = [f["action"] for f in fl]
check("轨迹含创建", "CREATE" in actions, actions)
check("轨迹含提交", "SUBMIT" in actions, actions)
check("轨迹含三级通过", all(a in actions for a in ["APPROVE_L1", "APPROVE_L2", "APPROVE_L3"]), actions)

# 只看复核动作的级别（SUBMIT 也会记 level=1，因为它进入了一级，这是合理的）
review_flows = [f for f in fl if (f.get("action") or "").startswith(("APPROVE", "REJECT"))]
check("三级复核操作的级别依次为 1/2/3", [f["reviewLevel"] for f in review_flows] == [1, 2, 3],
      [f.get("reviewLevel") for f in review_flows])
check("每级签字人各不相同", len({f["operatorName"] for f in review_flows}) == 3,
      [f.get("operatorName") for f in review_flows])
check("每级都有意见留痕", all(f.get("comment") for f in review_flows), review_flows)

# ---------- 7. 已确认后不可再操作 ----------
print("\n[7] 已确认后的状态保护")
r = call("PUT", "/evidence/%s/approve" % EVIDENCE_ID, {"comment": "再批一次"},
         token=TOKENS["partner"])
check("已确认证据不能再复核", r.get("code") == 3003, r)

# ---------- 8. 驳回场景 ----------
print("\n[8] 驳回：二级驳回退回编制人")
r = call("POST", "/evidence", {
    "projectId": 1, "issueId": 1, "evidenceName": "三级复核流转测试-驳回案例",
    "evidenceType": "DOCUMENT", "source": "财务部",
    "sourceType": "INTERNAL", "acquireMethod": "INDIRECT",
    "formType": "ELECTRONIC", "originality": "SCANNED", "controlEffective": True,
    "filePath": "/2026/10/t2.pdf", "fileSize": 512,
    "remark": "已补充ERP系统截图与总账核对表"
}, token=TOKENS["lisi"])
eid2 = r.get("data")
check("第二条证据创建", r.get("code") == 200, r)

call("PUT", "/evidence/%s/submit" % eid2, token=TOKENS["lisi"])
call("PUT", "/evidence/%s/approve" % eid2, {"comment": "一级无问题"}, token=TOKENS["leader"])

r = call("PUT", "/evidence/%s/reject" % eid2, {"comment": ""}, token=TOKENS["manager"])
check("驳回必须填意见", r.get("code") == 400, r)

r = call("PUT", "/evidence/%s/reject" % eid2,
         {"comment": "扫描件清晰度不足，关键金额栏无法辨认，请补充原件照片"}, token=TOKENS["manager"])
check("二级驳回成功", r.get("code") == 200, r)

d = detail(eid2, TOKENS["manager"])["data"]
check("驳回后状态 -> 已驳回(3)", d["status"] == 3, d["status"])
check("驳回后回到起点(级别0)", d["currentLevel"] == 0, d["currentLevel"])

fl = call("GET", "/evidence/%s/flows" % eid2, token=TOKENS["admin"])["data"]
rej = [f for f in fl if f["action"].startswith("REJECT")]
check("驳回记录标注了发生级别", rej and rej[0]["reviewLevel"] == 2, rej)

# ---------- 9. 驳回后重新提交，从第一级重走 ----------
print("\n[9] 驳回后重新提交：必须从第一级重走")
r = call("PUT", "/evidence/%s/submit" % eid2, token=TOKENS["lisi"])
check("已驳回可重新提交", r.get("code") == 200, r)
d = detail(eid2, TOKENS["lisi"])["data"]
check("重新提交后回到待详细复核(1)", d["status"] == 1, d["status"])
check("级别重置为 1", d["currentLevel"] == 1, d["currentLevel"])

# ---------- 10. 待办队列按角色过滤 ----------
print("\n[10] 各角色能否看到复核按钮（canReview）")
d = detail(eid2, TOKENS["leader"])["data"]
check("项目负责人在一级环节可复核", d["canReview"] is True, d["canReview"])
d = detail(eid2, TOKENS["manager"])["data"]
check("部门经理在非本级环节不可复核", d["canReview"] is False, d["canReview"])
d = detail(eid2, TOKENS["lisi"])["data"]
check("审计员始终不可复核", d["canReview"] is False, d["canReview"])

print("\n" + "=" * 62)
print(" 测试完成: 通过 %d 项, 失败 %d 项" % (passed, failed))
print("=" * 62)
print("\n清理用证据ID: %s, %s" % (EVIDENCE_ID, eid2))
sys.exit(0 if failed == 0 else 1)
