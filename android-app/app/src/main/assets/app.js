const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const STORAGE_KEY = 'cet4-focus-state-v1';
const CONTENT_DB_NAME = 'study-content-updates';
const CONTENT_DB_VERSION = 1;
const REMOTE_CONTENT_BASE = 'https://raw.githubusercontent.com/qyl123665408/cet4-focus/main/';
const LATEST_RELEASE_API = 'https://api.github.com/repos/qyl123665408/cet4-focus/releases/latest';
const APK_UPDATE_CHECK_KEY = 'study-apk-update-checked-at';
const CORE_CONTENT_PATHS = ['data/questions.json', 'data/vocab.json', 'data/version.json', 'data/modules/index.json'];
const state = { tab: 'modules', mode: 'practice', selectedTypes: [], vocabFilter: 'all', vocabPage: 1, vocabSearch: '', vocabStatus: {}, moduleProgress: {}, activeModuleId: '', courseLessonId: '', courseSearch: '', courseTopic: 'all', total: 0, correct: 0, streak: 0, lastStudy: '', wrong: [], history: [], session: null };
let vocab = [];
let questionData = { types: [], items: [], examLinks: [] };
let moduleRegistry = [];
let courseModules = {};
let courseSession = null;
let versionInfo = { version: 'dev', dataVersion: '' };
let serviceWorkerRegistration = null;
let contentUpdateInProgress = false;

