# Study

Study 是一个本地优先、可以持续更新的多模块学习应用。当前包含大学英语四级和按《x64 汇编语言：从新手到 AVX 专家》教材顺序整理的 43 章汇编课程。汇编课程的主题分类只用于搜索和筛选，不会把教材目录改成五个单元。网页/PWA 与 Android APK 共用内容和交互代码，学习进度保存在设备本地。

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

Android 工程在 `android-app/`，会把网页内容打包到 APK 内，断网也可以使用已打包的词库和题目。需要 Java 17 或更高版本、Android SDK 和 Gradle 9.1.0。

```powershell
npm.cmd run build:apk
```

APK 输出在 `android-app/app/build/outputs/apk/debug/Study.apk`。本地构建需要 Java 17+、Android SDK 和 Gradle 9.1.0。推送到 `main` 后，GitHub Actions 会检查内容、部署网页并生成保留 30 天的构建 artifact。发布 `v*` 标签会构建同签名 APK 并附加到 GitHub Release，手机端会在启动和回到前台时检查最新 Release，有新版本时显示下载入口。Release 签名使用 `.android-user/debug.keystore`；首次发布前，将该文件的 Base64 内容保存为仓库 Actions Secret `STUDY_DEBUG_KEYSTORE_BASE64`，不要把密钥提交到仓库。GitHub 提交历史保存已提交的源代码版本；Actions artifact 到期后会删除。

应用启动或重新切回前台时会自动检查 GitHub 上的新课程，首页刷新按钮也可以手动检查。新增课程时，将课程 JSON 放入 `data/modules/`、登记到 `data/modules/index.json`，并递增 `data/version.json` 的 `dataVersion`；提交并推送到 `main` 后，已安装应用会下载新课程并将其保存在设备，联网失败时仍可离线学习。仅课程数据更新不需要重新安装 APK；网页代码或应用功能变化仍需重新部署网页或安装新版 APK。APK 从公开 GitHub 仓库下载更新数据。

## 如何更新内容

- 词汇：替换或重新生成 `data/vocab.json`。原始 PDF 解析脚本是 `scripts/extract_vocab.py`。
- 四级题目和真题：编辑 `data/questions.json`，新增 `mode: "real"` 的条目会进入真题中心。客观题填写 `answer` 与 `explanation`；写作、翻译等主观题提供参考答案或自评标准。
- 新增学习模块：在 `data/modules/` 下添加独立课程 JSON，再把 `id`、标题、图标、颜色和文件路径登记到 `data/modules/index.json`。章节按 `number` 对应教材顺序；主题只作为筛选标签。汇编课程的每章还需在 `lessonGuides` 中提供概念讲解、至少三步例题推演和两道带解析自练题，构建校验会检查章节覆盖；章节选择题使用 `lessonId` 关联章节。
- 版本：发布时更新 `data/version.json` 的 `version`、`dataVersion`、`updatedAt` 和 `changelog`，并递增 `android-app/app/build.gradle` 的 `versionCode`、更新 `versionName` 和 Service Worker 缓存名。
- 功能：页面结构在 `index.html`，交互逻辑在 `app.js`，样式在 `styles.css`。修改课程数据后先运行 `npm.cmd run validate`；构建脚本会自动校验题目答案、章节顺序和模块引用。
- 音频：后续可以在 `assets/audio/` 加入文件，并在题目数据中增加音频路径，再把播放逻辑接入 `app.js`。

## 发布到 GitHub

1. 将 `.android-user/debug.keystore` 转成 Base64，并保存为仓库 **Settings → Secrets and variables → Actions** 下的 `STUDY_DEBUG_KEYSTORE_BASE64` secret。
2. 更新版本号后推送 `main`，再推送对应的 `v*` 标签，例如 `v0.2.6`。
3. GitHub Pages 工作流自动部署网页；APK 工作流会把签名 APK 附加到同版本 GitHub Release。
4. 首次启用网页发布时，在仓库的 **Settings → Pages** 中将部署来源设为 **GitHub Actions**。

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
```

## 数据来源

词汇来自本机 `D:\学习有关文件\大学英语四级词汇带音标-乱序版.pdf` 的解析结果。完整真题入口保留原始来源链接；发布前应确认题目和文章的使用授权。
