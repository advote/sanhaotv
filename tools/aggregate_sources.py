#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TVBox 点播源「自助聚合验证」工具
================================================
作用：在当前运行环境的网络下，验证一批点播源地址是否真正能出影片列表，
      并把有效的自动聚合成可直接导入的 video_source.json。

注意：请在你【家里电视/电脑的同一网络】下运行本脚本。很多国内点播源按 IP
      地域放行，在海外/云端机房会被跳到落地页或拒绝，导致误判为失效。

用法:
  python3 aggregate_sources.py 源列表.txt              # txt: 每行一个地址，或 "名称,地址"
  python3 aggregate_sources.py video_source.json        # 兼容已有配置
  python3 aggregate_sources.py https://某个订阅地址
  python3 aggregate_sources.py 源列表.txt -o 我的点播源.json

输出:
  - 有效的 video_source.json（urls:[{url,name}] + vip 占位）
  - 终端打印验证报告（失效项标注原因）
"""
import sys
import os
import re
import json
import argparse
import requests
from urllib.parse import urlparse
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = {"User-Agent": "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36"}
TIMEOUT = 15


# ---------- 输入解析 ----------
def parse_inputs(path):
    if path.startswith("http://") or path.startswith("https://"):
        return [(path, "自定义源")]
    if not os.path.exists(path):
        print(f"[错误] 文件不存在: {path}")
        sys.exit(1)
    raw = open(path, encoding="utf-8", errors="ignore").read().strip().lstrip("\ufeff")
    items = []
    if raw.startswith("{") or raw.startswith("["):
        try:
            data = json.loads(raw)
            items = _from_json(data)
        except Exception as e:
            print(f"[警告] JSON 解析失败: {e}")
    if not items:
        for line in raw.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.search(r"https?://[^\s]+", line)
            if not m:
                continue
            url = m.group(0).rstrip(",，")
            name = line[:m.start()].strip(" ,，-—")
            items.append((url, name or _auto_name(url)))
    seen, out = set(), []
    for url, name in items:
        if url not in seen:
            seen.add(url)
            out.append((url, name))
    return out


def _from_json(obj):
    found = []
    if isinstance(obj, dict):
        arr = obj.get("urls")
        if isinstance(arr, list):
            for x in arr:
                if isinstance(x, dict) and x.get("url"):
                    found.append((x["url"], x.get("name") or _auto_name(x["url"])))
        elif obj.get("url"):
            found.append((obj["url"], obj.get("name") or _auto_name(obj["url"])))
    elif isinstance(obj, list):
        for x in obj:
            if isinstance(x, dict) and x.get("url"):
                found.append((x["url"], x.get("name") or _auto_name(x["url"])))
            elif isinstance(x, str) and x.startswith("http"):
                found.append((x, _auto_name(x)))
    return found


def _auto_name(url):
    host = urlparse(url).netloc
    return host or "源"


# ---------- 抓取与判定 ----------
def fetch(url, timeout=TIMEOUT, limit=3 * 1024 * 1024):
    r = requests.get(url, headers=UA, timeout=timeout, stream=True, allow_redirects=True)
    status, ctype = r.status_code, r.headers.get("Content-Type", "")
    body = b""
    try:
        for chunk in r.iter_content(4096):
            body += chunk
            if len(body) > limit:
                break
    except Exception:
        pass
    r.close()
    return status, ctype, body


def analyze(body):
    text = body.decode("utf-8", errors="ignore").strip().lstrip("\ufeff")
    low = text[:200].lower()
    # 反爬/落地页先行拦截
    landing_marks = ("<html", "<!doctype", "redirecting", "/lander", "baiduspider",
                     "just a moment", "captcha", "访问验证", "请输入验证码")
    if any(m in low for m in landing_marks):
        return "landing", "反爬/落地页"
    if "#extm3u" in low or "#extinf" in low:
        return "m3u8", "播放列表"
    try:
        data = json.loads(text)
    except Exception:
        return "unknown", "非JSON"
    if isinstance(data, dict):
        if isinstance(data.get("urls"), list):
            return "subscribe", f"二次订阅({len(data['urls'])}条)"
        for k in ("list", "vod_list", "data", "videos"):
            if isinstance(data.get(k), list):
                return "api", f"点播API({k}={len(data[k])}条)"
        return "json-other", "JSON无影片列表"
    if isinstance(data, list):
        return "json-list", f"JSON数组({len(data)}项)"
    return "unknown", "未知"


def verify(item):
    url, name = item
    res = {"url": url, "name": name, "kind": "", "note": "", "ok": False}
    try:
        status, ctype, body = fetch(url)
        if status not in (200, 206) or not body:
            res["note"] = f"HTTP {status}"
            return res
        kind, note = analyze(body)
        res["kind"], res["note"] = kind, note
        if kind in ("api", "json-list", "m3u8"):
            res["ok"] = True
        elif kind == "subscribe":
            # 展开子线路，只要有一条能出数据就算可用
            data = json.loads(body.decode("utf-8", errors="ignore").strip().lstrip("\ufeff"))
            subs = [x.get("url") for x in data["urls"]
                    if isinstance(x, dict) and x.get("url")][:20]
            def sub_ok(u):
                try:
                    s, c, b = fetch(u, timeout=12, limit=256 * 1024)
                    k, _ = analyze(b)
                    return s in (200, 206) and k in ("api", "json-list", "m3u8")
                except Exception:
                    return False
            with ThreadPoolExecutor(max_workers=8) as ex:
                futs = [ex.submit(sub_ok, u) for u in subs]
                okcnt = sum(1 for f in as_completed(futs) if f.result())
            res["note"] += f"；子线路可用 {okcnt}/{len(subs)}"
            res["ok"] = okcnt > 0
    except Exception as e:
        res["note"] = "ERR " + str(e)[:60]
    return res


# ---------- 主流程 ----------
def main():
    ap = argparse.ArgumentParser(description="TVBox 点播源自助聚合验证工具")
    ap.add_argument("input", help="源列表txt / video_source.json / 单个URL")
    ap.add_argument("-o", "--output", default="video_source.json", help="输出文件名")
    args = ap.parse_args()

    items = parse_inputs(args.input)
    if not items:
        print("[错误] 没有提取到任何地址")
        sys.exit(1)
    print(f"共 {len(items)} 个地址，在当前网络下验证...\n")

    results = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        futs = {ex.submit(verify, it): it for it in items}
        for f in as_completed(futs):
            results.append(f.result())
    order = {u: i for i, (u, n) in enumerate(items)}
    results.sort(key=lambda r: order.get(r["url"], 999))

    print(f"{'有效':<6} {'类型':<12} {'名称':<26} 说明 / 地址")
    print("-" * 110)
    for r in results:
        print(f"{str(r['ok']):<6} {r['kind']:<12} {r['name']:<26} {r['note']}")
        print(f"       {r['url']}")

    good = [r for r in results if r["ok"]]
    out = {
        "urls": [{"url": r["url"], "name": r["name"]} for r in good],
        "vip": [{"url": "", "name": "默认"}],
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n有效 {len(good)}/{len(results)}，已写入: {args.output}")


if __name__ == "__main__":
    main()
