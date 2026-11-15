"""Read and write WoW SavedVariables files (Lua assignments of tables/values) as Python data.
Tables become OrderedDicts; array items keep integer keys."""
import re
from collections import OrderedDict

BS = chr(92)  # backslash
WS = re.compile(r'(\s+|--[^\n]*)')
NUM = re.compile(r'-?(?:\d+\.?\d*(?:[eE][-+]?\d+)?|inf|nan)|true|false|nil')
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
ESC = {'n': '\n', 't': '\t', 'r': '\r', BS: BS, '"': '"', "'": "'", '\n': '\n'}


class P:
    def __init__(s, t):
        s.t, s.i = t, 0

    def ws(s):
        while True:
            m = WS.match(s.t, s.i)
            if not m or m.end() == s.i:
                return
            s.i = m.end()

    def peek(s):
        s.ws()
        return s.t[s.i] if s.i < len(s.t) else ''

    def eat(s, c):
        s.ws()
        if not s.t.startswith(c, s.i):
            raise ValueError('expected %r at %d: %r' % (c, s.i, s.t[s.i:s.i + 40]))
        s.i += len(c)

    def string(s):
        quote = s.t[s.i]
        s.i += 1
        out = []
        while True:
            c = s.t[s.i]
            if c == BS:
                n = s.t[s.i + 1]
                if n.isdigit():
                    m = re.compile(r'\d{1,3}').match(s.t, s.i + 1)
                    out.append(chr(int(m.group())))
                    s.i = m.end()
                    continue
                out.append(ESC.get(n, n))
                s.i += 2
                continue
            if c == quote:
                s.i += 1
                return ''.join(out)
            out.append(c)
            s.i += 1

    def value(s):
        c = s.peek()
        if c == '{':
            return s.table()
        if c in '"\'':
            return s.string()
        m = NUM.match(s.t, s.i)
        if not m:
            raise ValueError('bad value at %d: %r' % (s.i, s.t[s.i:s.i + 40]))
        s.i = m.end()
        v = m.group()
        if v == 'true':
            return True
        if v == 'false':
            return False
        if v == 'nil':
            return None
        return float(v) if re.search(r'[.eEn]', v) else int(v)

    def table(s):
        s.eat('{')
        d = OrderedDict()
        n = 1
        while s.peek() != '}':
            if s.peek() == '[':
                s.eat('[')
                k = s.value()
                s.eat(']')
                s.eat('=')
                d[k] = s.value()
            else:
                d[n] = s.value()
                n += 1
            if s.peek() in (',', ';'):
                s.i += 1
        s.eat('}')
        return d


def load(text):
    p = P(text)
    out = OrderedDict()
    while p.peek():
        m = NAME.match(p.t, p.i)
        p.i = m.end()
        p.eat('=')
        out[m.group()] = p.value()
    return out


def q(v):
    v = v.replace(BS, BS + BS).replace('"', BS + '"').replace('\n', BS + 'n').replace('\r', BS + 'r')
    return '"' + v + '"'


def dump_value(v, ind):
    if isinstance(v, dict):
        if not v:
            return '{\n' + '\t' * ind + '}'
        lines = ['{']
        for k, x in v.items():
            key = '[%s]' % (q(k) if isinstance(k, str) else repr(k))
            lines.append('\t' * (ind + 1) + key + ' = ' + dump_value(x, ind + 1) + ',')
        lines.append('\t' * ind + '}')
        return '\n'.join(lines)
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if v is None:
        return 'nil'
    if isinstance(v, str):
        return q(v)
    if isinstance(v, float):
        return repr(v)
    return str(v)


def dump(data):
    return '\n' + ''.join('%s = %s\n' % (k, dump_value(v, 0)) for k, v in data.items())
