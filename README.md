# Study

Study 是一个本地优先、可以持续更新的多模块学习应用。当前包含大学英语四级和按《x64 汇编语言：从新手到 AVX 专家》教材顺序整理的 43 章汇编课程。汇编课程的主题分类只用于搜索和筛选，不会把教材目录改成五个单元。网页/PWA 与 Android 应用共用 JSON 课程数据；Android 界面使用原生 Java View，学习进度保存在设备本地。

## 本地运行

需要 Node.js 18 或更高版本。

```powershell
npm.cmd run dev
```

打开 <http://127.0.0.1:4173/>。如果当前电脑限制了 PowerShell 脚本执行，可以使用 `npm.cmd run dev`。

## 构建和预览

```powershell
npm.cmd run validate
npm.cmd run build
npm.cmd run preview
```

构建结果在 `dist/`，GitHub Pages 工作流会自动构建并发布它。

## 构建 APK

Android 原生工程在 `android-app/`，会把词库、题目和课程 JSON 打包到 APK 内，断网也可以学习。界面和交互使用 Android 原生控件，不依赖 WebView。需要 Java 17 或更高版本、Android SDK 和 Gradle 9.1.0。

```powershell
npm.cmd run build:apk
```

APK 输出在 `android-app/app/build/outputs/apk/debug/Study.apk`。推送到 `main` 后，GitHub Actions 会检查内容、部署网页并生成保留 30 天的 CI artifact。正式分发的 APK 使用本机保存的稳定签名，手动附加到 GitHub Release，签名密钥不上传。仅在仓库已经配置稳定签名 Secret `STUDY_DEBUG_KEYSTORE_BASE64` 时，标签构建才自动发布 APK；没有该 Secret 时只生成 CI 构建包，不能用于覆盖已安装的正式分发版本。GitHub 提交历史保存源代码，Release 保存分发安装包。

手机端会在启动或回到前台时检查 APK 与课程更新，检查成功后六小时内不重复自动检查；顶部“更新”可以随时手动检查。有新 APK 时弹窗进入下载页，用户确认安装；当前不提供关闭 App 后的后台推送。课程和题库保存在应用私有目录，联网下载更新后可继续离线使用。

应用启动或重新切回前台时会自动检查 GitHub 上的新课程；顶部“更新”按钮也可以手动检查。新增课程时，将课程 JSON 放入 `data/modules/`、登记到 `data/modules/index.json`，并递增 `data/version.json` 的 `dataVersion`；提交并推送到 `main` 后，已安装应用会下载新课程并将其保存在设备，联网失败时仍可离线学习。仅课程数据更新不需要重新安装 APK；Android 原生界面或功能变化需要安装新版 APK。APK 从公开 GitHub 仓库下载更新数据。

## AI 接口与逐题讲解（Android v0.3.3）

在顶部「更多 → AI 接口设置」中填写 HTTPS 接口地址、模型 ID 和自己的 API Key，点击「测试连接」后保存。支持 OpenAI 兼容的 Chat Completions JSON 接口；可填写 Base URL（自动追加 /chat/completions）或完整接口地址。模型 ID 按服务商控制台填写，不依赖 App 预置的模型名单。仅有网页会员不等于有 API 额度。

题目提交后点击「AI 讲解 / 追问」，会带入当前文章、题目、词库/选项、参考答案及你的作答，可继续追问词汇、语法和错误原因，也能请求写作翻译修改建议。每页最多10轮，支持取消和失败重试。只有点击发送或测试连接才会请求服务；材料发送到你填写的服务商，费用由对应 API 账户承担。AI 回复不是官方解析，不会修改原题参考答案。

密钥使用 Android Keystore 的 AES-GCM 加密，存于应用私有的免备份目录，界面不回填明文密钥，可留空保留或明确清除；更改接口地址需重新填入密钥。密钥不随学习记录、GitHub 源码或 APK 分发。卸载或清除应用数据后需重新配置。此功能只接入原生 Android，当前讲解针对题目，不会自动读取 PDF。详细验证范围见 [AI 接入说明](docs/ai-tutor.md)。

## 四级真题阅读（Android v0.3.2 起）

