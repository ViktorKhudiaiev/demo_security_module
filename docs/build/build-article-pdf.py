"""Typeset the authored article. Input is locally rendered Markdown, not remote HTML."""
from pathlib import Path
import argparse
from html import escape
from html.parser import HTMLParser
import posixpath
import re
from urllib.parse import urlsplit
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, KeepTogether
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.colors import HexColor, white
from reportlab.lib.enums import TA_LEFT
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from pypdf import PdfReader

ROOT=Path(__file__).resolve().parents[2]
arguments=argparse.ArgumentParser(description=__doc__)
arguments.add_argument('--source-revision',required=True,help='Full Git commit containing the published Markdown and evidence')
source_revision=arguments.parse_args().source_revision
if not re.fullmatch(r'[0-9a-f]{40}',source_revision):
    raise ValueError('An explicit full source commit is required for PDF reference links')
repository='https://github.com/ViktorKhudiaiev/demo_security_module'
BUILD=ROOT/'.local/artifact-qa/pdfs/publication'
OUT=ROOT/'docs/article/article.pdf'
OUT.parent.mkdir(parents=True,exist_ok=True)
FONT=Path('C:/Windows/Fonts')
for name,file in [('Georgia','georgia.ttf'),('Georgia-Bold','georgiab.ttf'),('Georgia-Italic','georgiai.ttf'),('Georgia-BoldItalic','georgiaz.ttf'),('Segoe','segoeui.ttf'),('Segoe-Bold','segoeuib.ttf')]:
    pdfmetrics.registerFont(TTFont(name,str(FONT/file)))
pdfmetrics.registerFontFamily('Georgia',normal='Georgia',bold='Georgia-Bold',italic='Georgia-Italic',boldItalic='Georgia-BoldItalic')
pdfmetrics.registerFontFamily('Segoe',normal='Segoe',bold='Segoe-Bold',italic='Segoe',boldItalic='Segoe-Bold')

class Node:
    def __init__(self,tag='',attrs=()):self.tag=tag;self.attrs=dict(attrs);self.children=[]
class Parser(HTMLParser):
    def __init__(self):super().__init__(convert_charrefs=True);self.root=Node();self.stack=[self.root]
    def handle_starttag(self,tag,attrs):
        n=Node(tag,attrs);self.stack[-1].children.append(n)
        if tag not in ('br','hr','img','meta','input'):self.stack.append(n)
    def handle_endtag(self,tag):
        for i in range(len(self.stack)-1,0,-1):
            if self.stack[i].tag==tag:self.stack=self.stack[:i];return
    def handle_data(self,data):self.stack[-1].children.append(data)
def normalized(s):return s.replace('\u2014',' - ').replace('\u2013','-').replace('\u2011','-').replace('\u00b7',' / ')
def inline(n):
    if isinstance(n,str):return escape(normalized(n))
    content=''.join(inline(x) for x in n.children)
    if n.tag in ('strong','b'):return '<b>'+content+'</b>'
    if n.tag in ('em','i'):return '<i>'+content+'</i>'
    if n.tag=='code':return '<font name="Courier" size="8.8">'+content+'</font>'
    if n.tag=='a':
        href=n.attrs.get('href','')
        parsed=urlsplit(href)
        if not parsed.scheme and not parsed.netloc and parsed.path:
            target=posixpath.normpath(posixpath.join('docs/article',parsed.path))
            if target.startswith('../') or target.startswith('/'):
                raise ValueError('Article link escapes the repository')
            href=f'{repository}/blob/{source_revision}/{target}'
            if parsed.fragment:href+='#'+parsed.fragment
        return '<link href="'+escape(href,quote=True)+'" color="#215acc">'+content+'</link>' if href.startswith('https://') else content
    if n.tag=='br':return '<br/>'
    return content
ink=HexColor('#172a42');muted=HexColor('#52647b');blue=HexColor('#215acc')
body=ParagraphStyle('body',fontName='Georgia',fontSize=10.5,leading=15.5,textColor=ink,spaceAfter=10,allowWidows=0,allowOrphans=0,splitLongWords=1)
heading=ParagraphStyle('heading',fontName='Segoe-Bold',fontSize=15,leading=19,textColor=ink,spaceBefore=17,spaceAfter=8,keepWithNext=True)
title=ParagraphStyle('title',fontName='Georgia-Bold',fontSize=28,leading=31,textColor=ink,spaceAfter=15,keepWithNext=True)
subtitle=ParagraphStyle('subtitle',fontName='Segoe',fontSize=13,leading=18,textColor=muted,spaceAfter=15,keepWithNext=True)
author=ParagraphStyle('author',fontName='Segoe',fontSize=10,leading=14,textColor=muted,spaceAfter=20,keepWithNext=True)
cell=ParagraphStyle('cell',fontName='Segoe',fontSize=9,leading=12.5,textColor=ink,spaceAfter=0)
cellhead=ParagraphStyle('cellhead',parent=cell,fontName='Segoe-Bold')
W,H=A4;left=54;right=54;usable=W-left-right

