// v2.5/v2.6 批次补抓：32 位村民讨厌/不喜欢 + 怪物掉落 + 熔炉冶炼 + 晶球 + 工匠通用 + 钥匙补页
// 输出到 raw_v2/，已有文件跳过（幂等）。间隔 1.5s，失败重试带退避（前次 567 拦截经验）。
const fs = require('fs');
const path = require('path');
const api = 'https://wiki.biligame.com/stardewvalley/api.php';
const UA = { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' } };
const OUT = path.join(__dirname, 'raw_v2');

const VILLAGERS = ['阿比盖尔', '亚历克斯', '艾利欧特', '哈维', '山姆', '塞巴斯蒂安', '谢恩', '潘妮', '艾米丽', '海莉',
  '莉亚', '玛鲁', '卡洛琳', '克林特', '德米特里厄斯', '艾芙琳', '乔治', '格斯', '乔迪', '肯特',
  '刘易斯', '莱纳斯', '玛妮', '皮埃尔', '罗宾', '桑迪', '威利', '法师', '科罗布斯', '里奥', '矮人', '浣熊'];

const PAGES = [
  // 数据缺口补页
  '怪物', '熔炉', '晶球', '头骨钥匙', '生锈的钥匙',
  // 通用工匠/规则参考
  '蜂箱', '腌菜桶', '酿酒桶', '果酱', '蜂蜜', '木桶',
  // 村民 32 页（讨厌/不喜欢）
  ...VILLAGERS.map(v => v + '（村民）').concat(VILLAGERS.filter(v => ['浣熊', '矮人', '科罗布斯', '法师', '桑迪'].includes(v))),
];

async function wikitext(title) {
  const u = api + '?' + new URLSearchParams({ action: 'parse', page: title, prop: 'wikitext', format: 'json', formatversion: '2' });
  for (let a = 0; a < 5; a++) {
    try {
      const r = await fetch(u, UA);
      const d = await r.json();
      if (d.parse && d.parse.wikitext) return { t: d.parse.title, w: d.parse.wikitext };
      if (d.error) return { err: d.error.code };
    } catch (e) { /* retry */ }
    await new Promise(s => setTimeout(s, 8000 + 8000 * a));
  }
  return { err: 'timeout' };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  let ok = 0, skip = 0, fail = 0;
  for (const p of PAGES) {
    const f = path.join(OUT, p + '.wiki.txt');
    if (fs.existsSync(f) && fs.statSync(f).size > 200) { skip++; continue; }
    const r = await wikitext(p);
    if (r.err) { console.log('×', p, r.err); fail++; continue; }
    fs.writeFileSync(f, r.w);
    ok++;
    console.log('√', p, '(' + r.t + ')', r.w.length + '字', r.w.includes('#重定向') ? '[重定向]' : '');
    await new Promise(s => setTimeout(s, 1500));
  }
  console.log(`完成：新增 ${ok} 跳过 ${skip} 失败 ${fail}`);
})();
