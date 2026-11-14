// 村民 32 页补抓：正确页面名（无「（村民）」后缀）。已有 raw_v2/ 文件跳过。
const fs = require('fs');
const path = require('path');
const api = 'https://wiki.biligame.com/stardewvalley/api.php';
const UA = { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' } };
const OUT = path.join(__dirname, 'raw_v2');
const VILLAGERS = ['阿比盖尔','亚历克斯','艾利欧特','哈维','山姆','塞巴斯蒂安','谢恩','潘妮','艾米丽','海莉','莉亚','玛鲁','卡洛琳','克林特','德米特里厄斯','艾芙琳','乔治','格斯','乔迪','肯特','刘易斯','莱纳斯','玛妮','皮埃尔','罗宾','威利','里奥','浣熊'];
// 已抓到的：桑迪 法师 科罗布斯 矮人
async function wikitext(title) {
  const u = api + '?' + new URLSearchParams({ action: 'parse', page: title, prop: 'wikitext', format: 'json', formatversion: '2' });
  for (let a = 0; a < 5; a++) {
    try {
      const r = await fetch(u, UA);
      const d = await r.json();
      if (d.parse && d.parse.wikitext) return { t: d.parse.title, w: d.parse.wikitext };
      if (d.error) return { err: d.error.code };
    } catch (e) {}
    await new Promise(s => setTimeout(s, 8000 + 8000 * a));
  }
  return { err: 'timeout' };
}
(async () => {
  let ok = 0, fail = 0;
  for (const p of VILLAGERS) {
    const f = path.join(OUT, p + '.wiki.txt');
    if (fs.existsSync(f) && fs.statSync(f).size > 200) continue;
    const r = await wikitext(p);
    if (r.err) { console.log('×', p, r.err); fail++; continue; }
    fs.writeFileSync(f, r.w);
    ok++; console.log('√', p, r.w.length + '字');
    await new Promise(s => setTimeout(s, 1500));
  }
  console.log(`完成：新增 ${ok} 失败 ${fail}`);
})();
