"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio) and the 3D colour pairs (CIE L*)."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
def L(h): y = Y(h); return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16
GLASS = "#B4B3B1"  # darkest measured window glass (royal-ur / factory measure)
pairs = [
    ("正文 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("第 3 行 / 结果卡 Ink / TagFace", "#1B1F23", "#FBF6EC", 4.5), ("确认页次要字 / TagFace", "#3A3F44", "#FBF6EC", 4.5),
    ("主按钮 / 开关开 白 / Primary", "#FFFFFF", "#1E6B66", 4.5), ("退出 / 确定 白 / Error", "#FFFFFF", "#B3261E", 4.5),
    ("新纪录 白 / Primary", "#FFFFFF", "#1E6B66", 4.5), ("怎么玩序号 白 / Primary", "#FFFFFF", "#1E6B66", 4.5),
    ("烙印号 榉木（浅）", "#4A2E18", "#E2C498", 3.0), ("烙印号 榉木（深纹）", "#4A2E18", "#C49E6C", 3.0),
    ("烙印号 樱桃（浅）", "#F2DDB8", "#B0623A", 3.0), ("烙印号 樱桃（深纹）", "#F2DDB8", "#864224", 3.0),
    ("烙印号 胡桃（浅纹）", "#E8D2A8", "#6A472C", 3.0), ("烙印号 胡桃（深纹）", "#E8D2A8", "#462C19", 3.0),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
print()
lp = [("琥珀箭头 vs 榉木", "#E8A33D", "#D9B98C", 0), ("琥珀箭头 vs 樱桃", "#E8A33D", "#A0522D", 15), ("琥珀箭头 vs 胡桃", "#E8A33D", "#5A3A22", 25),
      ("金描边 vs 榉木", "#F3D27A", "#D9B98C", 0), ("金描边 vs 胡桃", "#F3D27A", "#5A3A22", 25),
      ("朱红槽口 vs 榉木", "#C0392B", "#D9B98C", 15), ("朱红槽口 vs 樱桃", "#C0392B", "#A0522D", 0), ("朱红槽口 vs 胡桃", "#C0392B", "#5A3A22", 10)]
print("| 3D 对比 | A | B | ΔL* | 门槛 |\n|---|---|---|---|---|")
for n, a, b, t in lp:
    print(f"| {n} | {a} | {b} | {abs(L(a) - L(b)):.1f} | {'≥ %d' % t if t else '— 色相区分'} |")
