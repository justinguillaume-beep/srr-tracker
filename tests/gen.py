import numpy as np, json, os, sys, math, random
from PIL import Image, ImageDraw, ImageFilter
random.seed(7); np.random.seed(7)
OUT = os.environ.get('OUT','imgs'); os.makedirs(OUT, exist_ok=True)
W, H = 1024, 768
SS = 2
PIPS = {1:[(0,0)],2:[(-1,-1),(1,1)],3:[(-1,-1),(0,0),(1,1)],4:[(-1,-1),(1,-1),(-1,1),(1,1)],
        5:[(-1,-1),(1,-1),(0,0),(-1,1),(1,1)],6:[(-1,-1),(1,-1),(-1,0),(1,0),(-1,1),(1,1)]}
FELTS = [(24,92,58),(30,110,70),(20,70,120),(110,30,40),(60,60,64),(140,100,60),(25,80,50)]
BODIES = [('white',(238,236,228),(25,25,28)),('ivory',(232,222,196),(40,30,30)),('white',(250,250,250),(10,10,10)),
          ('red',(190,30,35),(245,245,245)),('red',(170,25,30),(250,240,230)),('blue',(30,60,170),(240,240,240)),
          ('orange',(225,120,30),(250,250,250))]

def die_tile(side, body, pipc, n, rng, offs, pr_ratio, aa=SS):
    S = int(side*aa); pad = int(side*0.12*aa)
    im = Image.new('RGBA', (S+2*pad, S+2*pad), (0,0,0,0))
    # shadow handled elsewhere. body w/ rounded corners & subtle edge shading
    base = Image.new('RGBA', im.size, (0,0,0,0)); d = ImageDraw.Draw(base)
    rad = int(S*0.13)
    d.rounded_rectangle([pad,pad,pad+S,pad+S], radius=rad, fill=body+(255,))
    # bevel: darker rim
    rim = Image.new('RGBA', im.size, (0,0,0,0)); dr = ImageDraw.Draw(rim)
    dr.rounded_rectangle([pad,pad,pad+S,pad+S], radius=rad, outline=tuple(int(c*0.78) for c in body)+(255,), width=max(2,int(S*0.035)))
    base = Image.alpha_composite(base, rim)
    # gradient lighting
    yy, xx = np.mgrid[0:im.size[1], 0:im.size[0]].astype(np.float32)
    g = 1.0 + rng.uniform(-0.12,0.12)*(xx/im.size[0]-0.5)*2 + rng.uniform(-0.12,0.12)*(yy/im.size[1]-0.5)*2
    arr = np.array(base).astype(np.float32); arr[...,:3] *= g[...,None]
    base = Image.fromarray(np.clip(arr,0,255).astype(np.uint8),'RGBA')
    d = ImageDraw.Draw(base)
    pr = S*pr_ratio/2
    cx = cy = pad+S/2
    for (px,py) in PIPS[n]:
        jx, jy = rng.normal(0,S*0.006,2)
        x = cx+px*S*offs+jx; y = cy+py*S*offs+jy
        rr = pr*rng.uniform(0.95,1.05)
        # pip with slight inner shade (concave look)
        d.ellipse([x-rr,y-rr,x+rr,y+rr], fill=pipc+(255,))
        r2 = rr*0.7
        c2 = tuple(int(c*0.88+ (255-c)*0.0) for c in pipc)
        d.ellipse([x-r2,y-r2+rr*0.1,x+r2,y+r2+rr*0.1], fill=c2+(255,))
    return base

