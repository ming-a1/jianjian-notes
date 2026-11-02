# 简笺 · Markdown 笔记（Android · Kotlin 原生）

本地优先的个人笔记 App：完整 Markdown 编辑与预览、日夜主题、单篇「渲染查看」开关、
WebDAV 备份（定时 / 实时 / 增量 / 加密 / 保留 7 份）、分享为图片、检查更新。

**界面全部是原生 View（Kotlin），没有 WebView。**

## 构建

需要 JDK 17、Android SDK（platform 34 / build-tools 34.0.0）、Kotlin 编译器 2.0.x、aapt2/d8/apksigner。
本项目不使用 Gradle，直接脚本构建：

```bash
# 1) 准备你自己的签名（本仓库不含任何签名文件）
mkdir -p signing
# 把你的 keystore 放进来，并准备两个口令文件（各一行）
# signing/release.p12 / signing/ks.pass / signing/key.pass

# 2) 编译
./build-native.sh      # 输出 dist/jianjian-<版本>.apk
```

脚本里的密钥库路径、别名、口令文件都可以按自己的签名调整。

## 目录

```
app/
├── AndroidManifest.xml
├── res/                       图标、颜色、主题
└── src/com/heyu/notes/
    ├── MainActivity.kt        列表 / 编辑 / 预览 / 菜单
    ├── SettingsDialog.kt      设置弹窗（外观 / 备份 / 关于）
    ├── Widgets.kt             自绘控件：Field / Toggle / Select / DialogCard / MenuPopup / Snack / 分享预览
    ├── Markdown.kt            Markdown 块解析 + Spannable 渲染
    ├── Preview.kt             预览页原生视图
    ├── ShareImage.kt          Canvas 绘制分享长图
    ├── Theme.kt / Ui.kt       配色与控件工具
    ├── Model.kt               笔记模型与仓库
    ├── AppPrefs.kt            设置读写
    ├── CryptoBox.java         PBKDF2 + AES-256-GCM（备份加密）
    ├── SecureStore.java       备份密码（Android Keystore）
    ├── NoteStore.java         笔记 JSON 文件存储
    ├── BackupEngine.java      备份编排（本地 + WebDAV + 留存）
    ├── BackupPlanner.java     增量 / 完整 / 留存决策
    ├── WebDavClient.java      WebDAV 客户端
    ├── DavHttp.java           Socket 级 HTTP（支持 PROPFIND）
    ├── BackupScheduler.java   定时备份排程
    ├── BackupReceiver.java    定时触发
    ├── ShareProvider.java     分享图片用只读 ContentProvider
    └── Json.java              轻量 JSON
```

## 备份与加密

- 文件：`notes-YYYYMMDD-HHmmss-full|inc.hnb`，加密后 `.hnbe`
- 加密：`HNB1` 头 + PBKDF2-HMAC-SHA256(200000) + AES-256-GCM，AAD 为 40 字节头部
- 增量：首份全量，之后只含变更笔记与删除记录；每 `keep-1` 次或 7 天转全量
- 留存：默认 7 份，超出自动清理（本地与 WebDAV）
- 「立即备份」只上传 WebDAV，不在本机留副本；自动备份本机 + WebDAV 各一份

## 检查更新

App 设置 → 关于 → 检查更新，读取仓库里的 `version.json`：

- 主地址：`https://api.github.com/repos/ming-a1/jianjian-notes/contents/version.json`（GitHub Contents API，国内可直连、无 CDN 缓存）
- 镜像：`https://cdn.jsdelivr.net/gh/ming-a1/jianjian-notes@main/version.json`

```json
{"version":"2.0.10","code":15,"url":"https://cdn.jsdelivr.net/gh/ming-a1/jianjian-notes@main/apk/jianjian-2.0.10.apk","mirror":"…/releases/download/v2.0.10/jianjian-2.0.10.apk","notes":"更新说明"}
```

`code` 与 APK 的 versionCode 比较，大于当前值即提示更新；下载地址优先用 jsDelivr（国内速度快），
GitHub Release 作为备用。

发版流程：改版本号 → `./build-native.sh` → 提交 `apk/jianjian-<版本>.apk` → 建 Release 并上传 APK →
更新 `version.json` 的 version/code/url。