class NumberedCanvas(canvas.Canvas):
    def __init__(self,*a,**kw):super().__init__(*a,**kw);self.pages=[]
    def showPage(self):self.pages.append(dict(self.__dict__));self._startPage()
    def save(self):
        total=len(self.pages)
        for state in self.pages:
            self.__dict__.update(state);self.saveState()
            self.setFont('Segoe',8);self.setFillColor(muted)
            if self._pageNumber>1:
                self.drawString(left,H-32,'Verifiable Record Integrity Without a Blockchain')
                self.setStrokeColor(HexColor('#cbd6e5'));self.line(left,H-40,W-right,H-40)
            self.drawString(left,29,'Viktor Khudiaiev / September 2026')
            self.drawRightString(W-right,29,f'{self._pageNumber} / {total}')
            self.restoreState();super().showPage()
        super().save()

parser=Parser();parser.feed((BUILD/'article-body.html').read_text(encoding='utf8'))
story=[];first_h2=True;first_p=True
for node in parser.root.children:
    if isinstance(node,str):continue
    if node.tag=='h1':story.append(Paragraph(inline(node),title))
    elif node.tag=='h2':
        style=subtitle if first_h2 else heading;first_h2=False;story.append(Paragraph(inline(node),style))
    elif node.tag=='p':
        style=author if first_p else body;first_p=False;story.append(Paragraph(inline(node),style))
    elif node.tag=='table':
        rows=[]
        def collect(n):
            if isinstance(n,str):return
            if n.tag=='tr':rows.append(n)
            else:
                for child in n.children:collect(child)
        collect(node);cells=[]
        for i,row in enumerate(rows):cells.append([Paragraph(inline(c),cellhead if i==0 else cell) for c in row.children if isinstance(c,Node) and c.tag in ('th','td')])
        widths=[usable*.18,usable*.43,usable*.39] if len(cells[0])==3 else [usable*.47,usable*.53]
        table=Table(cells,colWidths=widths,repeatRows=1,hAlign='LEFT')
        table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),HexColor('#eaf1fb')),('LINEBELOW',(0,0),(-1,-1),.5,HexColor('#cbd6e5')),('LEFTPADDING',(0,0),(-1,-1),8),('RIGHTPADDING',(0,0),(-1,-1),8),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]))
        story.extend([Spacer(1,5),table,Spacer(1,14)])
    elif node.tag in ('ul','ol'):
        for i,c in enumerate(x for x in node.children if isinstance(x,Node) and x.tag=='li'):story.append(Paragraph(f'{i+1}. '+inline(c),body))
    else:raise RuntimeError('Unexpected top-level article element '+node.tag)
doc=SimpleDocTemplate(str(OUT),pagesize=A4,rightMargin=right,leftMargin=left,topMargin=58,bottomMargin=51,title='Verifiable Record Integrity Without a Blockchain',author='Viktor Khudiaiev',subject='Authenticated PostgreSQL execution and independently retained evidence',pageCompression=1)
doc.build(story,canvasmaker=NumberedCanvas)
r=PdfReader(str(OUT));text='\n'.join(p.extract_text() for p in r.pages)
for required in ['20.0091','19.9181','2,400','709','microseconds','five-second','Ed25519','settlement','19.9636','137','Mailpit']:
    if required not in text:raise RuntimeError('Missing PDF content '+required)
if '[repository URL]' in text:raise RuntimeError('Unresolved repository placeholder')
links=[annotation.get_object().get('/A',{}).get('/URI','') for page in r.pages for annotation in page.get('/Annots',[])]
if not any(f'/blob/{source_revision}/docs/evidence/local-verification-2026-09-07.json' in link for link in links):
    raise RuntimeError('PDF is missing the immutable latest load evidence reference')
print(f'Created {OUT.name}: {len(r.pages)} pages, {len(text.split())} extracted words')
