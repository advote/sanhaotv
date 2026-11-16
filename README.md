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

## 致谢与许可

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 二次开发，遵循上游开源许可。本项目仅供技术学习与个人合法使用，请遵守当地法律法规，勿用于任何侵权或非法用途。

## 修改记录

- v1.0.0-sanhao：初始定制版本，新增云端源自动恢复、源历史 6 条、点播/直播历史入口、品牌图标与改名。
