"""Generates site/web/public/bg-roads.svg: a night street map in the app's colours
with an amber route, drawn procedurally so it isn't a copy of any real map tile."""
import math, random, sys
random.seed(7)
W, H = 1600, 1000
out = []
def warp(x, y):
    return (x + 26*math.sin(y/170.0) + 14*math.sin((x+y)/90.0), y + 22*math.sin(x/210.0))
def poly(points):
    p = [warp(x, y) for x, y in points]
    d = f"M{p[0][0]:.0f} {p[0][1]:.0f}" + "".join(f"L{x:.0f} {y:.0f}" for x, y in p[1:])
    return d
# water body lower left
out.append('<path d="M0 760 C220 700 380 820 560 900 C640 940 700 1000 700 1000 L0 1000Z" fill="#0A1A26"/>')
# minor streets: two skewed families
streets = []
for i in range(-6, 40):
    x0 = i * 52 + random.uniform(-8, 8)
    streets.append([(x0 + t*0.18*H/10, t*H/10) for t in range(0, 11)])
for j in range(-2, 26):
    y0 = j * 46 + random.uniform(-6, 6)
    streets.append([(t*W/12, y0 + t*W/12*0.12) for t in range(0, 13)])
d = " ".join(poly(s) for s in streets if random.random() > 0.12)
out.append(f'<path d="{d}" stroke="#1E2A35" stroke-width="2" fill="none"/>')
# main roads
mains = [[(0, 300), (400, 360), (800, 330), (1200, 420), (1600, 400)],
         [(250, 0), (300, 300), (380, 600), (420, 1000)],
         [(1100, 0), (1040, 300), (1000, 640), (930, 1000)],
         [(0, 620), (500, 600), (900, 690), (1600, 720)]]
for m in mains:
    pts = []
    for (a, b), (c, e) in zip(m, m[1:]):
        for t in range(10):
            pts.append((a + (c-a)*t/10, b + (e-b)*t/10))
    pts.append(m[-1])
    out.append(f'<path d="{poly(pts)}" stroke="#2C3A47" stroke-width="7" fill="none" stroke-linecap="round"/>')
# route (amber with dark case) from lower left to upper right along streets
route = [(330, 940), (365, 700), (520, 650), (700, 640), (760, 520), (1010, 470), (1040, 300), (1300, 250)]
pts = []
for (a, b), (c, e) in zip(route, route[1:]):
    for t in range(12):
        pts.append((a + (c-a)*t/12, b + (e-b)*t/12))
pts.append(route[-1])
rd = poly(pts)
out.append(f'<path d="{rd}" stroke="#FFB020" stroke-opacity=".18" stroke-width="30" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
out.append(f'<path d="{rd}" stroke="#3B2804" stroke-width="13" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
out.append(f'<path d="{rd}" stroke="#FFB020" stroke-width="8" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
ex, ey = warp(*route[-1]); sx, sy = warp(*route[0])
out.append(f'<circle cx="{ex:.0f}" cy="{ey:.0f}" r="15" fill="#FFB020" stroke="#fff" stroke-width="5"/><circle cx="{ex:.0f}" cy="{ey:.0f}" r="5" fill="#0E151C"/>')
out.append(f'<circle cx="{sx:.0f}" cy="{sy:.0f}" r="11" fill="#FFB020" stroke="#fff" stroke-width="4"/>')
# parking pins near the destination
for dx, dy in [(-60, -46), (52, -58), (40, 50)]:
    x, y = ex + dx, ey + dy
    out.append(f'<rect x="{x-13:.0f}" y="{y-13:.0f}" width="26" height="26" rx="7" fill="#FFB020"/><text x="{x:.0f}" y="{y+6:.0f}" font-family="Arial" font-weight="700" font-size="17" text-anchor="middle" fill="#1A1203">P</text>')
svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" preserveAspectRatio="xMidYMid slice"><rect width="{W}" height="{H}" fill="#0E151C"/>{"".join(out)}</svg>'
open(sys.argv[1], "w").write(svg)
print(len(svg), "bytes")
