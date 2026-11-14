# Rasterise KOSTAT 2012 province polygons (github.com/southkorea/southkorea-maps, kostat/2012/json/provinces-geo-simple.json)
# into KoreaBoard.GRID. Usage: python3 scripts/raster_provinces.py provinces-geo-simple.json 10 62 126.0 38.62 129.62 33.15
# Then drop one-tile islands with no orthogonal land neighbour. Metro areas win a tile at 25% cover so they keep a shape.
import json, math, sys
from matplotlib.path import Path
src, K, SH = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
kl = 111.195 * math.cos(math.radians(36)); KL = 111.195
LON_LEFT, LAT_TOP, LON_RIGHT, LAT_BOT = map(float, sys.argv[4:8])
OX = (LON_LEFT - 127) * kl; OY = (LAT_TOP - 36) * KL
COLS = math.ceil(((LON_RIGHT - LON_LEFT) * kl) / K)
ROWS = math.ceil((((LAT_TOP - LAT_BOT) * KL) - SH) / K)
ZONE = {'서울특별시':'S','인천광역시':'I','경기도':'G','강원도':'K','세종특별자치시':'X','대전광역시':'D','충청북도':'B','충청남도':'N',
        '광주광역시':'Q','전라북도':'J','전라남도':'M','대구광역시':'T','경상북도':'E','울산광역시':'U','경상남도':'W','부산광역시':'P','제주특별자치도':'Z'}
METRO = 'SIXDQTUP'
d = json.load(open(src))
paths = []
for f in d['features']:
    z = ZONE[f['properties']['name']]
    g = f['geometry']; polys = g['coordinates'] if g['type'] == 'MultiPolygon' else [g['coordinates']]
    for poly in polys:
        ring = poly[0]
        pts = [((lon - 127) * kl, (lat - 36) * KL) for lon, lat in ring]
        if len(pts) < 8: continue
        paths.append((z, Path(pts)))
# Metro areas sit in holes of their province, which the outer rings ignore, so test them first.
paths.sort(key=lambda zp: zp[0] not in METRO)
N = 5
grid = []
for r in range(ROWS):
    line = ''
    for c in range(COLS):
        votes = {}
        for i in range(N):
            for j in range(N):
                x = OX + (c + (i + .5) / N) * K
                y = OY - (r + (j + .5) / N) * K
                for jeju in (False, True):
                    yy = y - SH if jeju else y
                    for z, p in paths:
                        if (z == 'Z') != jeju: continue
                        if p.contains_point((x, yy)):
                            votes[z] = votes.get(z, 0) + 1; break
        total = sum(votes.values())
        pick = max(votes, key=votes.get) if votes else '.'
        for small in METRO:
            if votes.get(small, 0) >= N * N * .25: pick = small
        line += pick if total >= N * N * .35 else '.'
    grid.append(line)
print(COLS, ROWS, file=sys.stderr)
print('\n'.join(grid))
