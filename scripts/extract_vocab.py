from __future__ import annotations

import json
import re
from pathlib import Path

import pdfplumber

SOURCE = Path(r"D:\学习有关文件\大学英语四级词汇带音标-乱序版.pdf")
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "vocab.json"


def clean(value: str | None) -> str:
    if not value:
        return ""
    value = value.replace("\u00a0", " ")
    value = re.sub(r"\s+", " ", value).strip()
    return value


def clean_phonetic(value: str | None) -> str:
    value = clean(value)
    # The source PDF sometimes wraps phonetic symbols on a new line.
    return value.replace("ɑ ​", "ɑ").replace("ɔ ​", "ɔ")


def main() -> None:
    if not SOURCE.exists():
        raise FileNotFoundError(SOURCE)

    entries: list[dict[str, object]] = []
    seen: set[tuple[str, str]] = set()
    with pdfplumber.open(SOURCE) as pdf:
        for page_number, page in enumerate(pdf.pages, start=1):
            tables = page.extract_tables()
            if not tables:
                continue
            for row in tables[0][1:]:
                if not row or len(row) < 4:
                    continue
                number = clean(row[0])
                word = clean(row[1])
                phonetic = clean_phonetic(row[2])
                definition = clean(row[3])
                if not number.isdigit() or not word or not definition:
                    continue
                key = (word.lower(), definition)
                if key in seen:
                    continue
                seen.add(key)
                entries.append({
                    "id": len(entries) + 1,
                    "sourceNumber": int(number),
                    "sourcePage": page_number,
                    "word": word,
                    "phonetic": phonetic,
                    "definition": definition,
                })

    entries.sort(key=lambda item: int(item["sourceNumber"]))
    for index, item in enumerate(entries, start=1):
        item["id"] = index

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(entries)} words to {OUT}")


if __name__ == "__main__":
    main()