进入「英语四级 → 真题」，可按套获取或一次获取全部8套阅读，共240道小题（选词填空80、段落匹配80、仔细阅读80），对应32篇完整来源文章。下载后使用原生界面离线练习；可选整套30题或单个题型10题，按原题号排列。首次下载需要访问来源网站，不是随 APK 内置240道正文。学习进度和错题仍保存在本机。

公开来源为南京大学 eol 网站的四级自测页，范围为2023年12月第1/3套、2024年6月第1/2/3套、2024年12月第1/2/3套。每题保留文章、词库或选项、来源及答案解析 PDF 入口。答案来自第三方整理参考版，不代表考试机构官方答案；当前未提供全部题目的独立逐题中文详解。验收与获取机制见 [阅读题库说明](docs/cet4-reading.md)。此下载练习功能当前仅接入原生 Android。

## 原书与重点讲解

汇编课程当前的43章是原创辅助笔记，不是教材全文。Android「原书阅读」支持从本机导入有权使用的完整 PDF，离线翻页、缩放、跳转、标记重点、编辑批注和恢复阅读位置。先校准正文第1页对应的 PDF 页数，再使用章节定位和章节讲解；目录页码以中文第一版为准。现有讲解为按章辅助内容，不会自动分析 PDF 每一页。原书不会随 APK 上传公开 GitHub；原书及批注只保存在设备本地，卸载后会删除。原书来源核对与验收限制见 [说明](docs/original-book-reader.md)。

## 如何更新内容

- 词汇：替换或重新生成 `data/vocab.json`。原始 PDF 解析脚本是 `scripts/extract_vocab.py`。
- 四级题目和真题：编辑 `data/questions.json`，新增 `mode: "real"` 的条目会进入真题中心。客观题填写 `answer` 与 `explanation`；写作、翻译等主观题提供参考答案或自评标准。
- 新增学习模块：在 `data/modules/` 下添加独立课程 JSON，再把 `id`、标题、图标、颜色和文件路径登记到 `data/modules/index.json`。章节按 `number` 对应教材顺序；主题只作为筛选标签。汇编课程的每章还需在 `lessonGuides` 中提供概念讲解、至少三步例题推演和两道带解析自练题，构建校验会检查章节覆盖；章节选择题使用 `lessonId` 关联章节。
- 版本：发布时更新 `data/version.json` 的 `version`、`dataVersion`、`updatedAt` 和 `changelog`，并递增 `android-app/app/build.gradle` 的 `versionCode`、更新 `versionName` 和 Service Worker 缓存名。
- 功能：页面结构在 `index.html`，交互逻辑在 `app.js`，样式在 `styles.css`。修改课程数据后先运行 `npm.cmd run validate`；构建脚本会自动校验题目答案、章节顺序和模块引用。
- 音频：后续可以在 `assets/audio/` 加入文件，并在题目数据中增加音频路径，再把播放逻辑接入 `app.js`。

## 发布到 GitHub

1. 更新版本号后用原有本机 keystore 构建 APK，并验证版本、签名与离线数据。不要提交签名密钥。
2. 推送 `main`，以对应提交创建版本标签，例如 `v0.3.0`。
3. 创建同版本 GitHub Release，上传本地构建并验证过的 `Study.apk`；仓库没有稳定签名 Secret 时使用此流程。
4. APK 工作流只在稳定签名 Secret 已配置时自动发布；网页独立通过 GitHub Pages 工作流部署。

## 目录

```text
index.html                 页面
app.js                     交互逻辑
styles.css                 样式
manifest.json              PWA 配置
service-worker.js          离线缓存与版本更新
data/vocab.json            4,449 条词汇
data/questions.json        题型、练习题和真题精选
data/modules/index.json   学习模块注册表
data/modules/*.json       独立课程与章节练习数据
data/version.json          应用与题库版本
scripts/extract_vocab.py   从 PDF 重新生成词库
scripts/validate-content.mjs  构建前检查模块、章节、答案和解析
scripts/build.mjs          发布构建
scripts/server.mjs         本地开发服务器
android-app/app/src/main/java/com/cet4/focus/  Android 原生界面与更新逻辑
```

## 数据来源

词汇来自本机 `D:\学习有关文件\大学英语四级词汇带音标-乱序版.pdf` 的解析结果。完整真题入口保留原始来源链接；发布前应确认题目和文章的使用授权。
