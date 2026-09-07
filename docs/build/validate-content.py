"""Reject Cyrillic in project-authored content and distribution artifacts.

Dependency distributions, captured runtime evidence and Git history are excluded.
The check inspects text, decoded escapes, PDF text, and XML inside nested ZIP/PPTX.
It does not identify arbitrary Latin-script prose or OCR raster images.
"""
import html
import io
import json
from pathlib import Path
import re
import sys
import unicodedata
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
EXCLUDED = {'.git', '.local', 'tools', 'node_modules', 'target', '__pycache__'}
TEXT = {'.md', '.mjs', '.js', '.ts', '.java', '.py', '.ps1', '.html', '.xml',
        '.rels', '.json', '.ndjson', '.yml', '.yaml', '.txt', '.properties',
        '.sql', '.toml', '.svg', '.css', '.csv'}
findings = []
counts = {'textFiles': 0, 'pdfFiles': 0, 'archiveContainers': 0}

def decoded_text(value):
    value = html.unescape(value)
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), value)

def inspect_text(name, value):
    counts['textFiles'] += 1
    value = decoded_text(value)
    lines = set()
    line = 1
    for char in value:
        if char == '\n':
            line += 1
        elif ord(char) > 127 and 'CYRILLIC' in unicodedata.name(char, ''):
            lines.add(line)
    if lines:
        findings.append({'path': name, 'reason': 'Cyrillic text', 'lines': sorted(lines)})
    if re.search(r'''\blang\s*=\s*["']ru(?:-RU)?["']''', value, re.I):
        findings.append({'path': name, 'reason': 'Russian language metadata'})

def inspect_bytes(name, data, depth=0):
    suffix = Path(name).suffix.lower()
    if re.search(r'_RU(?=\.|/|\\|$)', name):
        findings.append({'path': name, 'reason': 'Superseded language filename'})
    if suffix in TEXT or Path(name).name in {'AGENTS.md', 'Dockerfile', '.gitignore'}:
        try:
            inspect_text(name, data.decode('utf-8-sig'))
        except UnicodeDecodeError:
            findings.append({'path': name, 'reason': 'Text encoding must be UTF-8'})
    elif suffix in {'.pptx', '.zip'}:
        if depth > 3:
            raise RuntimeError('Unexpected archive nesting')
        counts['archiveContainers'] += 1
        with ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    inspect_bytes(name + '!/' + entry.filename, archive.read(entry), depth + 1)
    elif suffix == '.pdf':
        from pypdf import PdfReader
        counts['pdfFiles'] += 1
        reader = PdfReader(io.BytesIO(data))
        for index, page in enumerate(reader.pages, 1):
            inspect_text(name + f'#page={index}', page.extract_text() or '')

def files(directory):
    for child in sorted(directory.iterdir()):
        if child.is_symlink():
            continue
        if child.is_dir():
            if child.name not in EXCLUDED:
                yield from files(child)
        elif child.is_file():
            yield child

def main():
    for file in files(ROOT):
        inspect_bytes(file.relative_to(ROOT).as_posix(), file.read_bytes())
    result = {'passed': not findings, 'counts': counts,
              'excludedDirectories': sorted(EXCLUDED), 'findings': findings}
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1

if __name__ == '__main__':
    sys.exit(main())
