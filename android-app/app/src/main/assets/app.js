const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const STORAGE_KEY = 'cet4-focus-state-v1';
const state = { tab: 'home', mode: 'practice', selectedTypes: [], vocabFilter: 'all', vocabPage: 1, vocabSearch: '', vocabStatus: {}, total: 0, correct: 0, streak: 0, lastStudy: '', wrong: [], history: [], session: null };
let vocab = [];
let questionData = { types: [], items: [], examLinks: [] };
let versionInfo = { version: 'dev', dataVersion: '' };
let serviceWorkerRegistration = null;

const saveState = () => localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...state, session: null }));
function loadState() { try { Object.assign(state, JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')); } catch (_) {} }
const escapeHtml = (value = '') => String(value).replace(/[&<>'\"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '\"': '&quot;' }[char]));
const todayKey = () => new Date().toISOString().slice(0, 10);
function toast(message) { const node = $('#toast'); node.textContent = message; node.classList.add('show'); clearTimeout(toast.timer); toast.timer = setTimeout(() => node.classList.remove('show'), 2200); }
function speak(text, lang = 'en-US') { if (!('speechSynthesis' in window)) return toast('当前浏览器不支持朗读'); speechSynthesis.cancel(); const utterance = new SpeechSynthesisUtterance(text); utterance.lang = lang; utterance.rate = 0.9; speechSynthesis.speak(utterance); }

function showUpdateNotice() { $('#updateNotice')?.classList.remove('hidden'); }

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
    window.setTimeout(() => serviceWorkerRegistration?.update(), 4000);
  } catch (error) {
    console.warn('Service worker registration failed', error);
  }
}

function setupVersion(version) {
  versionInfo = version || versionInfo;
  const label = $('#appVersion');
  if (label) label.textContent = '版本 ' + (versionInfo.version || 'dev') + (versionInfo.dataVersion ? ' · 题库 ' + versionInfo.dataVersion : '');
  const previous = localStorage.getItem('cet4-app-version');
  if (previous && previous !== versionInfo.version) showUpdateNotice();
  if (versionInfo.version) localStorage.setItem('cet4-app-version', versionInfo.version);
}

async function boot() {
  loadState();
  const results = await Promise.all([
    fetch('data/questions.json').then((res) => res.json()),
    fetch('data/vocab.json').then((res) => res.json()),
    fetch('data/version.json', { cache: 'no-store' }).then((res) => res.ok ? res.json() : {}).catch(() => ({}))
  ]);
  questionData = results[0]; vocab = results[1]; setupVersion(results[2]);
  if (!state.selectedTypes.length) state.selectedTypes = questionData.types.map((item) => item.id);
  renderAll();
  registerServiceWorker();
}

function renderAll() { renderNav(); renderStructure(); renderTypePicker(); renderExamLinks(); renderRealList(); renderVocab(); renderStats(); renderRecent(); switchTab(state.tab || 'home'); }
function renderNav() {
  $$('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.tab === state.tab));
  const titles = { home: '今天，先完成一小组', practice: '按题型练，按弱项补', real: '真题中心', vocab: '四级词汇库' };
  $('#pageTitle').textContent = titles[state.tab] || titles.home;
}
function switchTab(tab) { state.tab = tab; $$('.tab-panel').forEach((panel) => panel.classList.toggle('active', panel.id === 'tab-' + tab)); renderNav(); saveState(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
function typeLabel(type) { const item = questionData.types.find((entry) => entry.id === type); return item ? item.label : type; }

function renderStructure() {
  const groups = [
    { title: '写作', color: 'peach', text: '1 题 · 30 分钟', pct: '15%', types: ['写作'] },
    { title: '听力', color: 'mint', text: '25 题 · 25 分钟', pct: '25%', types: ['新闻', '长对话', '短文'] },
    { title: '阅读', color: 'blue', text: '30 题 · 40 分钟', pct: '35%', types: ['选词填空', '段落匹配', '仔细阅读'] },
    { title: '翻译', color: 'gold', text: '1 题 · 30 分钟', pct: '15%', types: ['汉译英'] }
  ];
  $('#structureGrid').innerHTML = groups.map((group, index) => '`<article class="structure-card ' + group.color + '"><div class="structure-top"><span class="structure-no">0' + (index + 1) + '</span><b>' + group.pct + '</b></div><h3>' + group.title + '</h3><p>' + group.text + '</p><div class="mini-tags">' + group.types.map((type) => '<span>' + type + '</span>').join('') + '</div></article>`').join('');
}
function renderTypePicker() {
  const groups = ['写作', '听力', '阅读', '翻译'];
  $('#typePicker').innerHTML = groups.map((group) => {
    const types = questionData.types.filter((type) => type.group === group);
    return '`<div class="type-group"><div class="type-group-label">' + group + '</div><div class="type-options">' + types.map((type) => '`<label class="type-option ' + (state.selectedTypes.includes(type.id) ? 'selected' : '') + '"><input type="checkbox" value="' + type.id + '" ' + (state.selectedTypes.includes(type.id) ? 'checked' : '') + '><span class="type-check">✓</span><span><b>' + type.label.replace(group + '：', '') + '</b><small>' + type.weight + ' · ' + type.minutes + ' 分钟</small></span></label>`').join('') + '</div></div>`';
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
  const option = event.target.closest('.option-btn'); if (option && !option.disabled) { state.session.selected = option.dataset.option; $$('.option-btn').forEach((button) => button.classList.toggle('selected', button === option)); return; }
  const match = event.target.closest('[data-match]'); if (match && !state.session.locked) { state.session.matching[match.dataset.match] = match.dataset.matchValue; $$('[data-match="' + match.dataset.match + '"]').forEach((button) => button.classList.toggle('selected', button === match)); return; }
  const chip = event.target.closest('[data-cloze-word]'); if (chip && !state.session.locked) { state.session.selected = chip.dataset.clozeWord; $$('.word-chip').forEach((button) => button.classList.toggle('selected', button === chip)); return; }
  const realRow = event.target.closest('[data-real-id]'); if (realRow) { state.mode = 'real'; switchTab('practice'); return startSession({ mode: 'real', count: 1, forceItemId: realRow.dataset.realId }); }
  const vocabRow = event.target.closest('[data-word-id]'); if (vocabRow && !event.target.closest('button')) return renderWordFocus(vocab.find((word) => word.id === Number(vocabRow.dataset.wordId)));
  const pager = event.target.closest('[data-vocab-page]'); if (pager) { state.vocabPage += pager.dataset.vocabPage === 'next' ? 1 : -1; renderVocab(); }
});
document.addEventListener('change', (event) => { if (event.target.matches('.type-option input')) { state.selectedTypes = $$('input:checked', $('#typePicker')).map((item) => item.value); saveState(); } });
$('#startPractice').addEventListener('click', () => startSession({ mode: state.mode }));
$('#vocabSearch').addEventListener('input', (event) => { state.vocabSearch = event.target.value; state.vocabPage = 1; renderVocab(); });
$$('[data-vocab-filter]').forEach((button) => button.addEventListener('click', () => { state.vocabFilter = button.dataset.vocabFilter; state.vocabPage = 1; $$('[data-vocab-filter]').forEach((item) => item.classList.toggle('active', item === button)); renderVocab(); }));
$('#resetProgress').addEventListener('click', () => { if (confirm('确定清空本地学习进度吗？')) { localStorage.removeItem(STORAGE_KEY); location.reload(); } });
$('#applyUpdate')?.addEventListener('click', async () => {
  const registration = serviceWorkerRegistration || await navigator.serviceWorker?.getRegistration();
  if (registration?.waiting) {
    navigator.serviceWorker.addEventListener('controllerchange', () => location.reload(), { once: true });
    registration.waiting.postMessage({ type: 'SKIP_WAITING' });
  } else {
    location.reload();
  }
});
boot().catch((error) => { console.error(error); toast('数据加载失败，请用本地服务器打开此文件夹'); });
