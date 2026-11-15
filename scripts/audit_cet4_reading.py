"""Audit reading keys and passage coverage against linked source PDFs."""
import concurrent.futures
import difflib
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp' / 'python-deps'))
from pypdf import PdfReader

def inspect(exam):
    result = {'exam': exam['id']}
    for kind, key in [('answers', 'answerSourceUrl'), ('paper', 'paperUrl')]:
        target = ROOT / 'tmp' / 'cet4-sources' / (exam['id'] + '-' + kind + '.pdf')
        if not target.exists():
            url = urllib.parse.quote(exam[key], safe=':/%?=&')
            target.write_bytes(urllib.request.urlopen(url, timeout=40).read())
        pages = PdfReader(target).pages
        content = chr(10).join(page.extract_text() or '' for page in pages)
        target.with_suffix('.txt').write_text(content, encoding='utf-8')
        result[kind + 'Pages'] = len(pages)
        if kind == 'answers':
            result['answerKeys'] = {int(n): a for n, a in re.findall(r'(?m)^([2345][0-9])[ .、]*([A-O])(?:[^A-Za-z]|$)', content) if 26 <= int(n) <= 55}
    return result

manifest = json.loads((ROOT / 'data' / 'reading-sources.json').read_text(encoding='utf-8'))
items_all = json.loads((ROOT / 'tmp' / 'cet4-sources' / 'verified-reading.json').read_text(encoding='utf-8'))
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    results = list(executor.map(inspect, manifest['exams']))
for result in results:
    items = [q for q in items_all if q['id'].startswith('r-' + result['exam'] + '-')]
    result['missingKeys'] = []
    result['mismatches'] = []
    result['passageCoverage'] = []
    normalize = lambda value: re.sub('[^a-z]', '', value.lower())
    pdf = normalize((ROOT / 'tmp/cet4-sources' / (result['exam'] + '-paper.txt')).read_text(encoding='utf-8'))
    for passage_id, passage in {q['passageId']: q['passage'] for q in items}.items():
        article = normalize(passage)
        matched = sum(block.size for block in difflib.SequenceMatcher(None, article, pdf, autojunk=False).get_matching_blocks())
        result['passageCoverage'].append({'passageId': passage_id, 'letters': len(article), 'matchedLetters': matched, 'continuousMatch': article in pdf})
    for q in items:
        expected = q['acceptedAnswers'][0] if q['type'] == 'reading-cloze' else q['statements'][0]['answer'] if q['type'] == 'reading-matching' else q['answer']
        actual = result['answerKeys'].get(q['questionNumber'])
        if actual is None: result['missingKeys'].append(q['questionNumber'])
        elif actual != expected: result['mismatches'].append({'number': q['questionNumber'], 'html': expected, 'pdf': actual})
    print(json.dumps(result, ensure_ascii=True))
(ROOT / 'tmp' / 'cet4-sources' / 'audit.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
if any(result['missingKeys'] or result['mismatches'] or any(p['letters'] != p['matchedLetters'] for p in result['passageCoverage']) for result in results):
    raise SystemExit('Reading answer or passage coverage audit failed')
passages = {q['passageId']: normalize(q['passage']) for q in items_all}
assert len(passages) == len(set(passages.values())) == 32, 'Repeated reading passage'
question_keys = {(q['type'], normalize(q['passage']), q['questionNumber'] if q['type'] == 'reading-cloze' else normalize(q['prompt']) if q['type'] == 'reading-detail' else normalize(q['statements'][0]['text'])) for q in items_all}
assert len(question_keys) == len(items_all) == 240, 'Repeated reading question'
print('PASS: 240 distinct questions, 32 distinct passages, all reference keys and normalized passage letters covered by source PDFs.')