const saveState = () => localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...state, session: null }));
function loadState() { try { Object.assign(state, JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')); } catch (_) {} }
const escapeHtml = (value = '') => String(value).replace(/[&<>'\"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '\"': '&quot;' }[char]));
const todayKey = () => new Date().toISOString().slice(0, 10);
function toast(message) { const node = $('#toast'); node.textContent = message; node.classList.add('show'); clearTimeout(toast.timer); toast.timer = setTimeout(() => node.classList.remove('show'), 2200); }
function speak(text, lang = 'en-US') { if (!('speechSynthesis' in window)) return toast('当前浏览器不支持朗读'); speechSynthesis.cancel(); const utterance = new SpeechSynthesisUtterance(text); utterance.lang = lang; utterance.rate = 0.9; speechSynthesis.speak(utterance); }

function showUpdateNotice(message = '发现网页更新', type = 'web') {
  const notice = $('#updateNotice');
  if (!notice) return;
  $('#updateNoticeMessage').textContent = message;
  const button = $('#applyUpdate');
  button.textContent = type === 'apk' ? '前往下载' : '重新加载';
  button.dataset.updateType = type;
  notice.classList.remove('hidden');
}

async function checkForAppUpdates() {
  const bridge = window.StudyBridge;
  if (!bridge?.getInstalledVersion || !bridge?.openLatestRelease) return;
  const installedVersion = bridge.getInstalledVersion();
  if (!/^\d+(?:\.\d+)*$/.test(installedVersion || '')) return;
  const lastCheck = Number(localStorage.getItem(APK_UPDATE_CHECK_KEY) || 0);
  if (Date.now() - lastCheck < 6 * 60 * 60 * 1000) return;
  localStorage.setItem(APK_UPDATE_CHECK_KEY, String(Date.now()));
  try {
    const response = await fetch(LATEST_RELEASE_API, {
      headers: { Accept: 'application/vnd.github+json' },
      cache: 'no-store'
    });
    if (!response.ok) return;
    const release = await response.json();
    const latestVersion = String(release.tag_name || '').replace(/^v/i, '');
    if (/^\d+(?:\.\d+)*$/.test(latestVersion) && compareDottedVersions(latestVersion, installedVersion) > 0) {
      showUpdateNotice('Study ' + latestVersion + ' APK 已发布（当前 ' + installedVersion + '）', 'apk');
    }
  } catch (error) {
    console.warn('APK update check failed', error);
  }
}

async function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || location.protocol === 'file:') return;
  try {
    serviceWorkerRegistration = await navigator.serviceWorker.register('./service-worker.js');
    serviceWorkerRegistration.addEventListener('updatefound', () => {
      const worker = serviceWorkerRegistration.installing;
      worker?.addEventListener('statechange', () => {
        if (worker.state === 'installed' && navigator.serviceWorker.controller) showUpdateNotice();
      });
    });
    window.setTimeout(() => serviceWorkerRegistration?.update(), 1500);
  } catch (error) {
    console.warn('Service worker registration failed', error);
  }
}

function setupVersion(version) {
  versionInfo = version || versionInfo;
  const label = $('#appVersion');
  if (label) label.textContent = '版本 ' + (versionInfo.version || 'dev') + (versionInfo.dataVersion ? ' · 题库 ' + versionInfo.dataVersion : '');
}

function safeContentPath(path) {
  return typeof path === 'string' && path.startsWith('data/') && !path.includes(String.fromCharCode(92))
    && path.split('/').every((part) => part && part !== '.' && part !== '..');
}

function validateContentAssets(assets) {
  const questions = assets['data/questions.json'];
  const words = assets['data/vocab.json'];
  const version = assets['data/version.json'];
  const registry = assets['data/modules/index.json'];
  if (!Array.isArray(questions?.types) || !Array.isArray(questions?.items) || !Array.isArray(words)
      || !/^\d+(?:\.\d+)*$/.test(version?.version || '') || !/^\d+(?:\.\d+)*$/.test(version?.dataVersion || '')
      || !Array.isArray(registry?.modules)) {
    throw new Error('Downloaded content is missing required data.');
  }
  const ids = new Set();
  const courses = {};
  for (const module of registry.modules) {
    if (!module.id || ids.has(module.id) || !module.title || !['native', 'course'].includes(module.kind)) {
      throw new Error('Downloaded module directory is invalid.');
    }
    ids.add(module.id);
    if (module.kind !== 'course') continue;
    if (!safeContentPath(module.file) || !module.file.startsWith('data/modules/')) {
      throw new Error('Downloaded course path is invalid.');
    }
    const course = assets[module.file];
    if (course?.id !== module.id || !Array.isArray(course.lessons) || !Array.isArray(course.questions)) {
      throw new Error('Downloaded course data is incomplete: ' + module.id);
    }
    if (module.id === 'x64-assembly') {
      const guides = course.lessonGuides || {};
      if (Object.keys(guides).length !== course.lessons.length || course.lessons.some((lesson) => {
        const guide = guides[lesson.id];
        return !Array.isArray(guide?.explain) || guide.explain.length < 2
          || !Array.isArray(guide?.steps) || guide.steps.length < 3
          || !Array.isArray(guide?.practice) || guide.practice.length < 2;
      })) throw new Error('Downloaded assembly lessons are incomplete.');
    }
    courses[module.id] = course;
  }
  return { questions, words, version, registry: registry.modules, courses };
}

function compareDottedVersions(left, right) {
  const a = String(left || '').split('.').map(Number);
  const b = String(right || '').split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const difference = (a[i] || 0) - (b[i] || 0);
    if (difference) return Math.sign(difference);
  }
  return 0;
}

function compareContentVersions(left, right) {
  return compareDottedVersions(left?.version, right?.version)
    || compareDottedVersions(left?.dataVersion, right?.dataVersion);
}

async function readLocalJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error('Cannot load local data: ' + path);
  return response.json();
}

async function readJsonFromRemote(path) {
  if (!safeContentPath(path)) throw new Error('Invalid update path.');
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(new URL(path.split('/').map(encodeURIComponent).join('/'), REMOTE_CONTENT_BASE), {
      cache: 'no-store', signal: controller.signal
    });
    if (!response.ok) throw new Error('Update source returned HTTP ' + response.status + '.');
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

async function collectLocalSnapshot() {
  const [questions, words, version, registry] = await Promise.all(CORE_CONTENT_PATHS.map(readLocalJson));
  const values = [questions, words, version, registry];
  const assets = Object.fromEntries(CORE_CONTENT_PATHS.map((path, index) => [path, values[index]]));
  await Promise.all((registry.modules || []).filter((module) => module.kind === 'course').map(async (module) => {
    if (!safeContentPath(module.file)) throw new Error('Invalid bundled course path: ' + module.id);
    assets[module.file] = await readLocalJson(module.file);
  }));
  validateContentAssets(assets);
  return assets;
}

function openContentDatabase() {
  return new Promise((resolve, reject) => {
    if (!('indexedDB' in window)) return reject(new Error('Offline storage is unavailable.'));
    const request = indexedDB.open(CONTENT_DB_NAME, CONTENT_DB_VERSION);
    request.onupgradeneeded = () => request.result.createObjectStore('snapshots', { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Could not open offline storage.'));
  });
}

async function readSavedSnapshot() {
  const database = await openContentDatabase();
  try {
    return await new Promise((resolve, reject) => {
      const request = database.transaction('snapshots', 'readonly').objectStore('snapshots').get('latest');
      request.onsuccess = () => resolve(request.result?.assets || null);
      request.onerror = () => reject(request.error || new Error('Could not read offline content.'));
    });
  } finally {
    database.close();
  }
}

async function saveSnapshot(assets) {
  const database = await openContentDatabase();
  try {
    await new Promise((resolve, reject) => {
      const transaction = database.transaction('snapshots', 'readwrite');
      transaction.objectStore('snapshots').put({ id: 'latest', assets, savedAt: Date.now() });
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error('Could not save offline content.'));
      transaction.onabort = () => reject(transaction.error || new Error('Offline content save was cancelled.'));
    });
  } finally {
    database.close();
  }
}

function applyContentSnapshot(assets) {
  const content = validateContentAssets(assets);
  questionData = content.questions;
  vocab = content.words;
  versionInfo = content.version;
  moduleRegistry = content.registry;
  courseModules = content.courses;
  setupVersion(versionInfo);
}

async function checkForContentUpdates({ silent = false } = {}) {
  if (contentUpdateInProgress) return;
  if (silent && Date.now() - (checkForContentUpdates.lastCheck || 0) < 15 * 60 * 1000) return;
  checkForContentUpdates.lastCheck = Date.now();
  const button = $('#checkUpdates');
  contentUpdateInProgress = true;
  if (button) {
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
  }
  try {
    const remoteVersion = await readJsonFromRemote('data/version.json');
    if (compareContentVersions(remoteVersion, versionInfo) <= 0) {
      if (!silent) toast('已经是最新课程 · ' + versionInfo.version);
      return;
    }
    const remoteRegistry = await readJsonFromRemote('data/modules/index.json');
    if (!Array.isArray(remoteRegistry.modules)) throw new Error('The remote module directory is invalid.');
    const paths = [...CORE_CONTENT_PATHS.filter((path) => path !== 'data/version.json'), ...remoteRegistry.modules
      .filter((module) => module.kind === 'course').map((module) => module.file)];
    const values = await Promise.all(paths.map(readJsonFromRemote));
    const assets = Object.fromEntries(paths.map((path, index) => [path, values[index]]));
    assets['data/version.json'] = remoteVersion;
    validateContentAssets(assets);
    await saveSnapshot(assets);
    applyContentSnapshot(assets);
    renderAll();
    toast('已更新到 ' + moduleRegistry.length + ' 个学习模块');
  } catch (error) {
    console.warn('Course update check failed', error);
    if (!silent) toast('暂时无法联网，已下载的课程仍可离线使用');
  } finally {
    contentUpdateInProgress = false;
    if (button) {
      button.disabled = false;
      button.removeAttribute('aria-busy');
    }
  }
}

async function boot() {
  loadState();
  if (state.tab === 'home') state.tab = 'modules';
  const [bundledResult, savedResult] = await Promise.allSettled([collectLocalSnapshot(), readSavedSnapshot()]);
  let bundled = bundledResult.status === 'fulfilled' ? bundledResult.value : null;
  let saved = savedResult.status === 'fulfilled' && savedResult.value ? savedResult.value : null;
  if (bundled) validateContentAssets(bundled);
  if (saved) {
    try { validateContentAssets(saved); } catch (error) { console.warn('Ignoring invalid saved course data', error); saved = null; }
  }
  if (!bundled && !saved) throw bundledResult.reason || new Error('No offline course data is available.');
  const assets = saved && (!bundled || compareContentVersions(saved['data/version.json'], bundled['data/version.json']) > 0)
    ? saved : bundled;
  applyContentSnapshot(assets);
  if (assets === bundled) saveSnapshot(bundled).catch((error) => console.warn('Could not save bundled offline content', error));
  state.moduleProgress ||= {};
  if (!state.selectedTypes.length) state.selectedTypes = questionData.types.map((item) => item.id);
  renderAll();
  registerServiceWorker();
  window.setTimeout(checkForAppUpdates, 1800);
  window.setTimeout(() => checkForContentUpdates({ silent: true }), 1500);
  window.addEventListener('studyappresume', checkForAppUpdates);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      checkForContentUpdates({ silent: true });
      checkForAppUpdates();
    }
  });
}

