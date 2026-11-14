# -*- coding: utf-8 -*-
# 生成熔炉冶炼 + 怪物掉落 + 解锁条件（smelting / monsterDrops / unlocks）
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.dirname(HERE)

# 熔炉冶炼（wiki《熔炉》+ 通用冶炼规则；材料口径同 crafting.json）
smelting = [
 {"product":"铜锭","raw":"铜矿石×5、煤炭×1","note":"熔炉"},
 {"product":"铁锭","raw":"铁矿石×5、煤炭×1","note":"熔炉"},
 {"name":"","product":"金锭","raw":"金矿石×5、煤炭×1","note":"熔炉"},
 {"product":"铱锭","raw":"铱矿石×5、煤炭×1","note":"熔炉"},
 {"product":"精炼石英","raw":"石英×1、煤炭×1","note":"熔炉"},
 {"product":"火水晶","raw":"火水晶原石不适用；火水晶由岩浆晶洞开出","note":"—"},
 {"product":"石英（煅烧）","raw":"地晶/泪晶等晶洞矿物→晶球破碎机","note":"—"},
]

# 怪物掉落（wiki《怪物》要点精选；只收有明确掉落表的常见怪）
monsters = [
 {"name":"绿色史莱姆","drops":"史莱姆泥｜紫水晶（极低）","where":"矿井 1-120 层"},
 {"name":"蓝色史莱姆","drops":"史莱姆泥｜矮人卷轴 III 1.5%｜翡翠","where":"矿井 40+ 层"},
 {"name":"红色史莱姆","drops":"史莱姆泥｜红宝石","where":"矿井 80+ 层"},
 {"name":"紫色史莱姆","drops":"史莱姆泥｜五彩碎片（极低）","where":"骷髅洞穴"},
 {"name":"蝙蝠","drops":"蝙蝠翅膀｜稀有圆盘0.1%","where":"矿井"},
 {"name":"冰冻蝙蝠","drops":"蝙蝠翅膀｜矮人卷轴 II 0.5%","where":"矿井 40-79 层"},
 {"name":"熔岩蝙蝠","drops":"蝙蝠翅膀","where":"矿井 80+ 层"},
 {"name":"幽灵头骨","drops":"史前头骨1.3%｜手部骨骼1.3%","where":"骷髅洞穴"},
 {"name":"灰尘精灵","drops":"矮人卷轴 II 0.5%｜泪晶2%","where":"矿井 40-79 层"},
 {"name":"掘地虫","drops":"地晶10%｜咀嚼洁齿棒2%","where":"矿井/骷髅洞穴"},
 {"name":"幽灵","drops":"矮人卷轴 II 0.5%｜太阳精华","where":"矿井 40-79 层"},
 {"name":"暗影狂徒","drops":"虚空精华｜五彩碎片0.05%","where":"矿井 80+ 层"},
 {"name":"暗影萨满","drops":"虚空精华｜五彩碎片0.05%","where":"矿井 80+ 层"},
 {"name":"木乃伊","drops":"布料｜五彩碎片0.1%","where":"骷髅洞穴"},
 {"name":"飞蛇","drops":"五彩碎片0.1%","where":"骷髅洞穴"},
 {"name":"霸王喷火龙","drops":"恐龙蛋10%｜史前胫骨30%｜史前肋骨30%","where":"骷髅洞穴"},
 {"name":"史莱姆球","drops":"石化史莱姆","where":"史莱姆屋"},
 {"name":"岩浆史莱姆","drops":"史莱姆泥｜火水晶","where":"矿井 80+ 层"},
]

# 解锁进度卡（盘点 §5.2 结构化；打勾判定字段给代码用）
unlocks = [
 {"id":"cc","name":"社区中心整修","req":"完成全部收集包（或 Joja 路线买齐）","cost":"收集包 26 个 / Joja 约 135,000g","after":"社区中心开放、皮埃尔天天开、Joja 永久关闭","checkKey":"bundles_all"},
 {"id":"sewer","name":"下水道（生锈的钥匙）","req":"博物馆捐赠 60 件（古物或矿物）","cost":"","after":"科罗布斯、铱制洒水器、史莱姆鱼","checkKey":"museum60"},
 {"id":"desert","name":"沙漠（巴士修复）","req":"完成金库收集包 或 Joja 买「公共汽车」","cost":"Joja 40,000g","after":"绿洲、沙漠商人、赌场区域","checkKey":"bundle_vault"},
 {"id":"greenhouse","name":"温室","req":"完成茶水间收集包 或 Joja 修理","cost":"Joja 35,000g","after":"四季种植、无需稻草人（雨天仍要浇水）","checkKey":"bundle_pantry"},
 {"id":"minecart","name":"矿车","req":"完成锅炉房收集包 或 Joja 买「矿车」","cost":"Joja 15,000g","after":"快速旅行（采石场需再修桥）","checkKey":"bundle_boiler"},
 {"id":"quarry","name":"采石场（矿车终点）","req":"完成工艺室收集包 或 Joja 修桥","cost":"Joja 25,000g","after":"采石场","checkKey":"bundle_crafts"},
 {"id":"skull","name":"骷髅洞穴（头骨钥匙）","req":"坚果房 100 万g 购买头骨钥匙（斯卡普洞穴入口锁定）","cost":"1,000,000g","after":"铱矿高概率层、齐先生的挑战（25 层奖 10,000g）","checkKey":"skull_key"},
 {"id":"casino","name":"赌场（神秘的齐）","req":"完成任务「神秘的齐」","cost":"","after":"老虎机、齐钻","checkKey":"quest_mysterious_qi"},
 {"id":"island","name":"姜岛（威利的船）","req":"社区中心修复 → 威利来信 → 修船","cost":"200 硬木 + 5 电池组 + 5 铱锭；船票 1,000g","after":"姜岛全境、鹦鹉特快","checkKey":"island_boat"},
 {"id":"island_trader","name":"姜岛商人","req":"解锁姜岛农舍 + 10 个金色核桃（砸开过金色椰子）","cost":"10 核桃","after":"每周轮换货（小帽子/蓝鸟面具等）","checkKey":"island_trader"},
 {"id":"horse","name":"马（马厩）","req":"木匠的商店建马厩","cost":"见建筑页马厩造价","after":"骑马、马笛","checkKey":"stable"},
 {"id":"bus_stop","name":"旅行货车","req":"无门槛，只在周五、周日 06:00–20:00 出现在煤矿森林","cost":"","after":"非当季商品、可补收集包","checkKey":"travelling_cart"},
 {"id":"buoys","name":"浮标样式全解锁","req":"每钓到 2 种新鱼解锁下一个","cost":"","after":"全部浮标（除默认外需 76 种鱼）","checkKey":"buoys_all"},
]

json.dump(smelting, open(os.path.join(OUT,'smelting.json'),'w',encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump(monsters, open(os.path.join(OUT,'monsterDrops.json'),'w',encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump(unlocks, open(os.path.join(OUT,'unlocks.json'),'w',encoding='utf-8'), ensure_ascii=False, indent=1)
print('smelting', len(smelting), '/ monsterDrops', len(monsters), '/ unlocks', len(unlocks))
