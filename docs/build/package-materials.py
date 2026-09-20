"""Build a strictly allow-listed offline presentation package; no runtime data."""
from hashlib import sha256
from html import escape, unescape
from html.parser import HTMLParser
import importlib.util
import json
from pathlib import Path
import posixpath
import re
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'docs/downloads/demo-materials.zip'
FILES = [
    'docs/README.md',
    'docs/reference/architecture-decisions.md',
    'docs/reference/implementation-status.md',
    'docs/reference/master-plan.md',
    'docs/reference/glossary.md',
    'docs/reference/security-module.md',
    'docs/reference/running.md',
    'docs/reference/contributing.md',
    'docs/reference/code-structure.md',
    'docs/reference/notifications.md',
    'docs/evidence/local-verification-2026-09-07.json',
    'docs/evidence/notification-verification-2026-09-08.json',
    'docs/evidence/local-verification-2026-09-06-final.json',
    'docs/evidence/local-verification-2026-09-06.json',
    'docs/evidence/local-recovery-2026-09-06.json',
    'docs/evidence/live-lab-verification-2026-09-06.json',
    'docs/evidence/protected-migration-2026-09-06.json',
    'docs/reference/architecture.md',
    'docs/index.html',
    'docs/live/index.html',
    'docs/live/client.js',
    'docs/article/article.md',
    'docs/article/index.html',
    'docs/article/linkedin-post.txt',
    'docs/article/linkedin-post.html',
    'docs/article/assets/record-integrity-cover.png',
    'docs/article/assets/cover-notes.md',
    'docs/article/medium-article.md',
    'docs/article/medium-article.html',
    'docs/article/publishing-guide.md',
    'docs/presentation/guide.md',
    'docs/presentation/guide.html',
    'docs/evidence/local-verification-2026-09-05.json',
    'docs/evidence/local-recovery-2026-09-05.json',
    'docs/evidence/live-lab-verification-2026-09-05.json',
    'docs/reference/protocol.md',
    'tokenization-module/src/test/java/com/demo/integrity/crypto/CanonicalEncoderTest.java',
    'docs/article/article.pdf',
    'docs/presentation/demo.pptx',
    'docs/presentation/notes.md',
]

def resolve_link(source, href):
    parsed = urlsplit(unescape(href))
    if parsed.scheme or parsed.netloc or not parsed.path:
        return None
    return posixpath.normpath(posixpath.join(posixpath.dirname(source), unquote(parsed.path)))

def source_only_html(match, source):
    href, label = match.group(1), match.group(2)
    target = resolve_link(source, href)
    if target is None or target in FILES:
        return match.group(0)
    if target == 'docs/downloads/demo-materials.zip':
        return '<span class="source-reference">You are viewing the extracted documentation pack.</span>'
    return f'{label} <small class="source-reference">(full source checkout: <code>{escape(target)}</code>)</small>'

def source_only_md(match, source):
    label, href = match.group(1), match.group(2)
    target = resolve_link(source, href)
    if target is None or target in FILES:
        return match.group(0)
    return f'{label} (full source checkout: `{target}`)'

contents = {}
for name in FILES:
    file = ROOT / name
    if not file.is_file():
        raise RuntimeError(f'Missing deliverable: {name}')
    data = file.read_bytes()
    if name.endswith(('.html', '.md')):
        text = data.decode('utf-8')
        if name.endswith('.html'):
            text = re.sub(r'<a href="([^"]+)"[^>]*>(.*?)</a>',
                          lambda m: source_only_html(m, name), text, flags=re.S)
        if name.endswith('.md'):
            text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)',
                          lambda m: source_only_md(m, name), text)
        # The shared package is portable; workstation commands require the full checkout.
        text = text.replace(str(ROOT), r'C:\path\to\record-integrity')
        data = text.encode('utf-8')
    contents[name] = data

