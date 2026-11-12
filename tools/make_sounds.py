"""Derived Luban Lock sounds (profile D51: real CC0 recordings for every wooden event; design sound.list).

The base clips in app/src/main/assets/sounds are the cleaned CC0 recordings imported for Klotski Box
(freesound 254343 wood puzzle slide by cmorris035 + Kenney impact-sounds, see apps/luban-lock/build/sound-sources.md).
This script only cuts, re-pitches (resampling) and layers those recordings; nothing is synthesised except the existing
"solved" UI chime.

  python -X utf8 tools/make_sounds.py

  click   咔哒  装进去: light knock + the slide recording's click-into-place 45 ms later
  click_2       the same pair, the other light knock
  whoosh  嗖    整根脱出: slide_3 played 1.35x faster (shorter, brighter), 12 ms fade in
  whoosh_2      slide played 1.25x faster
  apart   木鱼两声 拆开了: medium knock, then the same knock a tone lower 220 ms later
  puff    呼    拆开看 / 合上: slide_2 played 0.72x (slower, darker), -6 dB
Then every clip is peak-normalised to -1 dBFS (puff to -7 dBFS). undo.wav is not used by this app and is removed.
"""
import array
import os
import wave

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(HERE, "app", "src", "main", "assets", "sounds")
RATE = 22050


def read(name):
    w = wave.open(os.path.join(SND, name + ".wav"))
    assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2
    a = array.array("h", w.readframes(w.getnframes()))
    return [x / 32768.0 for x in a]


def write(name, x, peak_db=-1.0):
    m = max(1e-9, max(abs(v) for v in x))
    g = (10 ** (peak_db / 20.0)) / m
    out = array.array("h", [max(-32767, min(32767, int(v * g * 32767))) for v in x])
    w = wave.open(os.path.join(SND, name + ".wav"), "wb")
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(out.tobytes()); w.close()
    print(name, "%.0f ms" % (len(x) * 1000.0 / RATE))


def speed(x, k):
    """Resample by linear interpolation: k > 1 = faster / higher."""
    n = int(len(x) / k)
    out = []
    for i in range(n):
        p = i * k; j = int(p); f = p - j
        a = x[j]; b = x[j + 1] if j + 1 < len(x) else 0.0
        out.append(a + (b - a) * f)
    return out


def mix(a, b, offset):
    n = max(len(a), offset + len(b))
    out = [0.0] * n
    for i, v in enumerate(a): out[i] += v
    for i, v in enumerate(b): out[offset + i] += v
    return out


def fade(x, inn=0.003, outt=0.03):
    ni = int(inn * RATE); no = int(outt * RATE)
    x = list(x)
    for i in range(min(ni, len(x))): x[i] *= i / ni
    for i in range(min(no, len(x))): x[len(x) - 1 - i] *= i / no
    return x


def main():
    snap, snap2, snap3 = read("snap"), read("snap_2"), read("snap_3")
    block = read("block")
    slide, slide2, slide3 = read("slide"), read("slide_2"), read("slide_3")
    gap = int(0.045 * RATE)
    write("click", fade(mix(snap2, [v * 0.8 for v in snap], gap)))
    write("click_2", fade(mix(snap3, [v * 0.8 for v in snap], gap)))
    write("whoosh", fade(speed(slide3, 1.35), inn=0.012))
    write("whoosh_2", fade(speed(slide, 1.25), inn=0.012))
    write("apart", fade(mix(block, [v * 0.9 for v in speed(block, 0.89)], int(0.22 * RATE))))
    write("puff", fade(speed(slide2, 0.72), inn=0.02, outt=0.06), peak_db=-7.0)
    u = os.path.join(SND, "undo.wav")
    if os.path.exists(u):
        os.remove(u)


if __name__ == "__main__":
    main()
