#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TVBox 点播源有效性验证工具

用法:
    python3 verify_video_source.py <输入>            # 输入可以是:
                                                     #   - 单个 URL
                                                     #   - 本地 txt（每行一个地址）
                                                     #   - 本地 JSON（video_source.json / urls 数组）
                                                     #   - 本地 m3u8 播放列表
    python3 verify_video_source.py <输入> -o 报告.json

检测内容:
    - HTTP 状态码 / Content-Type / 首包数据
    - JSON 是否可解析（对点播源 API）
    - 是否含 TVBox 可识别的影片列表字段（list / vod_list / data）
    - 返回影片条数
"""
import sys
import os
import re
import json
import argparse
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = {"User-Agent": "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36"}


def parse_inputs(path):
    """从任意输入里提取出 URL 列表"""
    urls = []
    if path.startswith("http://") or path.startswith("https://"):
        return [path]

    if not os.path.exists(path):
        print(f"[错误] 文件不存在: {path}")
        sys.exit(1)

    raw = open(path, encoding="utf-8", errors="ignore").read().strip()

    # JSON: 兼容 video_source.json 的 urls 数组 / 纯数组 / {"url":...}
    if raw.startswith("{") or raw.startswith("["):
        try:
            data = json.loads(raw)
            urls += _extract_urls_from_json(data)
        except Exception as e:
            print(f"[警告] JSON 解析失败: {e}")
    else:
        # txt / m3u8: 抓所有 http(s) 开头的行或片段
        for line in raw.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.search(r"https?://[^\s,，]+", line)
            if m:
                urls.append(m.group(0))

    # 去重保序
    seen, out = set(), []
    for u in urls:
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out


def _extract_urls_from_json(obj):
    found = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in ("url", "api") and isinstance(v, str) and v.startswith("http"):
                found.append(v)
            elif isinstance(v, (dict, list)):
                found += _extract_urls_from_json(v)
    elif isinstance(obj, list):
        for item in obj:
            if isinstance(item, str) and item.startswith("http"):
                found.append(item)
            else:
                found += _extract_urls_from_json(item)
    return found


def verify(url):
    result = {"url": url, "status": -1, "content_type": "", "reachable": False,
              "json_ok": False, "movie_count": None, "note": ""}
    try:
        r = requests.get(url, headers=UA, timeout=20, stream=True, allow_redirects=True)
        result["status"] = r.status_code
        result["content_type"] = r.headers.get("Content-Type", "")
        body = b""
        try:
            for chunk in r.iter_content(4096):
                body += chunk
                if len(body) > 2 * 1024 * 1024:  # 最多读 2MB
                    break
        except Exception:
            pass
        r.close()
        result["reachable"] = r.status_code in (200, 206) and len(body) > 0

        text = body.decode("utf-8", errors="ignore").strip()
        if text.startswith("{") or text.startswith("["):
            try:
                data = json.loads(text)
                result["json_ok"] = True
                for key in ("list", "vod_list", "data", "videos"):
                    arr = data.get(key) if isinstance(data, dict) else None
                    if isinstance(arr, list):
                        result["movie_count"] = len(arr)
                        break
            except Exception as e:
                result["note"] = f"JSON 解析失败: {str(e)[:50]}"
        elif "mpegurl" in result["content_type"] or "#EXTM3U" in text[:64]:
            result["note"] = "HLS 播放列表"
        elif "dash+xml" in result["content_type"] or "<MPD" in text[:256]:
            result["note"] = "DASH 清单"
        else:
            result["note"] = "非 JSON 响应（直链或其他）"
    except Exception as e:
        result["note"] = str(e)[:100]
    return result


def main():
    ap = argparse.ArgumentParser(description="TVBox 点播源有效性验证工具")
    ap.add_argument("input", help="单个 URL / 本地 txt / video_source.json / m3u8")
    ap.add_argument("-o", "--output", help="输出 JSON 报告路径")
    args = ap.parse_args()

    urls = parse_inputs(args.input)
    if not urls:
        print("[错误] 没有提取到任何 URL")
        sys.exit(1)

    print(f"共 {len(urls)} 个地址，开始验证...\n")
    results = []
    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = {ex.submit(verify, u): u for u in urls}
        for f in as_completed(futs):
            results.append(f.result())
    order = {u: i for i, u in enumerate(urls)}
    results.sort(key=lambda r: order.get(r["url"], 999))

    print(f"{'状态':<6} {'可达':<6} {'JSON':<6} {'影片数':<8} 地址")
    print("-" * 100)
    for r in results:
        status = str(r["status"])
        print(f"{status:<6} {str(r['reachable']):<6} {str(r['json_ok']):<6} "
              f"{str(r['movie_count'] or '-'):<8} {r['url']}")
        if r["note"]:
            print(f"       └ {r['note']}")

    ok = sum(1 for r in results if r["reachable"])
    print(f"\n可达: {ok}/{len(results)}")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print(f"报告已保存: {args.output}")


if __name__ == "__main__":
    main()
