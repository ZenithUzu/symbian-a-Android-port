"""Rank independent resolution evidence; never treat a sprite sheet as a screen by area alone."""
import os
import re
import struct
from collections import Counter, defaultdict

from .classfile import ClassFile, ClassFormatError, _walk


def image_size(data):
    if len(data) >= 24 and data.startswith(b'\x89PNG\r\n\x1a\n'):
        return struct.unpack('>II', data[16:24])
    if len(data) >= 10 and data[:6] in (b'GIF87a', b'GIF89a'):
        return struct.unpack('<HH', data[6:10])
    if data[:2] == b'\xff\xd8':
        pos = 2
        while pos + 4 <= len(data):
            if data[pos] != 255:
                break
            marker = data[pos+1]
            pos += 2
            if marker == 255:
                pos -= 1
                continue
            if marker in (0xd8, 0xd9) or 0xd0 <= marker <= 0xd7:
                continue
            length = int.from_bytes(data[pos:pos+2], 'big')
            if length < 2:
                break
            if marker in (0xc0, 0xc1, 0xc2) and pos + 7 <= len(data):
                h, w = struct.unpack('>HH', data[pos+3:pos+7])
                return w, h
            pos += length
    return None


def literal(cf, pos, op):
    if 0x02 <= op <= 0x08:
        return op - 3
    if op == 0x10:
        return struct.unpack('>b', cf.data[pos+1:pos+2])[0]
    if op == 0x11:
        return struct.unpack('>h', cf.data[pos+1:pos+3])[0]
    if op in (0x12, 0x13):
        idx = cf.data[pos+1] if op == 0x12 else int.from_bytes(cf.data[pos+1:pos+3], 'big')
        e = cf.cp[idx]
        if e and e[0] == 3:
            return struct.unpack('>i', e[1])[0]
    return None


def bytecode_evidence(suite, known):
    rects, buffers, constants = Counter(), Counter(), Counter()
    for filename in suite.classes:
        try:
            cf = ClassFile(suite.read(filename))
            class_rects, class_buffers, class_constants = set(), set(), set()
            for start, length in cf.code_ranges:
                pushed, method_constants = [], set()
                for pc, op in _walk(cf.data, start, length):
                    pos = start + pc
                    value = literal(cf, pos, op)
                    if value is not None:
                        pushed.append(value)
                        pushed = pushed[-4:]
                        method_constants.add(value)
                        continue
                    if op in (0xb6, 0xb8):
                        ref = cf.member_ref(int.from_bytes(cf.data[pos+1:pos+3], 'big'))
                        if ref and len(pushed) >= 4 and pushed[-4:-2] == [0, 0]:
                            owner, name, desc = ref
                            pair = tuple(pushed[-2:])
                            if owner == 'javax/microedition/lcdui/Graphics' and name in ('fillRect', 'setClip', 'drawRect') and desc == '(IIII)V' and pair in known:
                                class_rects.add(pair)
                        if ref == ('javax/microedition/lcdui/Image', 'createImage', '(II)Ljavax/microedition/lcdui/Image;') and len(pushed) >= 2 and tuple(pushed[-2:]) in known:
                            class_buffers.add(tuple(pushed[-2:]))
                    pushed = []  # no dataflow guessing across arithmetic, loads, branches or calls
                for w, h in known:
                    if w != h and min(w, h) >= 128 and w in method_constants and h in method_constants:
                        class_constants.add(tuple(sorted((w, h))))
            rects.update(class_rects)
            buffers.update(class_buffers)
            constants.update(class_constants)
        except (ClassFormatError, ValueError, IndexError, KeyError, TypeError, struct.error):
            continue
    return rects, buffers, constants


