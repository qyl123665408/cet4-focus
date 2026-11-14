# 四级 Focus

四级 Focus 是一个本地优先、可以持续更新的大学英语四级学习工具。它包含四级全部主要题型、词汇库、真题精选、错题记录和学习统计。项目采用原生 HTML/CSS/JavaScript 作为内容层，同时提供 Android APK 壳，部署到 GitHub Pages 后可以直接在浏览器使用，也可以构建 APK。

## 本地运行

需要 Node.js 18 或更高版本。

```powershell
npm run dev
```

打开 <http://127.0.0.1:4173/>。如果当前电脑限制了 PowerShell 脚本执行，可以使用 `npm.cmd run dev`。

## 构建和预览

```powershell
npm run build
npm run preview
```

构建结果在 `dist/`，GitHub Pages 工作流会自动构建并发布它。

## 构建 APK

Android 工程在 `android-app/`，会把网页内容打包到 APK 内，断网也可以使用已打包的词库和题目。需要 Java 17 或更高版本、Android SDK 和 Gradle 9.1.0。

```powershell
npm run build:apk
```

APK 输出在 `android-app/app/build/outputs/apk/debug/app-debug.apk`。以后修改网页、词库或题库后，先运行 `npm run sync:android`，再构建 APK；GitHub Actions 会在推送到 `main` 后自动生成可下载的 APK artifact。

## 如何更新内容

- 词汇：替换或重新生成 `data/vocab.json`。原始 PDF 解析脚本是 `scripts/extract_vocab.py`。
- 题目和真题：编辑 `data/questions.json`，保持现有字段结构；新增 `mode: "real"` 的条目会进入真题中心。
- 版本：每次发布更新 `data/version.json` 的 `version`、`dataVersion`、`updatedAt` 和 `changelog`。
- 功能：页面结构在 `index.html`，交互逻辑在 `app.js`，样式在 `styles.css`。功能增多后，可继续把 `app.js` 拆分到 `src/modules/`。
- 音频：后续可以在 `assets/audio/` 加入文件，并在题目数据中增加音频路径，再把播放逻辑接入 `app.js`。

## 发布到 GitHub

1. 在 GitHub 新建一个空仓库，例如 `cet4-focus`。
2. 在本目录执行：

```powershell
git init
git add .
git commit -m "feat: initial CET-4 study app"
git branch -M main
git remote add origin https://github.com/<你的用户名>/cet4-focus.git
git push -u origin main
```

3. 在仓库的 **Settings → Pages** 中，将部署来源设为 **GitHub Actions**。
4. 之后每次推送到 `main`，`.github/workflows/deploy-pages.yml` 会自动构建并发布新版本。

## 目录

```text
index.html                 页面
app.js                     交互逻辑
styles.css                 样式
manifest.json              PWA 配置
service-worker.js          离线缓存与版本更新
data/vocab.json            4,449 条词汇
data/questions.json        题型、练习题和真题精选
data/version.json          应用与题库版本
scripts/extract_vocab.py   从 PDF 重新生成词库
scripts/build.mjs          发布构建
scripts/server.mjs         本地开发服务器
```

## 数据来源

词汇来自本机 `D:\学习有关文件\大学英语四级词汇带音标-乱序版.pdf` 的解析结果。完整真题入口保留原始来源链接；发布前应确认题目和文章的使用授权。
