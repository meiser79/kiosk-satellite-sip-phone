#!/usr/bin/env python3
"""Check translation parsing and Java source escaping."""
from generate_translations import generate, read_properties
from pathlib import Path
import tempfile

with tempfile.TemporaryDirectory(prefix='plugin-i18n-test-') as directory:
    props=Path(directory)/'de.properties'
    props.write_text('# test\nhello=Grüße "SIP"\nline=Zeile\\nZwei\n',encoding='utf-8')
    values=read_properties(props)
    source=generate(values)
    assert 'case "hello": return "Gr\\u00fc\\u00dfe \\\"SIP\\\"";' in source
    assert 'case "line": return "Zeile\\\\nZwei";' in source
print('translation generator ok')
