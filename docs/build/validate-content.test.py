"""Unit coverage for the project language gate, using in-memory test fixtures."""
import importlib.util
import io
from pathlib import Path
import unittest
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location(
    'language_check', Path(__file__).with_name('validate-content.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

class LanguageGateTests(unittest.TestCase):
    def setUp(self):
        check.findings.clear()

    def test_conforming_source_is_accepted(self):
        check.inspect_bytes('example.java', b'// Verify the authenticated snapshot.')
        self.assertFalse(check.findings)

    def test_literal_cyrillic_is_rejected(self):
        check.inspect_bytes('example.md', ('Example\n' + chr(0x0410)).encode('utf-8'))
        self.assertEqual(check.findings[0]['lines'], [2])

    def test_escaped_cyrillic_is_rejected(self):
        fixture = ('\\' + 'u0410').encode('ascii')
        check.inspect_bytes('example.json', fixture)
        self.assertEqual(check.findings[0]['reason'], 'Cyrillic text')

    def test_html_entity_is_rejected(self):
        fixture = ('&' + '#1040;').encode('ascii')
        check.inspect_bytes('example.html', fixture)
        self.assertEqual(check.findings[0]['reason'], 'Cyrillic text')

    def test_old_language_filename_is_rejected(self):
        check.inspect_bytes('guide_RU.md', b'Example')
        self.assertEqual(check.findings[0]['reason'], 'Superseded language filename')

    def test_language_metadata_is_rejected(self):
        value = '<r lang=' + chr(34) + 'ru-RU' + chr(34) + '>Example</r>'
        check.inspect_bytes('notes.xml', value.encode('ascii'))
        self.assertEqual(check.findings[0]['reason'], 'Russian language metadata')

    def test_nested_pptx_notes_are_checked(self):
        pptx_buffer = io.BytesIO()
        with ZipFile(pptx_buffer, 'w') as pptx:
            pptx.writestr('ppt/notesSlides/notesSlide1.xml', chr(0x0410))
        zip_buffer = io.BytesIO()
        with ZipFile(zip_buffer, 'w') as archive:
            archive.writestr('slides.pptx', pptx_buffer.getvalue())
        check.inspect_bytes('package.zip', zip_buffer.getvalue())
        self.assertIn('package.zip!/slides.pptx!/ppt/notesSlides/', check.findings[0]['path'])

if __name__ == '__main__':
    unittest.main()