def detect(suite, known, device_screens):
    evidence = defaultdict(dict)
    def add(pair, family, score, reason):
        w, h = pair
        if 64 <= w <= 2048 and 64 <= h <= 2048:
            previous = evidence[pair].get(family)
            if previous is None or score > previous[0]:
                evidence[pair][family] = (score, reason)

    attrs = {key.lower(): value for key, value in suite.attrs.items()}
    for key, score in [('j2a-screen-size', 1200), ('nokia-midlet-original-display-size', 1100),
                       ('midlet-screen-size', 1000), ('nokia-midlet-target-display-size', 900)]:
        match = re.search(r'(\d{2,4})\s*[,xX*]\s*(\d{2,4})', attrs.get(key, ''))
        if match:
            add(tuple(map(int, match.groups())), 'manifest', score, key + ' (JAR/JAD)')
    # Descriptions often advertise every supported phone. Use the actual archive name/URL instead.
    names = [os.path.basename(suite.jar_path), os.path.basename(suite.jad_path or ''), attrs.get('midlet-jar-url', '')]
    text = ' '.join(names).lower()
    for match in re.finditer(r'(?<!\d)(\d{2,4})\s*[x*_-]\s*(\d{2,4})(?!\d)', text):
        pair = tuple(map(int, match.groups()))
        if 'x' in match.group(0) or '*' in match.group(0) or pair in known:
            add(pair, 'filename', 80, 'resolución en nombre del archivo')
    model_text = re.sub(r'(nokia|sony[-_ .]*ericsson|motorola)[_ .-]*', ' ', text)
    for w, h, models in device_screens:
        for model in models.split():
            if re.search(r'(?<![a-z0-9])' + re.escape(model) + r'(?![a-z0-9])', model_text):
                add((w,h), 'model', 40, 'modelo ' + model)
    icons = {attrs.get('midlet-icon', '').lstrip('/').lower()}
    icons.update(m['icon'].lstrip('/').lower() for m in suite.midlets())
    images, backgrounds = Counter(), Counter()
    for entry in suite.entries:
        name = entry.filename.lower()
        if name.endswith('.class') or name in icons or re.search(r'(?:^|[/_.-])(icon|icons|font|fonts|sprite|sprites|spritesheet|atlas|tiles|tileset)(?:[/_.-]|$)', name):
            continue
        try:
            with suite.zip.open(entry) as stream:
                pair = image_size(stream.read(65536))
            if pair in known:
                images[pair] += 1
                if re.search(r'(background|splash|loading|title|menu|(?:^|/)bg)', name):
                    backgrounds[pair] += 1
        except (OSError, ValueError, KeyError, struct.error):
            continue
    for pair, count in images.items():
        # A lone square image is usually an atlas/icon, not evidence of a square screen.
        if pair[0] == pair[1] and not backgrounds[pair]:
            continue
        score = min(50, 16 + 9 * count + (12 if backgrounds[pair] else 0))
        add(pair, 'images', score, '%d imagen(es) de pantalla%s' % (count, ', con fondo/menú' if backgrounds[pair] else ''))
    rects, buffers, constants = bytecode_evidence(suite, set(known))
    for pair, count in rects.items():
        add(pair, 'draw', min(125, 80 + 15 * count), 'área Graphics 0,0,%d,%d en %d clase(s)' % (*pair, count))
    for pair, count in buffers.items():
        if pair[0] != pair[1]:
            add(pair, 'buffer', min(55, 30 + 5 * count), 'buffer Image.createImage %dx%d' % pair)
    for pair, count in constants.items():
        w, h = pair
        direct = [p for p in evidence if tuple(sorted(p)) == pair]
        candidates = direct or [(w,h)]
        for candidate in candidates:
            add(candidate, 'constants', min(18, 4 * count), 'constantes de ancho/alto en el mismo método')
    ranked = []
    for (w,h), groups in evidence.items():
        score = sum(value[0] for value in groups.values())
        ranked.append({'width':w, 'height':h, 'score':score,
                       'reasons':[value[1] for value in sorted(groups.values(), reverse=True)],
                       'sources':list(groups)})
    ranked.sort(key=lambda x: (-x['score'], -len(x['sources']), x['width'], x['height']))
    if not ranked:
        return {'width':240, 'height':320, 'confidence':'baja', 'reason':'sin evidencia; valor de compatibilidad 240x320', 'candidates':[]}
    best = ranked[0]
    margin = best['score'] - ranked[1]['score'] if len(ranked) > 1 else best['score']
    confidence = 'alta' if best['score'] >= 900 or (best['score'] >= 110 and margin >= 30) else 'media' if best['score'] >= 60 and margin >= 15 else 'baja'
    return dict(width=best['width'], height=best['height'], confidence=confidence,
                reason='; '.join(best['reasons']), candidates=ranked)