function renderAll() { renderNav(); renderModules(); renderCourse(); renderStructure(); renderTypePicker(); renderExamLinks(); renderRealList(); renderVocab(); renderStats(); renderRecent(); switchTab(state.tab || 'modules'); }
function renderNav() {
  $$('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.tab === (state.tab === 'course' ? 'modules' : state.tab)));
  const titles = { modules: '学习模块', course: courseModules[state.activeModuleId]?.title || '课程学习', home: '今天，先完成一小组', practice: '按题型练，按弱项补', real: '真题中心', vocab: '四级词汇库' };
  $('#pageTitle').textContent = titles[state.tab] || titles.modules;
}
function switchTab(tab) { state.tab = tab; $$('.tab-panel').forEach((panel) => panel.classList.toggle('active', panel.id === 'tab-' + tab)); renderNav(); saveState(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
function typeLabel(type) { const item = questionData.types.find((entry) => entry.id === type); return item ? item.label : type; }

function renderModules() {
  const grid = $('#moduleGrid');
  if (!grid) return;
  $('#moduleCount').textContent = moduleRegistry.length + ' 个模块';
  if (!moduleRegistry.length) {
    grid.innerHTML = '<p class="empty-state">当前没有可用课程。</p>';
    return;
  }
  grid.innerHTML = moduleRegistry.map((module) => {
    const course = courseModules[module.id];
    const progress = state.moduleProgress[module.id] || {};
    const guidedPracticeCount = Object.values(course?.lessonGuides || {}).reduce((total, guide) => total + (guide.practice?.length || 0), 0);
    const detail = module.kind === 'native' ? '专项训练 · 真题 · 词汇库' : (course?.lessons.length || 0) + ' 个教学章节 · ' + guidedPracticeCount + ' 道自练 · ' + (course?.questions.length || 0) + ' 道选择题';
    const status = progress.answered ? '已练 ' + progress.answered + ' 题' : (module.kind === 'native' ? '持续更新' : escapeHtml(course?.level || '课程'));
    return '<article class="module-card ' + (module.color === 'blue' ? 'module-blue' : 'module-mint') + '"><div class="module-card-top"><span class="module-icon">' + escapeHtml(module.icon) + '</span><span class="module-status">' + status + '</span></div><h3>' + escapeHtml(module.title) + '</h3><p>' + escapeHtml(module.description) + '</p><div class="module-card-bottom"><small>' + escapeHtml(detail) + '</small><button class="primary-btn" data-module-id="' + escapeHtml(module.id) + '">' + escapeHtml(module.actionLabel) + ' <span>→</span></button></div></article>';
  }).join('');
}

function openModule(moduleId) {
  const module = moduleRegistry.find((item) => item.id === moduleId);
  if (!module) return;
  state.activeModuleId = module.id;
  if (module.kind === 'native') return switchTab(module.tab || 'home');
  state.courseLessonId = '';
  state.courseSearch = '';
  state.courseTopic = 'all';
  courseSession = null;
  renderCourse();
  switchTab('course');
}

function renderCourse() {
  const root = $('#courseContent');
  if (!root) return;
  const course = courseModules[state.activeModuleId];
  if (!course) { root.innerHTML = '<p class="empty-state">请选择一个课程模块。</p>'; return; }
  if (courseSession) return renderCourseQuestion();
  const progress = state.moduleProgress[course.id] || {};
  const lesson = course.lessons.find((item) => item.id === state.courseLessonId);
  const backButton = '<button class="ghost-btn" data-action="course-back-overview">← 章节目录</button>';
  const referenceList = Array.isArray(course.references) && course.references.length ? '<div class="course-references"><h3>参考资料</h3>' + course.references.map((item) => '<a href="' + escapeHtml(item.url) + '" target="_blank" rel="noreferrer">' + escapeHtml(item.title) + ' ↗</a>').join('') + '</div>' : '';
  if (lesson) {
    const lessonIndex = course.lessons.findIndex((item) => item.id === lesson.id);
    const previous = course.lessons[lessonIndex - 1];
    const next = course.lessons[lessonIndex + 1];
    const questionCount = course.questions.filter((item) => item.lessonId === lesson.id).length;
    const isComplete = (progress.completedLessons || []).includes(lesson.id);
    const navButtons = '<div class="lesson-nav">' + (previous ? '<button class="ghost-btn" data-course-lesson="' + escapeHtml(previous.id) + '">← 上一章</button>' : '<span></span>') + (next ? '<button class="ghost-btn" data-course-lesson="' + escapeHtml(next.id) + '">下一章 →</button>' : '<button class="ghost-btn" data-action="course-back-overview">回到目录</button>') + '</div>';
    const keyPoints = lesson.points.filter((point) => point !== lesson.summary);
    const guide = course.lessonGuides?.[lesson.id];
    const conceptSection = guide ? '<section class="lesson-theory"><h3>先把概念讲清楚</h3>' + guide.explain.map((paragraph) => '<p>' + escapeHtml(paragraph) + '</p>').join('') + '</section>' : '';
    const walkthroughSection = guide ? '<section class="lesson-walkthrough"><h3>跟着例子一步步走</h3><ol>' + guide.steps.map((step) => '<li>' + escapeHtml(step) + '</li>').join('') + '</ol></section>' : '';
    const practiceSection = guide ? '<section class="lesson-drills"><h3>动手练习</h3><div>' + guide.practice.map((item, index) => '<article><p><b>练习 ' + (index + 1) + '</b> · ' + escapeHtml(item.prompt) + '</p><details><summary>查看答案与推导</summary><div><b>参考答案</b><p>' + escapeHtml(item.answer) + '</p><b>为什么</b><p>' + escapeHtml(item.analysis) + '</p></div></details></article>').join('') + '</div></section>' : '';
    root.innerHTML = '<div class="course-back">' + backButton + '<span>第 ' + lesson.number + ' 章 · 第 ' + escapeHtml(lesson.pages) + ' 页</span></div><div class="course-lesson-head"><p class="eyebrow">CHAPTER ' + String(lesson.number).padStart(2, '0') + ' / ' + course.lessons.length + ' · ' + escapeHtml(lesson.topic || '未分类') + '</p><h2>' + escapeHtml(lesson.title) + '</h2><p class="lesson-english-title">' + escapeHtml(lesson.englishTitle || '') + '</p><p>' + escapeHtml(lesson.summary) + '</p></div><div class="lesson-content">' + conceptSection + '<section class="lesson-example"><div class="lesson-example-head"><b>学习示例</b><span>逐行演示</span></div><pre><code>' + escapeHtml(lesson.example.code) + '</code></pre><p>' + escapeHtml(lesson.example.explanation) + '</p></section>' + walkthroughSection + practiceSection + '<section class="lesson-points"><h3>知识要点回看</h3><ul>' + keyPoints.map((point) => '<li>' + escapeHtml(point) + '</li>').join('') + '</ul></section><section class="lesson-exercise"><p class="eyebrow">QUICK CHECK</p><h3>快速回忆</h3><p>' + escapeHtml(lesson.exercise.prompt) + '</p><details><summary>查看参考答案与解析</summary><div><b>参考答案</b><p>' + escapeHtml(lesson.exercise.answer) + '</p><b>解析</b><p>' + escapeHtml(lesson.exercise.analysis) + '</p></div></details></section><div class="lesson-takeaway"><b>本章记住</b><span>' + escapeHtml(lesson.takeaway) + '</span></div></div><div class="lesson-actions"><span>' + questionCount + ' 道选择题 · ' + (isComplete ? '已标记完成' : '读完后标记学习完成') + '</span><div><button class="ghost-btn" data-action="course-mark-lesson" data-lesson-id="' + escapeHtml(lesson.id) + '">' + (isComplete ? '撤销完成' : '标记完成') + '</button><button class="primary-btn" data-course-quiz="' + escapeHtml(lesson.id) + '">做本章练习 <span>→</span></button></div></div>' + navButtons + referenceList;
    return;
  }
  const completed = (progress.completedLessons || []).length;
  const query = String(state.courseSearch || '').trim().toLocaleLowerCase();
  const visibleLessons = course.lessons.filter((item) => {
    const searchable = [item.number, item.title, item.englishTitle, item.summary, item.pages, item.topic, ...(item.points || []), item.example?.code, item.exercise?.prompt, item.takeaway].join(' ').toLocaleLowerCase();
    return (state.courseTopic === 'all' || item.topic === state.courseTopic) && (!query || searchable.includes(query));
  });
  const topicOptions = ['<option value="all">全部主题</option>'].concat((course.topics || []).map((topic) => '<option value="' + escapeHtml(topic) + '" ' + (state.courseTopic === topic ? 'selected' : '') + '>' + escapeHtml(topic) + '</option>')).join('');
  const lessonRows = visibleLessons.map((item) => {
    const done = (progress.completedLessons || []).includes(item.id);
    const count = course.questions.filter((question) => question.lessonId === item.id).length;
    const guidedCount = course.lessonGuides?.[item.id]?.practice?.length || 0;
    return '<button class="lesson-row" data-course-lesson="' + escapeHtml(item.id) + '"><span class="lesson-number">' + String(item.number).padStart(2, '0') + '</span><span class="lesson-row-copy"><b>' + escapeHtml(item.title) + '</b><small>' + escapeHtml(item.englishTitle) + ' · p. ' + escapeHtml(item.pages) + ' · ' + escapeHtml(item.summary) + '</small></span><span class="lesson-row-meta"><i>' + escapeHtml(item.topic) + '</i><small>' + guidedCount + ' 道自练 · ' + count + ' 道选择题</small></span><span class="lesson-row-progress">' + (done ? '已完成' : '学习') + ' →</span></button>';
  }).join('');
  const bookInfo = course.book ? '<div class="course-book"><b>' + escapeHtml(course.book.title) + '</b><span>' + escapeHtml(course.book.author) + ' · ' + escapeHtml(course.book.publisher) + ' · ISBN ' + escapeHtml(course.book.isbn) + '</span><small>章节名称按教材目录整理；页码用于快速定位。</small></div>' : '';
  const guidedPracticeCount = Object.values(course.lessonGuides || {}).reduce((total, guide) => total + (guide.practice?.length || 0), 0);
  root.innerHTML = '<div class="course-back"><button class="ghost-btn" data-tab-link="modules">← 模块目录</button><span>课程目录 · 共 ' + course.lessons.length + ' 章</span></div><div class="course-overview"><div><p class="eyebrow">' + escapeHtml((course.level || '课程').toUpperCase()) + ' · COURSE TRACK</p><h2>' + escapeHtml(course.title) + '</h2><p>' + escapeHtml(course.description || '') + '</p><small>已完成 ' + completed + ' / ' + course.lessons.length + ' 章 · ' + guidedPracticeCount + ' 道章节自练 · ' + (course.questions || []).length + ' 道选择题</small></div><button class="primary-btn" data-course-quiz="all">开始章节练习 <span>→</span></button></div><div class="course-progress"><span style="width:' + (course.lessons.length ? Math.round(completed / course.lessons.length * 100) : 0) + '%"></span></div><p class="course-notice">' + escapeHtml(course.notice || '') + '</p>' + bookInfo + '<div class="course-tools"><label class="course-search"><span>⌕</span><input id="courseSearch" type="search" value="' + escapeHtml(state.courseSearch) + '" placeholder="搜索章号、知识点或页码" aria-label="搜索课程章节"></label><select id="courseTopic" aria-label="按知识主题筛选">' + topicOptions + '</select><span>' + visibleLessons.length + ' / ' + course.lessons.length + ' 章</span></div><div class="lesson-list">' + (lessonRows || '<p class="empty-state">没有匹配章节，请调整搜索或主题。</p>') + '</div>' + referenceList;
}

function startCourseQuiz(lessonId) {
  const course = courseModules[state.activeModuleId];
  if (!course) return;
  const items = course.questions.filter((item) => !lessonId || lessonId === 'all' || item.lessonId === lessonId);
  if (!items.length) return toast('当前范围没有选择题，可在各章自测区查看题目与答案解析');
  state.courseLessonId = lessonId && lessonId !== 'all' ? lessonId : '';
  courseSession = { courseId: course.id, items, index: 0, selected: '', score: 0, locked: false };
  renderCourse();
}

function renderCourseQuestion() {
  const course = courseModules[courseSession.courseId];
  const item = courseSession.items[courseSession.index];
  if (!item) return finishCourseQuiz();
  const progress = Math.round(courseSession.index / courseSession.items.length * 100);
  $('#courseContent').innerHTML = '<div class="course-back"><button class="ghost-btn" data-action="course-exit-quiz">退出练习</button><span>' + escapeHtml(course.title) + ' / ' + escapeHtml(course.lessons.find((entry) => entry.id === item.lessonId)?.title || '') + '</span></div><div class="question-top course-question-top"><div><p class="eyebrow">MODULE PRACTICE · ' + escapeHtml(item.title) + '</p><h2>' + escapeHtml(item.prompt) + '</h2></div><div class="question-progress"><span>' + (courseSession.index + 1) + ' / ' + courseSession.items.length + '</span><i><b style="width:' + progress + '%"></b></i></div></div><div class="question-card"><div class="option-list">' + item.options.map((option) => '<button class="option-btn ' + (courseSession.selected === option.id ? 'selected' : '') + '" data-course-option="' + escapeHtml(option.id) + '" ' + (courseSession.locked ? 'disabled' : '') + '><b>' + escapeHtml(option.id) + '.</b> ' + escapeHtml(option.text) + '</button>').join('') + '</div><div id="courseAnswerFeedback" class="answer-feedback"></div><div class="question-actions"><button class="primary-btn" data-action="' + (courseSession.locked ? 'course-next' : 'course-submit') + '">' + (courseSession.locked ? (courseSession.index === courseSession.items.length - 1 ? '查看结果' : '下一题') : '提交答案') + ' <span>→</span></button></div></div>';
}

function submitCourseAnswer() {
  if (!courseSession || courseSession.locked) return;
  if (!courseSession.selected) return toast('请先选择一个答案');
  const course = courseModules[courseSession.courseId];
  const item = courseSession.items[courseSession.index];
  const correct = courseSession.selected === item.answer;
  courseSession.locked = true;
  if (correct) courseSession.score += 1;
  const progress = state.moduleProgress[course.id] || { answered: 0, correct: 0, completedLessons: [], wrongIds: [] };
  progress.answered ||= 0;
  progress.correct ||= 0;
  progress.completedLessons ||= [];
  progress.wrongIds ||= [];
  progress.answered += 1;
  if (correct) progress.correct += 1;
  else if (!progress.wrongIds.includes(item.id)) progress.wrongIds.push(item.id);
  state.moduleProgress[course.id] = progress;
  const answerText = item.options.find((option) => option.id === item.answer)?.text || item.answer;
  const feedback = $('#courseAnswerFeedback');
  feedback.className = 'answer-feedback show ' + (correct ? 'good' : 'bad');
  feedback.innerHTML = '<b>' + (correct ? '回答正确' : '再看一遍') + '</b><span>参考答案：' + escapeHtml(item.answer) + '. ' + escapeHtml(answerText) + '</span><small>' + escapeHtml(item.explanation) + '</small>';
  saveState();
  renderStats();
  $$('.option-btn', $('#courseContent')).forEach((button) => { button.disabled = true; button.classList.toggle('correct-option', button.dataset.courseOption === item.answer); });
  const next = $('[data-action="course-submit"]');
  next.dataset.action = 'course-next';
  next.innerHTML = (courseSession.index === courseSession.items.length - 1 ? '查看结果' : '下一题') + ' <span>→</span>';
}

function finishCourseQuiz() {
  const session = courseSession;
  if (!session) return;
  const course = courseModules[session.courseId];
  const score = Math.round(session.score / session.items.length * 100);
  state.history.unshift({ title: course.title + '练习', meta: session.items.length + ' 题 · ' + new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }), score: score + '%', mode: 'course' });
  state.history = state.history.slice(0, 8);
  const day = todayKey();
  if (state.lastStudy !== day) { state.streak += 1; state.lastStudy = day; }
  saveState();
  renderStats();
  renderRecent();
  $('#courseContent').innerHTML = '<div class="result-card"><div class="result-orb">' + score + '<small>分</small></div><p class="eyebrow">MODULE COMPLETE</p><h2>' + escapeHtml(course.title) + '练习完成</h2><p>' + session.items.length + ' 题中答对 ' + session.score + ' 题。</p><div class="result-actions"><button class="primary-btn" data-action="course-retry">再练一次 <span>→</span></button><button class="ghost-btn" data-action="course-back-overview">返回课程</button></div></div>';
  courseSession = null;
}

function renderStructure() {
  const groups = [
    { title: '写作', color: 'peach', text: '1 题 · 30 分钟', pct: '15%', types: ['写作'] },
    { title: '听力', color: 'mint', text: '25 题 · 25 分钟', pct: '25%', types: ['新闻', '长对话', '短文'] },
    { title: '阅读', color: 'blue', text: '30 题 · 40 分钟', pct: '35%', types: ['选词填空', '段落匹配', '仔细阅读'] },
    { title: '翻译', color: 'gold', text: '1 题 · 30 分钟', pct: '15%', types: ['汉译英'] }
  ];
  $('#structureGrid').innerHTML = groups.map((group, index) => '<article class="structure-card ' + group.color + '"><div class="structure-top"><span class="structure-no">0' + (index + 1) + '</span><b>' + group.pct + '</b></div><h3>' + group.title + '</h3><p>' + group.text + '</p><div class="mini-tags">' + group.types.map((type) => '<span>' + type + '</span>').join('') + '</div></article>').join('');
}
function renderTypePicker() {
  const groups = ['写作', '听力', '阅读', '翻译'];
  $('#typePicker').innerHTML = groups.map((group) => {
    const types = questionData.types.filter((type) => type.group === group);
    return '<div class="type-group"><div class="type-group-label">' + group + '</div><div class="type-options">' + types.map((type) => '<label class="type-option ' + (state.selectedTypes.includes(type.id) ? 'selected' : '') + '"><input type="checkbox" value="' + type.id + '" ' + (state.selectedTypes.includes(type.id) ? 'checked' : '') + '><span class="type-check">✓</span><span><b>' + type.label.replace(group + '：', '') + '</b><small>' + type.weight + ' · ' + type.minutes + ' 分钟</small></span></label>').join('') + '</div></div>';
  }).join('');
}
function renderExamLinks() { $('#examLinks').innerHTML = questionData.examLinks.map((link) => '<a class="link-row" href="' + link.url + '" target="_blank" rel="noreferrer"><span class="link-icon">↗</span><span><b>' + escapeHtml(link.label) + '</b><small>' + escapeHtml(link.note) + '</small></span><span>→</span></a>').join(''); }
function renderRealList() { const items = questionData.items.filter((item) => item.mode === 'real'); $('#realCount').textContent = items.length + ' 道精选题'; $('#realList').innerHTML = items.map((item) => '<button class="real-row" data-real-id="' + item.id + '"><span class="real-type">' + typeLabel(item.type) + '</span><span class="real-title"><b>' + escapeHtml(item.title) + '</b><small>' + escapeHtml(item.source || item.exam || '') + '</small></span><span class="real-arrow">→</span></button>').join(''); }
function renderStats() { const mastery = vocab.length ? Math.round(Object.values(state.vocabStatus).filter((item) => item.mastered).length / vocab.length * 100) : 0; const accuracy = state.total ? Math.round(state.correct / state.total * 100) : 0; $('#streakStat').textContent = state.streak || 0; $('#masteryStat').textContent = mastery; $('#accuracyStat').textContent = state.total ? accuracy : '--'; $('#accuracyUnit').textContent = state.total ? '%' : ''; $('#wrongCount').textContent = state.wrong.length ? state.wrong.length + ' 道待复习题' : '还没有错题'; }
function renderRecent() { const list = $('#recentList'); if (!state.history.length) { list.innerHTML = '<p class="empty-state">完成一次训练后，这里会出现你的复盘记录。</p>'; return; } list.innerHTML = state.history.slice(0, 4).map((item) => '<div class="recent-row"><span class="recent-dot ' + (item.mode === 'real' ? 'orange' : 'mint') + '"></span><div><b>' + escapeHtml(item.title) + '</b><small>' + escapeHtml(item.meta) + '</small></div><strong>' + item.score + '</strong></div>').join(''); }

function startSession({ mode = state.mode, count = Number($('#questionCount').value || 10), selectedTypes = state.selectedTypes, forceItemId = '' } = {}) {
  let items = forceItemId ? questionData.items.filter((item) => item.id === forceItemId) : questionData.items.filter((item) => item.mode === mode && selectedTypes.includes(item.type));
  if (!items.length && mode === 'real') items = questionData.items.filter((item) => item.mode === 'real');
  if (!items.length) return toast('这个题型暂时没有题目，请换一个选择');
  if ($('#shuffleToggle')?.checked && !forceItemId) items = [...items].sort(() => Math.random() - 0.5);
  items = items.slice(0, Math.min(count, items.length));
  state.session = { mode, items, index: 0, score: 0, answered: 0, selected: null, locked: false, matching: {} };
  $('#practiceSetup').classList.add('hidden'); $('#questionStage').classList.remove('hidden'); switchTab('practice'); renderQuestion();
}
function renderQuestion() {
  const session = state.session; if (!session || session.index >= session.items.length) return finishSession();
  const item = session.items[session.index]; const progress = Math.round(session.index / session.items.length * 100); const subjective = ['writing', 'translation'].includes(item.type); let body = '';
  if (item.type.startsWith('listening')) body += '<button class="audio-btn" data-action="speak" data-text="' + encodeURIComponent(item.transcript || item.prompt) + '"><span>▶</span> 播放听力文本</button>';
  if (item.passage) body += '<div class="passage-box">' + escapeHtml(item.passage) + '</div>';
  if (item.wordBank) body += '<div class="word-bank">' + item.wordBank.map((word) => '<button class="word-chip" data-cloze-word="' + escapeHtml(word) + '">' + escapeHtml(word) + '</button>').join('') + '</div>';
  if (item.prompt) body += '<div class="question-prompt">' + escapeHtml(item.prompt) + '</div>';
  if (item.options) body += '<div class="option-list">' + item.options.map((option) => '<button class="option-btn" data-option="' + escapeHtml(option.slice(0, 1)) + '">' + escapeHtml(option) + '</button>').join('') + '</div>';
  if (item.statements) body += '<div class="matching-list">' + item.statements.map((statement) => '<div class="matching-row"><span>' + statement.number + '</span><p>' + escapeHtml(statement.text) + '</p><div class="matching-options">' + ['A', 'B', 'C', 'D'].map((letter) => '<button data-match="' + statement.number + '" data-match-value="' + letter + '">' + letter + '</button>').join('') + '</div></div>').join('') + '</div>';
  if (subjective) body += '<textarea class="answer-area" id="subjectiveAnswer" placeholder="在这里写下你的答案，再用提示清单自评……"></textarea>';
  const actionText = subjective || item.type === 'reading-matching' || item.type === 'reading-cloze' ? '提交自评' : '提交答案';
  $('#questionStage').innerHTML = '<div class="question-top"><div><p class="eyebrow">' + (item.mode === 'real' ? 'REAL EXAM' : 'PRACTICE') + ' · ' + typeLabel(item.type) + '</p><h2>' + escapeHtml(item.title) + '</h2></div><div class="question-progress"><span>' + (session.index + 1) + ' / ' + session.items.length + '</span><i><b style="width:' + progress + '%"></b></i></div></div><div class="question-card">' + body + '<div class="answer-feedback" id="answerFeedback"></div><div class="question-actions"><button class="ghost-btn" data-action="quit-session">退出训练</button><button class="primary-btn" data-action="submit-answer">' + actionText + ' <span>→</span></button></div></div>';
}
function collectAnswer(item) { if (item.type === 'reading-matching') return { ...state.session.matching }; if (item.type === 'reading-cloze') return state.session.selected || ''; if (['writing', 'translation'].includes(item.type)) return $('#subjectiveAnswer')?.value.trim() || ''; return state.session.selected; }
function judge(item, answer) {
  if (['writing', 'translation'].includes(item.type)) return { correct: null, message: '主观题已记录，请按提示完成自评。' };
  if (item.type === 'reading-matching') { const good = item.statements.filter((statement) => answer[statement.number] === statement.answer).length; return { correct: good === item.statements.length, message: '匹配正确 ' + good + '/' + item.statements.length + '，参考答案：' + item.statements.map((statement) => statement.number + statement.answer).join(' · ') }; }
  if (item.type === 'reading-cloze') return { correct: answer.toLowerCase() === String(item.answer).toLowerCase(), message: '参考答案：' + item.answer };
  return { correct: String(answer || '').toUpperCase() === String(item.answer || '').toUpperCase(), message: '参考答案：' + item.answer };
}
function submitAnswer() {
  const session = state.session; if (!session || session.locked) return; const item = session.items[session.index]; const answer = collectAnswer(item);
  if (item.type === 'reading-cloze' && !answer) return toast('请先从词库中选择一个答案'); if (!answer && !['writing', 'translation'].includes(item.type)) return toast('请先选择答案');
  const result = judge(item, answer); session.locked = true; session.answered += 1; if (result.correct === true) session.score += 1; if (result.correct === false && !state.wrong.includes(item.id)) state.wrong.push(item.id); if (result.correct !== null) { state.total += 1; if (result.correct) state.correct += 1; }
  const feedback = $('#answerFeedback'); feedback.className = 'answer-feedback show ' + (result.correct === true ? 'good' : result.correct === false ? 'bad' : 'neutral'); feedback.innerHTML = '<b>' + (result.correct === true ? '回答正确' : result.correct === false ? '再看一遍' : '已记录') + '</b><span>' + escapeHtml(result.message) + '</span>' + (item.explanation ? '<small>' + escapeHtml(item.explanation) + '</small>' : '') + (item.hint ? '<small>提示：' + escapeHtml(item.hint) + '</small>' : '');
  const button = $('[data-action="submit-answer"]'); button.textContent = session.index === session.items.length - 1 ? '查看结果' : '下一题 →'; button.dataset.action = 'next-question'; $$('.option-btn').forEach((option) => option.disabled = true); saveState(); renderStats();
}
function nextQuestion() { if (!state.session) return; state.session.index += 1; state.session.selected = null; state.session.locked = false; state.session.matching = {}; renderQuestion(); }
function finishSession() {
  const session = state.session; if (!session) return; const total = session.items.filter((item) => !['writing', 'translation'].includes(item.type)).length; const score = total ? Math.round(session.score / total * 100) : 0; state.history.unshift({ title: session.mode === 'real' ? '真题精选训练' : '专项训练', meta: session.items.length + ' 题 · ' + new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }), score: score + '%', mode: session.mode }); state.history = state.history.slice(0, 8); const day = todayKey(); if (state.lastStudy !== day) { state.streak += 1; state.lastStudy = day; } saveState(); renderStats(); renderRecent(); $('#questionStage').innerHTML = '<div class="result-card"><div class="result-orb">' + score + '<small>分</small></div><p class="eyebrow">SESSION COMPLETE</p><h2>' + (session.mode === 'real' ? '真题精选完成' : '专项训练完成') + '</h2><p>你完成了 ' + session.items.length + ' 题，其中客观题正确 ' + session.score + ' 题。</p><div class="result-actions"><button class="primary-btn" data-action="restart-session">再来一组 <span>→</span></button><button class="ghost-btn" data-action="back-home">回到今日学习</button></div></div>'; }

