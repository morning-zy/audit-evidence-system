/*
 * 前端可靠性算法与后端 EvidenceReliabilityEvaluator 的一致性校验
 * 用法: node docs/RelCheck.js
 *
 * 这个脚本最初是用来「确认前端没写错」的，结果第一轮跑出 3 条 MISMATCH，
 * 反倒暴露了分档规则本身的三处漏洞（见下方 cases 里的注释）。
 * 现在它同时承担两个职责：前后端一致性校验 + 分档规则回归测试。
 */
const W = { SRC: 3.0, ORI: 2.0, ACQ: 1.0, FORM: 1.0, PENALTY: 4.0 };
const SCORE = {
  EXTERNAL: 3, INTERNAL: 2, MANAGEMENT: 1,
  ORIGINAL: 3, SCANNED: 2, COPY: 1,
  DIRECT: 3, INDIRECT: 2, INFERRED: 1,
  WRITTEN: 3, ELECTRONIC: 2, ORAL: 1
};

function calc(src, ori, acq, form, ctl) {
  let s = SCORE[src] * W.SRC + SCORE[ori] * W.ORI
        + SCORE[acq] * W.ACQ + SCORE[form] * W.FORM;
  if (src === 'INTERNAL' && !ctl) s -= W.PENALTY;
  s = Math.max(0, s);
  // 第三版分档：A/B 看性质门槛，C/D 看分数
  let g;
  if (src === 'EXTERNAL' && ori !== 'COPY') g = 'A';
  else if (ori === 'ORIGINAL' && src !== 'MANAGEMENT' && s >= 15) g = 'B';
  else if (s >= 8) g = 'C';
  else g = 'D';
  return { score: s, grade: g };
}

// [来源, 原件性, 获取方式, 形式, 控制有效, 期望等级, 说明]
const cases = [
  ['EXTERNAL', 'ORIGINAL', 'DIRECT', 'WRITTEN', true, 'A', '外部函证原件直接取得 —— 天花板'],
  ['EXTERNAL', 'SCANNED', 'DIRECT', 'WRITTEN', true, 'A', '外部扫描件：准则(五)只降一档，仍属外部独立来源'],
  ['EXTERNAL', 'COPY', 'DIRECT', 'WRITTEN', true, 'C', '漏洞一：外部复印件 17 分曾被评 B，但复印件无原件可核'],
  ['INTERNAL', 'ORIGINAL', 'DIRECT', 'WRITTEN', true, 'B', '内部最优点，18 分撞不到 A'],
  ['INTERNAL', 'ORIGINAL', 'DIRECT', 'WRITTEN', false, 'C', '控制薄弱扣 4 分'],
  ['INTERNAL', 'SCANNED', 'INDIRECT', 'ELECTRONIC', true, 'C', 'ERP 导出常见组合'],
  ['INTERNAL', 'COPY', 'INDIRECT', 'ORAL', true, 'C', '访谈纪要复印件'],
  ['MANAGEMENT', 'COPY', 'INFERRED', 'ORAL', false, 'D', '管理层口头推论 —— 地板'],
  ['MANAGEMENT', 'ORIGINAL', 'DIRECT', 'WRITTEN', true, 'C', '漏洞二：管理层声明 15 分曾被评 B，只能作补充证据'],
  ['INTERNAL', 'COPY', 'INDIRECT', 'ELECTRONIC', true, 'C', '漏洞三：内部复印件 13 分，被 COPY 条款压到 C 才对'],
  ['MANAGEMENT', 'ORIGINAL', 'DIRECT', 'ELECTRONIC', true, 'C', '管理层电子记录'],
  ['EXTERNAL', 'ORIGINAL', 'INDIRECT', 'ELECTRONIC', true, 'A', '外部来源即使经被审计单位转交仍为 A（来源性质优先）']
];

let pass = 0, fail = 0;
console.log('来源/原件性/获取/形式/控制 | 分数 | 等级 | 期望 | 结果 | 说明');
console.log('-'.repeat(100));
for (const [src, ori, acq, form, ctl, expect, note] of cases) {
  const r = calc(src, ori, acq, form, ctl);
  const ok = r.grade === expect;
  ok ? pass++ : fail++;
  console.log(
    `${src}/${ori}/${acq}/${form}/${ctl} | ${r.score} | ${r.grade} | ${expect} | ${ok ? 'OK' : 'MISMATCH'} | ${note}`
  );
}
console.log('-'.repeat(100));
console.log(`通过 ${pass} 条，失败 ${fail} 条`);
process.exit(fail === 0 ? 0 : 1);
