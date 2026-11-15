import fs from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const errors = [];
const fail = (message) => errors.push(message);
const readJson = async (relativePath) => JSON.parse(await fs.readFile(path.join(root, relativePath), 'utf8'));

const registry = await readJson('data/modules/index.json');
const moduleIds = new Set();
for (const module of registry.modules || []) {
  if (!module.id || moduleIds.has(module.id)) fail('Module id is missing or duplicated: ' + (module.id || '(empty)'));
  moduleIds.add(module.id);
  if (module.kind !== 'course') continue;

  let course;
  try {
    course = await readJson(module.file);
  } catch (error) {
    fail(module.id + ': cannot load ' + module.file + ': ' + error.message);
    continue;
  }

  if (course.id !== module.id) fail(module.id + ': registry id does not match course data');
  if (!Array.isArray(course.lessons) || course.lessons.length === 0) {
    fail(module.id + ': course must include lessons');
    continue;
  }

  const lessonIds = new Set();
  let lastPage = 0;
  course.lessons.forEach((lesson, index) => {
    if (lesson.number !== index + 1) fail(module.id + ': lesson order breaks at position ' + (index + 1));
    if (!lesson.id || lessonIds.has(lesson.id)) fail(module.id + ': lesson id is missing or duplicated: ' + (lesson.id || '(empty)'));
    lessonIds.add(lesson.id);
    if (!lesson.title || !lesson.summary || !Array.isArray(lesson.points) || lesson.points.length < 2) fail(module.id + '/' + lesson.id + ': missing title, summary, or key points');
    if (lesson.topic && !(course.topics || []).includes(lesson.topic)) fail(module.id + '/' + lesson.id + ': topic is not listed in course topics');
    if (!lesson.example?.code || !lesson.example?.explanation) fail(module.id + '/' + lesson.id + ': example needs code and explanation');
    if (!lesson.exercise?.prompt || !lesson.exercise?.answer || !lesson.exercise?.analysis) fail(module.id + '/' + lesson.id + ': self-check needs prompt, answer, and analysis');
    const pages = String(lesson.pages || '').match(/^(\d+)-(\d+)$/);
    if (!pages || Number(pages[1]) <= lastPage || Number(pages[1]) > Number(pages[2])) fail(module.id + '/' + lesson.id + ': invalid or out-of-order page range ' + lesson.pages);
    if (pages) lastPage = Number(pages[2]);
  });

  if (module.id === 'x64-assembly' && course.lessons.length !== 43) fail('x64-assembly: expected all 43 textbook chapters');
  if (module.id === 'x64-assembly') {
    const guides = course.lessonGuides || {};
    const guideIds = new Set(Object.keys(guides));
    if (guideIds.size !== course.lessons.length) fail('x64-assembly: every chapter must have exactly one teaching guide');
    for (const lesson of course.lessons) {
      const guide = guides[lesson.id];
      if (!guide) {
        fail('x64-assembly/' + lesson.id + ': missing teaching guide');
        continue;
      }
      if (!Array.isArray(guide.explain) || guide.explain.length < 2 || guide.explain.some((paragraph) => typeof paragraph !== 'string' || paragraph.length < 80)) {
        fail('x64-assembly/' + lesson.id + ': teaching guide needs at least two substantial explanations');
      }
      if (!Array.isArray(guide.steps) || guide.steps.length < 3 || guide.steps.some((step) => typeof step !== 'string' || step.length < 20)) {
        fail('x64-assembly/' + lesson.id + ': teaching guide needs a step-by-step worked example');
      }
      if (!Array.isArray(guide.practice) || guide.practice.length < 2 || guide.practice.some((item) => !item.prompt || !item.answer || !item.analysis)) {
        fail('x64-assembly/' + lesson.id + ': teaching guide needs at least two exercises with answers and explanations');
      }
    }
  }
  const questions = course.questions || [];
  const questionIds = new Set();
  const coveredLessons = new Set();
  for (const question of questions) {
    if (!question.id || questionIds.has(question.id)) fail(module.id + ': question id is missing or duplicated: ' + (question.id || '(empty)'));
    questionIds.add(question.id);
    if (!lessonIds.has(question.lessonId)) fail(module.id + '/' + question.id + ': unknown lesson ' + question.lessonId);
    else coveredLessons.add(question.lessonId);
    const optionIds = new Set((question.options || []).map((option) => option.id));
    if (!question.prompt || !question.explanation || optionIds.size < 2 || !optionIds.has(question.answer)) fail(module.id + '/' + question.id + ': question needs choices, a valid answer, and an explanation');
    if ((question.options || []).some((option) => !option.text)) fail(module.id + '/' + question.id + ': choice text is empty');
  }
  for (const lesson of course.lessons) {
    if (!coveredLessons.has(lesson.id)) fail(module.id + '/' + lesson.id + ': no answerable practice question is mapped to this chapter');
  }
}

const cet4 = await readJson('data/questions.json');
for (const item of cet4.items || []) {
  if (['writing', 'translation'].includes(item.type)) {
    if (!(item.referenceAnswer || item.rubric?.length || item.checklist?.length || item.hint)) fail('cet4/' + item.id + ': subjective question needs a reference answer or self-review rubric');
  } else if (item.type === 'reading-matching') {
    if (!item.statements?.length || item.statements.some((statement) => !statement.answer)) fail('cet4/' + item.id + ': matching question is missing answers');
  } else if (!item.answer && !item.blanks?.every((blank) => blank.answer)) {
    fail('cet4/' + item.id + ': question is missing an answer');
  }
}

if (errors.length) {
  console.error('Content validation failed (' + errors.length + ' issue' + (errors.length === 1 ? '' : 's') + '):');
  for (const error of errors) console.error('- ' + error);
  process.exitCode = 1;
} else {
  const assembly = await readJson('data/modules/x64-assembly.json');
  console.log('Validated ' + registry.modules.length + ' modules, ' + assembly.lessons.length + ' assembly chapters, ' + assembly.questions.length + ' answered assembly questions, and ' + cet4.items.length + ' CET-4 items.');
}
