"""Minimal Java .class parser and bytecode patcher (no dependencies).

Only what j2a needs:
  * read the constant pool (to know which J2ME APIs a game uses),
  * rewrite calls to Class.getResourceAsStream(String) into a static call to
    j2a.core.ResourceShim.getResourceAsStream(Class, String).
"""
import re
import struct

CONSTANT_SIZES = {
    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
    15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}

RESOURCE_TARGET = ("java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;")
RESOURCE_SHIM = ("j2a/core/ResourceShim", "getResourceAsStream",
                 "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;")


class ClassFormatError(Exception):
    pass


def _mutf8(raw):
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("latin-1")


class ClassFile:
    def __init__(self, data):
        self.data = data
        if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
            raise ClassFormatError("not a class file")
        self.minor, self.major = struct.unpack(">HH", data[4:8])
        count = struct.unpack(">H", data[8:10])[0]
        self.cp = [None] * count  # (tag, value)
        pos = 10
        i = 1
        while i < count:
            tag = data[pos]
            pos += 1
            if tag == 1:
                ln = struct.unpack(">H", data[pos:pos + 2])[0]
                self.cp[i] = (1, _mutf8(data[pos + 2:pos + 2 + ln]))
                pos += 2 + ln
            elif tag in CONSTANT_SIZES:
                size = CONSTANT_SIZES[tag]
                body = data[pos:pos + size]
                if tag in (7, 8, 16, 19, 20):
                    self.cp[i] = (tag, struct.unpack(">H", body)[0])
                elif tag in (9, 10, 11, 12, 17, 18):
                    self.cp[i] = (tag, struct.unpack(">HH", body))
                else:
                    self.cp[i] = (tag, body)
                pos += size
                if tag in (5, 6):
                    i += 1
            else:
                raise ClassFormatError("bad constant tag %d" % tag)
            i += 1
        self.cp_end = pos
        self.access, self.this_idx, self.super_idx = struct.unpack(">HHH", data[pos:pos + 6])
        pos += 6
        icount = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        self.interfaces = [self.class_name(struct.unpack(">H", data[pos + 2 * k:pos + 2 * k + 2])[0])
                           for k in range(icount)]
        pos += 2 * icount
        self.code_ranges = []  # (abs_start, length) of each method's bytecode
        pos = self._members(pos, False)   # fields
        pos = self._members(pos, True)    # methods

    # ------------------------------------------------------------------ parsing
    def _members(self, pos, methods):
        d = self.data
        n = struct.unpack(">H", d[pos:pos + 2])[0]
        pos += 2
        for _ in range(n):
            pos += 6  # access, name, descriptor
            acount = struct.unpack(">H", d[pos:pos + 2])[0]
            pos += 2
            for _ in range(acount):
                name_idx, length = struct.unpack(">HI", d[pos:pos + 6])
                if methods and self.utf8(name_idx) == "Code":
                    code_len = struct.unpack(">I", d[pos + 10:pos + 14])[0]
                    self.code_ranges.append((pos + 14, code_len))
                pos += 6 + length
        return pos

    def utf8(self, idx):
        e = self.cp[idx] if 0 < idx < len(self.cp) else None
        return e[1] if e and e[0] == 1 else None

    def class_name(self, idx):
        e = self.cp[idx] if 0 < idx < len(self.cp) else None
        if not e or e[0] != 7:
            return None
        return self.utf8(e[1])

    @property
    def name(self):
        return self.class_name(self.this_idx)

    @property
    def super_name(self):
        return self.class_name(self.super_idx) if self.super_idx else None

    def member_ref(self, idx):
        e = self.cp[idx]
        if not e or e[0] not in (9, 10, 11):
            return None
        cls_idx, nat_idx = e[1]
        nat = self.cp[nat_idx]
        return (self.class_name(cls_idx), self.utf8(nat[1][0]), self.utf8(nat[1][1]))

    def referenced_classes(self):
        """All class names referenced by this class (constants + descriptors)."""
        out = set()
        for e in self.cp:
            if not e:
                continue
            if e[0] == 7:
                n = self.utf8(e[1])
                if n and not n.startswith("["):  # array types are caught by the descriptor scan
                    out.add(n)
            elif e[0] == 1 and ("L" in e[1] and ";" in e[1]):
                for m in re.findall(r"L([\w/$]+);", e[1]):
                    out.add(m)
        out.discard(self.name)
        return out

    def method_refs(self):
        for i, e in enumerate(self.cp):
            if e and e[0] in (10, 11):
                yield self.member_ref(i)

    # ----------------------------------------------------------------- patching
    def patch_resource_calls(self):
        """Returns patched class bytes, or None if nothing to patch."""
        target_idx = [i for i, e in enumerate(self.cp)
                      if e and e[0] == 10 and self.member_ref(i) == RESOURCE_TARGET]
        if not target_idx:
            return None
        sites = []
        for start, length in self.code_ranges:
            for pc, op in _walk(self.data, start, length):
                if op == 0xB6:
                    idx = struct.unpack(">H", self.data[start + pc + 1:start + pc + 3])[0]
                    if idx in target_idx:
                        sites.append(start + pc)
        if not sites:
            return None

        # Append: Utf8 class, Class, Utf8 name, Utf8 desc, NameAndType, Methodref
        n = len(self.cp)
        cls_utf, cls, name_utf, desc_utf, nat, mref = n, n + 1, n + 2, n + 3, n + 4, n + 5

        def utf(s):
            b = s.encode("utf-8")
            return b"\x01" + struct.pack(">H", len(b)) + b

        extra = (utf(RESOURCE_SHIM[0]) + b"\x07" + struct.pack(">H", cls_utf) + utf(RESOURCE_SHIM[1])
                 + utf(RESOURCE_SHIM[2]) + b"\x0c" + struct.pack(">HH", name_utf, desc_utf)
                 + b"\x0a" + struct.pack(">HH", cls, nat))
        rest = bytearray(self.data[self.cp_end:])
        for site in sites:
            off = site - self.cp_end
            rest[off] = 0xB8  # invokestatic
            rest[off + 1:off + 3] = struct.pack(">H", mref)
        out = bytearray(self.data[:8])
        out += struct.pack(">H", n + 6)
        out += self.data[10:self.cp_end]
        out += extra
        out += rest
        return bytes(out)


_LEN = [1] * 256
for _op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
    _LEN[_op] = 2
for _op in list(range(0x99, 0xA9)) + [0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8,
                                      0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7]:
    _LEN[_op] = 3
_LEN[0xC5] = 4
for _op in (0xB9, 0xBA, 0xC8, 0xC9):
    _LEN[_op] = 5


def _walk(data, start, length):
    """Yields (pc, opcode) for every instruction of a Code attribute."""
    pc = 0
    while pc < length:
        op = data[start + pc]
        yield pc, op
        if op == 0xAA:  # tableswitch
            p = pc + 1 + ((4 - (pc + 1) % 4) % 4)
            low, high = struct.unpack(">ii", data[start + p + 4:start + p + 12])
            pc = p + 12 + (high - low + 1) * 4
        elif op == 0xAB:  # lookupswitch
            p = pc + 1 + ((4 - (pc + 1) % 4) % 4)
            npairs = struct.unpack(">i", data[start + p + 4:start + p + 8])[0]
            pc = p + 8 + npairs * 8
        elif op == 0xC4:  # wide
            pc += 6 if data[start + pc + 1] == 0x84 else 4
        else:
            pc += _LEN[op]
