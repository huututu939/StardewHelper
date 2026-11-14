# -*- coding: utf-8 -*-
"""fetch_verified 的行为证明：HTTP 200 但内容不对时，**不能**当成功。

c96 踩的坑就是把「HTTP 200」当成重试的终止条件，于是 gitee raw 缓存里那份
上一版的 version.json 被判成了新发布的清单——脚本当场报绿，远端其实没同步完。
这里用一个永远返回 200、但内容永远是「旧版」的假服务器来钉住正确行为。
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
import smoke_publish as sp   # noqa: E402

calls = []


def fake_get(url, tries=3, gap=3.0):
    calls.append(url)
    return b'{"versionCode": 96, "versionName": "2.61.0"}', 200   # 永远是上一版


sp.http_get = fake_get
sp.time.sleep = lambda s: None

fails = []

# 场景 1：内容永不对 -> 必须把 tries 用满，绝不能第 1 次就返回成功
body, st = sp.fetch_verified("x", lambda b: b'"versionCode": 97' in b,
                             tries=4, gap=0, what="t")
if len(calls) != 4:
    fails.append("HTTP 200 但内容过期时，只请求了 %d 次（应为 4）—— 等于把旧内容当成了真相" % len(calls))
else:
    print("PASS HTTP 200 + 内容过期 -> 重试满 4 次后才放弃")

# 场景 2：第 3 次才拿到对的内容 -> 必须提前收敛，且返回的是对的那份
calls[:] = []


def flaky_get(url, tries=3, gap=3.0):
    calls.append(url)
    if len(calls) < 3:
        return b'{"versionCode": 96}', 200          # 前两次都是缓存里的旧版
    return b'{"versionCode": 97}', 200


sp.http_get = flaky_get
body, st = sp.fetch_verified("x", lambda b: b'"versionCode": 97' in b,
                             tries=6, gap=0, what="t")
if b'97' not in body or len(calls) != 3:
    fails.append("第 3 次才对时应返回第 3 次那份内容（calls=%d）" % len(calls))
else:
    print("PASS 前两次旧版、第 3 次新版 -> 收敛于第 3 次并返回新内容")

# 场景 3：404 是确定答案，不该浪费重试
calls[:] = []


def gone(url, tries=3, gap=3.0):
    calls.append(url)
    return b"", 404


sp.http_get = gone
body, st = sp.fetch_verified("x", lambda b: True, tries=5, gap=0, what="t")
if st != 404:
    fails.append("404 未被原样传出")
else:
    print("PASS 404 原样传出（确定性答案不做无谓重试）")

# 场景 4（v2.68.0）：重试**耗尽**时返回的仍是那份过期内容。
#   这条不是多余的——fetch_verified 只负责「重试到内容对为止」，
#   它耗尽后照样返回 (旧内容, 200)。调用方若写成 `if body: 判成功`，
#   就等于把过期缓存当成刚发布的那一版（① 曾经的假绿正是这个形状）。
#   所以「拿到东西」≠「拿到对的东西」，判据必须由调用方自己再跑一遍 validate。
calls[:] = []
sp.http_get = fake_get          # ⚠ 必须换回「永远 200 但内容过期」那个（上一个假服务器回 404）
body, st = sp.fetch_verified("x", lambda b: b'"versionCode": 97' in b,
                             tries=3, gap=0, what="t")
if st != 200 or b'"versionCode": 96' not in body:
    fails.append("耗尽后的返回值形态变了（应原样传出最后一份旧内容，好让调用方自己判）")
elif b'"versionCode": 97' in body:
    fails.append("耗尽后竟返回了对的内容——validate 逻辑有问题")
else:
    print("PASS 重试耗尽 -> 仍返回过期那份，调用方必须自己判（不能「拿到就打勾」）")

print()
if fails:
    print("FAIL " + "；".join(fails))
    sys.exit(1)
print("PASS fetch_verified 四项行为均正确")