function renderVocab() {
  const search = state.vocabSearch.trim().toLowerCase(); let list = vocab.filter((word) => { const text = (word.word + ' ' + word.phonetic + ' ' + word.definition).toLowerCase(); const status = state.vocabStatus[word.id] || {}; if (search && !text.includes(search)) return false; if (state.vocabFilter === 'starred' && !status.starred) return false; if (state.vocabFilter === 'mastered' && !status.mastered) return false; if (state.vocabFilter === 'unfamiliar' && status.mastered) return false; return true; });
  const pageSize = 30; const totalPages = Math.max(1, Math.ceil(list.length / pageSize)); state.vocabPage = Math.min(state.vocabPage, totalPages); const items = list.slice((state.vocabPage - 1) * pageSize, state.vocabPage * pageSize);
  $('#vocabList').innerHTML = items.map((word) => { const status = state.vocabStatus[word.id] || {}; return '<article class="word-row ' + (status.mastered ? 'mastered' : '') + '" data-word-id="' + word.id + '"><div class="word-main"><b>' + escapeHtml(word.word) + '</b><span>' + escapeHtml(word.phonetic) + '</span></div><p>' + escapeHtml(word.definition) + '</p><div class="word-actions"><button data-action="speak-word" title="朗读">▶</button><button data-action="star-word" class="' + (status.starred ? 'active' : '') + '" title="收藏">★</button><button data-action="master-word" class="' + (status.mastered ? 'active' : '') + '" title="标记掌握">✓</button></div></article>'; }).join('') || '<p class="empty-state">没有找到匹配词条。</p>';
  $('#vocabPager').innerHTML = '<span>第 ' + state.vocabPage + ' / ' + totalPages + ' 页 · ' + list.length + ' 个词条</span><div><button class="pager-btn" data-vocab-page="prev" ' + (state.vocabPage <= 1 ? 'disabled' : '') + '>←</button><button class="pager-btn" data-vocab-page="next" ' + (state.vocabPage >= totalPages ? 'disabled' : '') + '>→</button></div>';
}
function renderWordFocus(word) { if (!word) return; const status = state.vocabStatus[word.id] || {}; $('#wordFocus').dataset.wordId = word.id; $('#wordFocus').innerHTML = '<div class="focus-word"><span>' + escapeHtml(word.word) + '</span><button class="audio-btn small" data-action="speak-word-focus">▶ 朗读</button></div><div class="focus-phonetic">' + escapeHtml(word.phonetic) + '</div><div class="focus-definition">' + escapeHtml(word.definition) + '</div><div class="focus-actions"><button class="ghost-btn" data-action="focus-star">' + (status.starred ? '已收藏' : '收藏这个词') + '</button><button class="primary-btn" data-action="focus-master">' + (status.mastered ? '已掌握' : '标记已掌握') + '</button></div>'; }
function updateVocabStatus(id, key) { state.vocabStatus[id] = { ...(state.vocabStatus[id] || {}), [key]: !state.vocabStatus[id]?.[key] }; saveState(); renderVocab(); renderStats(); renderWordFocus(vocab.find((word) => word.id === id)); }

