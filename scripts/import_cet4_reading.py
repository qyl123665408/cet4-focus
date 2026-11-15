"""Import public CET-4 reading passages and source answer keys."""
import concurrent.futures
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp' / 'python-deps'))
from bs4 import BeautifulSoup

CACHE = ROOT / 'tmp' / 'cet4-sources'
BASE = 'https://eol.nju.edu.cn/cet4/papers/'
EXAMS = [f'{date}-{paper}' for date in ['202312', '202406', '202412'] for paper in range(1, 4) if f'{date}-{paper}' != '202312-2']
NL = chr(10)

def download(exam):
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / (exam + '.html')
    if not target.exists():
        request = urllib.request.Request(BASE + exam + '.html', headers={'User-Agent': 'Study-content-import/1.0'})
        target.write_bytes(urllib.request.urlopen(request, timeout=40).read())
    return exam, target.read_bytes()

def text(node):
    return ' '.join(node.get_text().split())

def parse(exam, content):
    root = BeautifulSoup(content, 'html.parser')
    label = f'{exam[:4]}年{int(exam[4:6])}月 第{exam[-1]}套'
    links = {text(node): urllib.request.urljoin(BASE + exam + '.html', node['href']) for node in root.select('a[href]')}
    common = {'mode': 'real', 'exam': label, 'source': label + '真题（南京大学公开自测版）', 'sourceUrl': BASE + exam + '.html', 'paperUrl': links.get('下载真题 PDF', ''), 'answerSourceUrl': links.get('下载答案解析 PDF', ''), 'sourceKind': 'public-exam'}
    items = []
    bank = root.select_one('#banked')
    words = {text(node)[0]: text(node)[1:].strip() for node in bank.select('.wbitem')}
    assert set(words) == set('ABCDEFGHIJKLMNO'), (exam, 'word bank')
    passage = text(bank.select_one('.passage'))
    for number in range(26, 36):
        passage, count = re.subn(rf'(?<![0-9]){number}(?![0-9])', f'__{number}__', passage)
        assert count == 1, (exam, 'blank marker', number, count)
    for node in bank.select('.blank'):
        number, letter = int(node['data-no']), node['data-ans']
        assert letter in words
        items.append({**common, 'id': f'r-{exam}-q{number}', 'type': 'reading-cloze', 'title': label + f' · 选词填空 {number}', 'questionNumber': number, 'passageId': exam + '-banked', 'passage': passage, 'prompt': f'请选择第 {number} 空的单词，输入单词或字母。其他空格保留用于理解完整上下文。', 'wordBank': [key + ') ' + word for key, word in words.items()], 'blanks': [{'number': number, 'answer': words[letter]}], 'answer': words[letter], 'acceptedAnswers': [letter, words[letter]], 'explanation': f'来源答案：{number}. {letter}) {words[letter]}。结合完整文章检查词性与上下文搭配。'})
    matching = root.select_one('#matching')
    paragraphs = [{'id': node['data-key'], 'text': text(node)[1:].strip()} for node in matching.select('.para')]
    paragraph_map = {p['id']: p['text'] for p in paragraphs}
    for node in matching.select('.mstmt'):
        number, answer = int(node['data-no']), node['data-ans']
        assert answer in paragraph_map
        statement = text(node.select_one('.qhead')).partition('.')[2].strip()
        items.append({**common, 'id': f'r-{exam}-q{number}', 'type': 'reading-matching', 'title': label + f' · 段落匹配 {number}', 'questionNumber': number, 'passageId': exam + '-matching', 'paragraphs': paragraphs, 'passage': (NL + NL).join(p['id'] + ') ' + p['text'] for p in paragraphs), 'statements': [{'number': number, 'text': statement, 'answer': answer}], 'explanation': '来源答案：' + answer + ' 段。对照原文：' + NL + paragraph_map[answer]})
    for index, block in enumerate(root.select('#careful .careful-passage'), 1):
        passage = text(block.select_one('.passage'))
        assert len(passage.split()) > 200, (exam, 'short passage')
        for node in block.select('.q'):
            number, answer = int(node['data-no']), node['data-ans']
            options = [opt['data-key'] + ') ' + text(opt.select_one('.otext')) for opt in node.select('.opt')]
            assert len(options) == 4 and answer in 'ABCD'
            items.append({**common, 'id': f'r-{exam}-q{number}', 'type': 'reading-detail', 'title': label + f' · 仔细阅读 {number}', 'questionNumber': number, 'passageId': exam + f'-careful-{index}', 'passage': passage, 'prompt': text(node.select_one('.stem')), 'options': options, 'answer': answer, 'explanation': '来源答案：' + options[ord(answer) - ord('A')] + '。本条提供来源答案，未附独立逐题详解；可打开来源解析核对。'})
    assert sorted(item['questionNumber'] for item in items) == list(range(26, 56)), (exam, 'question coverage')
    assert all(item['paperUrl'] and item['answerSourceUrl'] for item in items)
    return items, {'exam': label, 'id': exam, 'sourceUrl': common['sourceUrl'], 'paperUrl': common['paperUrl'], 'answerSourceUrl': common['answerSourceUrl'], 'sourceSha256': hashlib.sha256(content).hexdigest(), 'questionCount': len(items)}

def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        sources = dict(executor.map(download, EXAMS))
    items, exams = [], []
    for exam in EXAMS:
        batch, manifest = parse(exam, sources[exam])
        items.extend(batch)
        exams.append(manifest)
    assert len(items) == 240 and len({q['id'] for q in items}) == 240
    manifest = {'checkedAt': '2026-11-15', 'smallQuestionCount': len(items), 'passageCount': len({q['passageId'] for q in items}), 'notice': '按套从来源网站获取，下载后保存在本机；不随 APK 或 GitHub 分发真题正文。答案为来源参考答案，不代表考试机构官方答案。', 'exams': exams}
    (CACHE / 'verified-reading.json').write_text(json.dumps(items, ensure_ascii=False, indent=2) + NL, encoding='utf-8')
    (ROOT / 'data' / 'reading-sources.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + NL, encoding='utf-8')
    print(json.dumps({'verifiedLocalQuestions': len(items), 'exams': len(exams), 'passages': manifest['passageCount']}, ensure_ascii=False))

if __name__ == '__main__':
    main()
