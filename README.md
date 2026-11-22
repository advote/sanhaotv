# 三好TV (sanhaotv)


## 视频源配置指南

> 本项目不包含、也不代为搜集任何第三方影视采集站接口。请只接入**你拥有合法使用权或版权方公开授权**的视频源，勿用于侵权用途。

### 配置结构

`video_source.json` 是「多仓索引」，顶层 `urls` 数组中的每一项指向一份**单仓配置**的地址（raw 链接），App 取第一项作为默认视频源：

```json
{
  "urls": [
    { "name": "我的视频源", "url": "https://raw.githubusercontent.com/advote/sanhaotv/main/my_source.json" }
  ],
  "spider": "",
  "parse": []
}
```

单仓配置（`my_source.json`）的标准结构：

```json
{
  "sites": [
    {
      "key": "my_site_1",
      "name": "我的影视库",
      "type": 3,
      "api": "https://你的域名/api.php/provide/vod/",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1
    }
  ],
  "spider": "",
  "parses": []
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `key` | 站点唯一标识，不可重复 |
| `name` | 界面显示名称 |
| `type` | 源类型：`3`=JSON API（苹果CMS 等），`1`=XML，`0`=其他 |
| `api` | 点播接口地址 |
| `searchable` / `quickSearch` / `filterable` | 是否支持搜索/快搜/筛选，`1` 或 `0` |
| `spider` | 自定义爬虫 JS/jar 地址，普通 CMS 源留空即可 |

### 合法的源从哪来

1. **自建媒体库**：用 Jellyfin / Emplex / 苹果CMS 等搭建，管理你自己的正版媒体文件，生成 API 接入。
2. **版权方公开 API**：部分平台/机构提供公开授权的视频接口，按其文档接入。
3. **本地文件**：App 支持本地目录播放（`LocalFileActivity`），可直接看存于电视/U 盘的视频。

### 播放器功能自测（公开测试流）

如果你只是想验证点播/解码功能是否正常，可用下面这些**版权方明确公开供测试**的视频流地址，在 App 的播放器中直接打开测试（它们不是"影视资源"，也不作为点播源 `sites` 使用）：

| 素材 | 地址 | 来源 |
|---|---|---|
| Big Buck Bunny | `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` | Mux 官方测试流 |
| Apple BipBop | `https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8` | Apple 开发者示例 |
| Tears of Steel | `https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8` | Unified Streaming / Blender 开源电影 |
| Sintel 预告片 | `https://media.w3.org/2010/05/sintel/trailer.mp4` | W3C 公开测试素材 |
| Flower (CC0) | `https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4` | MDN 公开示例 |

### JSON 校验

编辑配置后建议用在线 JSON 校验工具（如 jsonlint.com）检查语法，多余逗号或缺失引号都会导致整个配置解析失败。

## 致谢与许可

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 二次开发，遵循上游开源许可。本项目仅供技术学习与个人合法使用，请遵守当地法律法规，勿用于任何侵权或非法用途。

