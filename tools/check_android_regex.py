#!/usr/bin/env python3
"""Check Kotlin regex literals with ICU4C in Actions, without a device or emulator.

Android's java.util.regex uses ICU4C; JVM/Robolectric tests use the host JDK
engine and accept some patterns that crash during initialization on Android.
This supplements the JVM semantic tests with native ICU syntax validation.
"""
import ctypes
import ctypes.util
from pathlib import Path
import re


class ParseError(ctypes.Structure):
    _fields_ = [('line', ctypes.c_int32), ('offset', ctypes.c_int32),
                ('pre_context', ctypes.c_uint16 * 16), ('post_context', ctypes.c_uint16 * 16)]


class IcuRegex:
    def __init__(self):
        library = ctypes.util.find_library('icui18n')
        if not library:
            raise RuntimeError('Native ICU4C is required for Android regex validation')
        self.library = ctypes.CDLL(library)
        version = re.search(r'\.so\.(\d+)', library)
        suffix = '_' + version[1] if version else ''
        self.open = getattr(self.library, 'uregex_open' + suffix)
        self.open.argtypes = [ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32, ctypes.c_uint32,
                              ctypes.POINTER(ParseError), ctypes.POINTER(ctypes.c_int32)]
        self.open.restype = ctypes.c_void_p
        self.close = getattr(self.library, 'uregex_close' + suffix)
        self.close.argtypes = [ctypes.c_void_p]
        self.close.restype = None
        self.error_name = getattr(self.library, 'u_errorName' + suffix)
        self.error_name.argtypes = [ctypes.c_int32]
        self.error_name.restype = ctypes.c_char_p
        self.name = library

    def error(self, pattern):
        raw = pattern.encode('utf-16-le')
        units = (ctypes.c_uint16 * (len(raw) // 2)).from_buffer_copy(raw)
        status = ctypes.c_int32(0)
        detail = ParseError()
        compiled = self.open(units, len(units), 0, ctypes.byref(detail), ctypes.byref(status))
        if compiled:
            self.close(compiled)
        if status.value > 0:
            return f'{self.error_name(status.value).decode()} at {detail.line}:{detail.offset}'
        if not compiled:
            return 'No compiled pattern returned'
        return None


LITERAL = re.compile(r'\bRegex\s*\(\s*("""[\s\S]*?"""|"(?:[^"\\]|\\.)*")')
ESCAPES = {'t': '\t', 'b': '\b', 'n': '\n', 'r': '\r', "'": "'", '"': '"', '\\': '\\', '$': '$'}


def kotlin_string(literal):
    if literal.startswith('"""'):
        value = literal[3:-3]
        if re.search(r'\$(?:[A-Za-z_]|\{)', value):
            raise ValueError('Interpolated raw regex needs an explicit runtime compatibility test')
        return value
    value = literal[1:-1]
    if re.search(r'(?<!\\)\$(?:[A-Za-z_]|\{)', value):
        raise ValueError('Interpolated regex needs an explicit runtime compatibility test')

    def unescape(match):
        escape = match[1]
        return chr(int(escape[1:], 16)) if escape.startswith('u') else ESCAPES[escape]

    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', unescape, value)


def main():
    icu = IcuRegex()
    # Each build-24 initializer must be rejected by this engine. This prevents
    # silently replacing the check with Python/Java's more permissive engine.
    old_patterns = [r'\\begin\{([A-Za-z*]+)}',
                    r'\\(begin|end)\{(equation\*?|align\*?|alignat\*?|gather\*?|split|multline\*?)}',
                    r'\\label\{[^{}]*}|\\(?:nonumber|notag)\b', r'\\tag(\*)?\{([^{}]*)}']
    for pattern in old_patterns:
        before, _, after = pattern.rpartition('}')
        fixed = before + r'\}' + after
        if icu.error(pattern) is None or icu.error(fixed) is not None:
            raise AssertionError('Native ICU regression fixture did not behave as expected')
    # Preserve quantifiers, Unicode properties, and braces inside character classes.
    for pattern in [r'[A-Za-z0-9_-]{1,80}', r'\p{Cntrl}', r'[^{}]*', r'[}{]']:
        if icu.error(pattern):
            raise AssertionError('Valid ICU fixture was rejected: ' + pattern)

    root = Path(__file__).resolve().parents[1]
    checked = 0
    failures = []
    for path in sorted((root / 'app/src/main/java').rglob('*.kt')):
        source = path.read_text()
        for match in LITERAL.finditer(source):
            location = f'{path.relative_to(root)}:{source.count(chr(10), 0, match.start()) + 1}'
            try:
                error = icu.error(kotlin_string(match[1]))
            except (KeyError, ValueError) as exception:
                error = str(exception)
            checked += 1
            if error:
                failures.append(f'{location}: {error}')
    if checked == 0:
        raise AssertionError('No source regex literals were checked')
    if failures:
        raise SystemExit('\n'.join(failures))
    print(f'Android regex compatibility: {checked} source literals passed using {icu.name}; 8 regression fixtures passed')


if __name__ == '__main__':
    main()