def render(spec, rng):
    felt = np.array(spec['felt'], np.float32)
    img = np.ones((H*SS, W*SS, 3), np.float32)*felt
    # felt texture noise + lighting gradient + vignette
    noise = rng.normal(0, spec['felt_noise'], (H*SS//4, W*SS//4)).astype(np.float32)
    noise = np.array(Image.fromarray(noise).resize((W*SS,H*SS), Image.BILINEAR))
    img += noise[...,None]
    yy, xx = np.mgrid[0:H*SS, 0:W*SS].astype(np.float32)
    gx, gy = spec['grad']
    light = 1 + gx*(xx/(W*SS)-0.5) + gy*(yy/(H*SS)-0.5)
    vig = 1 - spec['vig']*(((xx/(W*SS)-0.5)**2+(yy/(H*SS)-0.5)**2)*2)
    img *= (light*vig)[...,None]
    pil = Image.fromarray(np.clip(img,0,255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    # distractors: dark specks, chips of light, seam lines
    for _ in range(spec['specks']):
        x, y = rng.uniform(0,W*SS), rng.uniform(0,H*SS); r = rng.uniform(2,spec['speck_r'])*SS
        c = tuple(int(v) for v in (felt*rng.uniform(0.3,0.6)))
        if rng.random()<0.3: c = (235,235,235)
        d.ellipse([x-r,y-r*rng.uniform(.6,1),x+r,y+r], fill=c)
    for _ in range(spec['seams']):
        x0,y0 = rng.uniform(0,W*SS), rng.uniform(0,H*SS); a = rng.uniform(0,math.pi)
        d.line([x0,y0,x0+math.cos(a)*1500*SS,y0+math.sin(a)*1500*SS], fill=tuple(int(v*0.6) for v in felt), width=3*SS)
    pil = pil.convert('RGBA')
    for dd in spec['dice']:
        tile = die_tile(dd['side'], dd['body'], dd['pip'], dd['n'], rng, dd['offs'], dd['pr'])
        # shadow
        sh = Image.new('RGBA', tile.size, (0,0,0,0)); a = np.array(tile)[...,3]
        shadow = Image.fromarray((a*0.45).astype(np.uint8),'L').filter(ImageFilter.GaussianBlur(dd['side']*0.06*SS))
        shim = Image.new('RGBA', tile.size, (0,0,0,255)); shim.putalpha(shadow)
        # perspective squash + rotation
        sx, sy = dd['squash']
        def tf(t):
            t = t.resize((max(1,int(t.size[0]*sx)), max(1,int(t.size[1]*sy))), Image.BICUBIC)
            return t.rotate(dd['rot'], resample=Image.BICUBIC, expand=True)
        tile_t, shim_t = tf(tile), tf(shim)
        cx, cy = dd['cx']*SS, dd['cy']*SS
        off = int(dd['side']*0.05*SS)
        pil.alpha_composite(shim_t, (int(cx-shim_t.size[0]/2)+off, int(cy-shim_t.size[1]/2)+off)) if (0<=cx-shim_t.size[0]/2+off and cx+shim_t.size[0]/2+off<W*SS and 0<=cy-shim_t.size[1]/2+off and cy+shim_t.size[1]/2+off<H*SS) else None
        pil.alpha_composite(tile_t, (int(cx-tile_t.size[0]/2), int(cy-tile_t.size[1]/2)))
    pil = pil.convert('RGB').resize((W,H), Image.LANCZOS)
    if spec['blur']>0: pil = pil.filter(ImageFilter.GaussianBlur(spec['blur']))
    arr = np.array(pil).astype(np.float32)
    arr += rng.normal(0, spec['noise'], arr.shape)
    return Image.fromarray(np.clip(arr,0,255).astype(np.uint8))

def make_spec(cat, rng):
    n1, n2 = int(rng.integers(1,7)), int(rng.integers(1,7))
    hard = cat in ('hard',)
    if cat=='small': side = rng.uniform(60,95)
    elif cat=='large': side = rng.uniform(190,260)
    else: side = rng.uniform(100,190)
    body = BODIES[int(rng.integers(0,len(BODIES)))]
    if cat=='white': body = BODIES[int(rng.choice([0,1,2]))]
    if cat=='colored': body = BODIES[int(rng.integers(3,len(BODIES)))]
    sides=[side, side*rng.uniform(0.97,1.03)]
    # positions
    while True:
        c = [(rng.uniform(side*1.1, W-side*1.1), rng.uniform(side*1.1, H-side*1.1)) for _ in range(2)]
        dist = math.hypot(c[0][0]-c[1][0], c[0][1]-c[1][1])
        mn = side*1.45 if cat!='touching' else side*1.08
        mx = side*1.7 if cat=='touching' else 9999
        if mn<=dist<=mx: break
    squash = (rng.uniform(0.9,1.0), rng.uniform(0.9,1.0)) if cat in ('hard','small') else (1,1)
    dice=[]
    for i,(n) in enumerate((n1,n2)):
        dice.append(dict(side=sides[i], body=body[1], pip=body[2], n=n, cx=c[i][0], cy=c[i][1],
                         rot=float(rng.uniform(0,360)), offs=float(rng.uniform(0.255,0.29)), pr=float(rng.uniform(0.17,0.21)),
                         squash=squash))
    spec = dict(dice=dice, felt=FELTS[int(rng.integers(0,len(FELTS)))], felt_noise=float(rng.uniform(2,6)),
        grad=(float(rng.uniform(-.25,.25)),float(rng.uniform(-.25,.25))), vig=float(rng.uniform(0,.3)),
        specks=0, speck_r=6, seams=0, blur=0.0, noise=2.0)
    if cat in('hard','small'):
        spec.update(specks=int(rng.integers(10,40)), speck_r=float(rng.uniform(5,11)), seams=int(rng.integers(0,3)),
                    blur=float(rng.uniform(0.6,1.6)), noise=float(rng.uniform(4,9)))
    if cat=='touching': spec.update(blur=float(rng.uniform(0,.8)), noise=3.0)
    return spec, (n1,n2)

cats = {'white':40,'colored':40,'mixed':40,'large':20,'small':30,'touching':30,'hard':50}
rng = np.random.default_rng(int(sys.argv[1]) if len(sys.argv)>1 else 11)
labels = []
k=0
for cat,cnt in cats.items():
    for _ in range(cnt):
        spec,(a,b) = make_spec(cat, rng)
        im = render(spec, rng)
        fn = f'{OUT}/{k:04d}.jpg'
        im.save(fn, quality=int(rng.integers(70,92)))
        raw = np.array(Image.open(fn).convert('RGBA'))
        raw.tofile(f'{OUT}/{k:04d}.rgba')
        labels.append(dict(file=f'{k:04d}', cat=cat, d1=a, d2=b, total=a+b, w=W, h=H,
                           body=('light' if spec['dice'][0]['body'][0]>200 and spec['dice'][0]['body'][1]>200 else 'colored')))
        k+=1
json.dump(labels, open(f'{OUT}/labels.json','w'))
print(k,'images')