document.addEventListener('click', (event) => {
  const nav = event.target.closest('[data-tab]'); if (nav) return switchTab(nav.dataset.tab); const link = event.target.closest('[data-tab-link]'); if (link) return switchTab(link.dataset.tabLink);
  const mode = event.target.closest('[data-mode]'); if (mode) { state.mode = mode.dataset.mode; $$('.mode-btn').forEach((button) => button.classList.toggle('active', button === mode)); return; }
  const typeOption = event.target.closest('.type-option'); if (typeOption) { const input = $('input', typeOption); input.checked = !input.checked; typeOption.classList.toggle('selected', input.checked); state.selectedTypes = $$('input:checked', $('#typePicker')).map((item) => item.value); saveState(); return; }
  const action = event.target.closest('[data-action]'); if (action) { const name = action.dataset.action; if (name === 'start-daily') return startSession({ count: 5 }); if (name === 'quick-vocab') { state.vocabFilter = 'unfamiliar'; state.vocabPage = 1; return switchTab('vocab'); } if (name === 'quick-real' || name === 'start-real') { state.mode = 'real'; $$('.mode-btn').forEach((button) => button.classList.toggle('active', button.dataset.mode === 'real')); switchTab('practice'); return startSession({ mode: 'real', count: 8 }); } if (name === 'quick-wrong') return toast(state.wrong.length ? '你有 ' + state.wrong.length + ' 道错题待复习' : '完成一组训练后，这里会收集错题'); if (name === 'speak') return speak(decodeURIComponent(action.dataset.text || '')); if (name === 'submit-answer') return submitAnswer(); if (name === 'next-question') return nextQuestion(); if (name === 'quit-session') { state.session = null; $('#questionStage').classList.add('hidden'); $('#practiceSetup').classList.remove('hidden'); return; } if (name === 'restart-session') return startSession({ mode: state.history[0]?.mode || 'practice', count: 10 }); if (name === 'back-home') return switchTab('home'); if (name === 'speak-word') { const word = vocab.find((entry) => entry.id === Number(action.closest('[data-word-id]').dataset.wordId)); if (word) speak(word.word); return; } if (name === 'star-word' || name === 'master-word') { const row = action.closest('[data-word-id]'); return updateVocabStatus(Number(row.dataset.wordId), name === 'star-word' ? 'starred' : 'mastered'); } if (name === 'speak-word-focus') { const word = vocab.find((entry) => entry.id === Number($('#wordFocus').dataset.wordId)); if (word) speak(word.word); return; } if (name === 'focus-star' || name === 'focus-master') return updateVocabStatus(Number($('#wordFocus').dataset.wordId), name === 'focus-star' ? 'starred' : 'mastered'); }
  const option = event.target.closest('.option-btn'); if (option && !option.disabled) { if (option.dataset.courseOption) { if (courseSession && !courseSession.locked) { courseSession.selected = option.dataset.courseOption; $$('.option-btn', $('#courseContent')).forEach((button) => button.classList.toggle('selected', button === option)); } return; } if (state.session) { state.session.selected = option.dataset.option; $$('.option-btn').forEach((button) => button.classList.toggle('selected', button === option)); } return; }
  const match = event.target.closest('[data-match]'); if (match && !state.session.locked) { state.session.matching[match.dataset.match] = match.dataset.matchValue; $$('[data-match="' + match.dataset.match + '"]').forEach((button) => button.classList.toggle('selected', button === match)); return; }
  const chip = event.target.closest('[data-cloze-word]'); if (chip && !state.session.locked) { state.session.selected = chip.dataset.clozeWord; $$('.word-chip').forEach((button) => button.classList.toggle('selected', button === chip)); return; }
  const realRow = event.target.closest('[data-real-id]'); if (realRow) { state.mode = 'real'; switchTab('practice'); return startSession({ mode: 'real', count: 1, forceItemId: realRow.dataset.realId }); }
  const vocabRow = event.target.closest('[data-word-id]'); if (vocabRow && !event.target.closest('button')) return renderWordFocus(vocab.find((word) => word.id === Number(vocabRow.dataset.wordId)));
  const pager = event.target.closest('[data-vocab-page]'); if (pager) { state.vocabPage += pager.dataset.vocabPage === 'next' ? 1 : -1; renderVocab(); }
});
document.addEventListener('change', (event) => { if (event.target.matches('.type-option input')) { state.selectedTypes = $$('input:checked', $('#typePicker')).map((item) => item.value); saveState(); } });
document.addEventListener('click', (event) => {
  const moduleButton = event.target.closest('[data-module-id]');
  if (moduleButton) return openModule(moduleButton.dataset.moduleId);
  const lessonButton = event.target.closest('[data-course-lesson]');
  if (lessonButton) {
    state.courseLessonId = lessonButton.dataset.courseLesson;
    renderCourse();
    window.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  const quizButton = event.target.closest('[data-course-quiz]');
  if (quizButton) return startCourseQuiz(quizButton.dataset.courseQuiz);
  const action = event.target.closest('[data-action]');
  if (!action) return;
  if (action.dataset.action === 'course-mark-lesson') {
    const course = courseModules[state.activeModuleId];
    const progress = state.moduleProgress[state.activeModuleId] || { completedLessons: [] };
    progress.completedLessons ||= [];
    const lessonId = action.dataset.lessonId;
    progress.completedLessons = progress.completedLessons.includes(lessonId) ? progress.completedLessons.filter((id) => id !== lessonId) : [...progress.completedLessons, lessonId];
    state.moduleProgress[state.activeModuleId] = progress;
    saveState();
    renderCourse();
    renderModules();
    return;
  }
  if (action.dataset.action === 'course-submit') return submitCourseAnswer();
  if (action.dataset.action === 'course-next') {
    if (courseSession.index === courseSession.items.length - 1) return finishCourseQuiz();
    courseSession.index += 1;
    courseSession.selected = '';
    courseSession.locked = false;
    return renderCourseQuestion();
  }
  if (action.dataset.action === 'course-exit-quiz') { courseSession = null; return renderCourse(); }
  if (action.dataset.action === 'course-back-overview') { courseSession = null; state.courseLessonId = ''; return renderCourse(); }
  if (action.dataset.action === 'course-retry') return startCourseQuiz(state.courseLessonId || 'all');
});
document.addEventListener('input', (event) => {
  if (event.target.id !== 'courseSearch') return;
  state.courseSearch = event.target.value;
  const caret = event.target.selectionStart;
  renderCourse();
  const search = $('#courseSearch');
  search?.focus();
  search?.setSelectionRange(caret, caret);
});
document.addEventListener('change', (event) => {
  if (event.target.id !== 'courseTopic') return;
  state.courseTopic = event.target.value;
  renderCourse();
});
$('#startPractice').addEventListener('click', () => startSession({ mode: state.mode }));
$('#vocabSearch').addEventListener('input', (event) => { state.vocabSearch = event.target.value; state.vocabPage = 1; renderVocab(); });
$$('[data-vocab-filter]').forEach((button) => button.addEventListener('click', () => { state.vocabFilter = button.dataset.vocabFilter; state.vocabPage = 1; $$('[data-vocab-filter]').forEach((item) => item.classList.toggle('active', item === button)); renderVocab(); }));
$('#resetProgress').addEventListener('click', () => { if (confirm('确定清空本地学习进度吗？')) { localStorage.removeItem(STORAGE_KEY); location.reload(); } });
$('#checkUpdates')?.addEventListener('click', () => checkForContentUpdates());
$('#applyUpdate')?.addEventListener('click', async () => {
  if ($('#applyUpdate').dataset.updateType === 'apk') {
    window.StudyBridge?.openLatestRelease();
    return;
  }
  const registration = serviceWorkerRegistration || await navigator.serviceWorker?.getRegistration();
  if (registration?.waiting) {
    navigator.serviceWorker.addEventListener('controllerchange', () => location.reload(), { once: true });
    registration.waiting.postMessage({ type: 'SKIP_WAITING' });
  } else {
    location.reload();
  }
});
boot().catch((error) => {
  console.error(error);
  const grid = $('#moduleGrid');
  if (grid) grid.innerHTML = '<p class="empty-state">课程数据未能加载。请检查网络，或重新安装最新的 Study.apk。</p>';
  toast('课程数据加载失败');
});