readme = '''# Presentation package

Viktor Khudiaiev — September 2026

## Start here

Extract the entire ZIP and open START_HERE.html or docs/index.html.
The explanation HTML needs no Internet, Node, Java or Docker.
Keep the companion documents in their extracted folders.

1. Overview: the problem, control, execution and limits in four short blocks.
2. Architecture: twelve numbered arrows across three modules, two PostgreSQL databases and embedded ActiveMQ.
3. Sequence: the full normal-transfer path with separate participant lifelines.
4. Evidence, Terms and Materials: retained results, definitions and the talk pack.

The explanation diagrams do NOT connect to an API or calculate a real HMAC.
The separate Live Lab page DOES call the actual stack when opened through the
full project's loopback-only helper at http://127.0.0.1:8090/docs/live/index.html.
It supports protected funding, a valid transfer, direct-primary forgery, safe
retries and fresh demonstration identities; all funds are simulated. The page
alone cannot run experiments. The Java/PostgreSQL stack and helper backend are
not in this archive. The handbook commands require the full source checkout.
Replace C:\\path\\to\\record-integrity with your path.
References to omitted code appear as labels with paths in the full source checkout.

## Files

- docs/index.html — offline interactive walkthrough.
- docs/live/index.html — real transaction lab entry point and startup instructions.
- docs/live/client.js — lab page client, without backend credentials.
- docs/article/index.html — updated article in the browser.
- docs/article/article.pdf — updated technical article.
- docs/presentation/demo.pptx — 14 editable
  slides with detailed speaker notes in PowerPoint notes view.
- docs/presentation/notes.md — the same talk notes separately.
- docs/presentation/guide.html — Rehearsal and Q&A handbook.
- docs/reference/notifications.md — incident email setup, retries and capture limits.
- docs/evidence/*.json — dated verification, recovery, Live Lab and notification reports,
  including the latest strict-throughput failure rather than only historical passes.
- docs/reference/protocol.md — exact encoding and key-custody protocol.
- CanonicalEncoderTest.java under tokenization-module/src/test — public golden
  vectors only. Its repeated 0b test key is intentionally public, never a runtime key.
- MANIFEST.json — SHA-256 digests of every packaged file except the manifest itself.

## Boundaries

The package contains no service binaries, databases, real secret keys, credentials
or .local directory. Public test constants are not deployment credentials.
The latest full load run, September 7, offered 20 TPS for 120 seconds and completed
all 2,400 unique operations with correct protected accounting and audit, with zero
transaction failures. Steady throughput was 19.9636 TPS, below the strict 20 TPS
threshold with zero tolerance: load and combined acceptance FAILED. Whole-run
throughput was 19.9403 TPS; p95 was 668 ms. Historical September 6 successes remain
separate dated evidence. These are local software-key Windows measurements, not a
production SLA or a new benchmark of the email-notification revision.

The September 8 notification revision passed 137 Java tests, 44 Node tests and
18 PostgreSQL role checks, plus four live notification scenarios. Two incident
messages were captured by local Mailpit; no external Gmail delivery was tested.
SMTP acceptance is not inbox delivery, and retry after an ambiguous failure can
duplicate an email. Notification delivery is a side channel, not an execution gate.

The production architecture is a target, not deployed infrastructure. Links to
external RFCs and vendor documentation require Internet when opened. Offline
content and the teaching model themselves do not require those sites.

No venue-specific conference template has been applied. The source repository is
https://github.com/ViktorKhudiaiev/demo_security_module. This package updates the
repository article, not the separately supplied original source PDF.
'''
contents['README_READ_FIRST.md'] = readme.encode('utf-8')
contents['START_HERE.html'] = '''<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Secure Transfer Demo — start here</title><style>
body{max-width:800px;margin:12vh auto;padding:24px;color:#172a42;background:#f3f6fa;font:20px/1.6 'Segoe UI',Arial,sans-serif}
h1{font-size:38px;line-height:1.2}a{color:#215acc}li{margin:16px 0}small{font-size:16px}
</style></head><body><h1>Secure Transfer Demo</h1>
<p>Understand the problem, follow the architecture arrows and inspect the sequence. Reading materials work locally after you extract the archive.</p>
<p><a href="docs/index.html">Open the interactive walkthrough →</a></p>
<ul><li><a href="docs/live/index.html">Live transaction lab · requires the full local project</a></li>
<li><a href="docs/presentation/guide.html">Presenter handbook</a></li>
<li><a href="docs/article/article.pdf">Article · PDF</a></li>
<li><a href="docs/presentation/demo.pptx">Technical presentation · PowerPoint</a></li></ul>
<small>The teaching model does not connect to services or make real payments.
Retained test results and production requirements are identified separately.</small>
</body></html>'''.encode('utf-8')

class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        if 'href' in attrs:
            self.links.append(attrs['href'])
        if tag in ('script', 'img', 'iframe', 'link') and 'src' in attrs:
            self.links.append(attrs['src'])

link_count = 0
for name, data in contents.items():
    if not name.endswith('.html'):
        continue
    parsed = Links()
    parsed.feed(data.decode('utf-8'))
    if len(parsed.ids) != len(set(parsed.ids)):
        raise RuntimeError(f'Duplicate HTML IDs in {name}')
    for href in parsed.links:
        target = resolve_link(name, href)
        if target is not None and target not in contents:
            raise RuntimeError(f'Broken offline link: {name} -> {href}')
        link_count += 1

# Only explicitly listed files are read. A permissive archive of a workspace is forbidden.
for name in contents:
    if any(part in ('.local', '.git', 'node_modules', 'target', 'secrets', 'keys')
           for part in name.split('/')):
        raise RuntimeError(f'Forbidden archive path: {name}')

# Inspect nested Office XML and PDF text before writing a shareable archive.
language_spec = importlib.util.spec_from_file_location(
    'language_check', Path(__file__).with_name('validate-content.py'))
language_check = importlib.util.module_from_spec(language_spec)
language_spec.loader.exec_module(language_check)
for name, data in contents.items():
    language_check.inspect_bytes(name, data)
if language_check.findings:
    raise RuntimeError('Package failed the content validation: ' +
                       json.dumps(language_check.findings))

manifest = {name: {'bytes': len(data), 'sha256': sha256(data).hexdigest()}
            for name, data in sorted(contents.items())}
contents['MANIFEST.json'] = (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(OUTPUT, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(contents.items()):
        archive.writestr(name, data)

with ZipFile(OUTPUT) as archive:
    if archive.testzip() is not None or set(archive.namelist()) != set(contents):
        raise RuntimeError('Archive integrity failed')
    for name, expected in manifest.items():
        if sha256(archive.read(name)).hexdigest() != expected['sha256']:
            raise RuntimeError(f'Manifest mismatch: {name}')

print(json.dumps({'archive': str(OUTPUT), 'fileCount': len(contents),
                  'bytes': OUTPUT.stat().st_size, 'htmlLinksChecked': link_count,
                  'runtimeDirectoriesIncluded': False, 'passed': True}, ensure_ascii=False))
