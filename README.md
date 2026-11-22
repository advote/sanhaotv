# 三好TV (sanhaotv)

基于开源 TVBox（q215613905/TVBoxOS）二次开发的 Android TV 播放器。

## 功能特性

- **直播源 + 视频源独立设置**：两个源地址分开配置、分开保存，均可手动修改。
- **云端源自动恢复**：应用启动时校验本地源地址是否可用；若失效（网络不可达或为空），自动从 GitHub 托管的 `sources.json` 拉取最新源地址写回本地。用户手动设置的可用源拥有最高优先级，不会被云端覆盖。
- **源地址切换历史**：点播历史、直播历史分开记录，各保留**最近 6 条**，可一键回退。
- **自定义图标**：电视 + 播放 + 信号弧主题 logo。
- **完整继承上游能力**：点播、直播、搜索、EPG、多播放器（IJK/Exo/MX）、投屏等。

## 项目结构

```
sanhaotv/
├── app/                      # 主应用（已定制）
├── player/ quickjs/ pyramid/ # 播放器与依赖模块
├── sources.json              # 云端源配置索引（放 GitHub 根，raw 链接给 App 拉取）
├── video_source.json         # 视频源示例（占位，请替换为真实源）
├── live_source.txt           # 直播源示例（占位，请替换为真实源，m3u 格式）
└── README.md
```

## 部署到 GitHub（重要）

1. 在 GitHub 新建**公开**仓库 `sanhaotv`。
2. 把本项目全部文件上传到该仓库 `main` 分支。
3. **替换占位符**：把所有文件中的 `advote` 替换为你的 GitHub 用户名，共两处：
   - `app/src/main/java/com/github/tvbox/osc/util/RemoteSourceHelper.java` 里的 `DEFAULT_CONFIG_URL`
   - `sources.json` 里的 `video` / `live` / `urls` / `live_urls` 字段
   
   替换后形如：
   `https://raw.githubusercontent.com/你的用户名/sanhaotv/main/sources.json`
4. 编辑 `video_source.json` 与 `live_source.txt`，填入你自己的真实源地址（示例中的 `example.com` 仅为占位，不可用）。

> 云端自动恢复原理：App 启动 → 探测本地 `API_URL` / `LIVE_API_URL` → 任一不可达时读取 `sources.json` → 取其中 `video` / `live` 地址写回本地。因此 `sources.json` 与它指向的源文件都必须能通过 raw 链接公网访问。

## 本地编译（Android Studio）

> 云端环境无 Android SDK，未做编译验证，请在本机用 Android Studio 打开编译。

1. 安装 Android Studio（Arctic Fox 及以上），安装 **Android SDK Platform 33**、**NDK**（r21e 及以上）、**JDK 8/11**。
2. Android Studio 打开项目根目录，`File → Sync Project with Gradle Files`。
3. 选择构建变体：
   - 仅 32 位电视：`java32Debug`
   - 仅 64 位电视：`java64Debug`
   - 双架构：`javaDebug`
   - 带 Python  spiders：`pythonDebug` 等
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在
   `app/build/outputs/apk/<flavor>/<debug|release>/`，文件名形如 `sanhaotv_debug-java64.apk`。
5. 首次安装需在电视/盒子上允许「未知来源」，并授予存储权限。

## 使用说明

1. 首次打开若未配置源，App 会自动从 GitHub 拉取 `sources.json` 里的源（需已替换 advote 且网络可达）。
2. 手动改源：设置页 → 配置地址 → 分别填写「点播」「直播」地址 → 确定。
3. 查看历史：设置页 → 配置地址 → 点击「点播历史」或「直播历史」，最多显示最近 6 条。
4. 云端配置地址可在 `RemoteSourceHelper.DEFAULT_CONFIG_URL` 修改，也可运行时写入 Hawk 键 `sanhao_cloud_config_url` 覆盖。

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

## 修改记录

- v1.0.0-sanhao：初始定制版本，新增云端源自动恢复、源历史 6 条、点播/直播历史入口、品牌图标与改名。
