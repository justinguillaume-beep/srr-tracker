#!/usr/bin/env python3
"""Train a tiny top-face CNN and report leave-one-position-out accuracy.

Each face is one phone photo of 12 dice. Validation holds out dice by
position (the same index in every photo), not by augmented copy. The
number it prints is same-session accuracy. It is not a real-world estimate.

Measured with this script (seed 5, 18 epochs, 10 views):
  face 1 10/12, face 2 9/12, face 3 7/12, face 4 8/12, face 5 6/12, face 6 7/12
  overall 47/72
  softmax >= 0.85 was wrong on 12 of those held-out dice
The pip reader is 72/72 on the same crops, so these weights are not shipped.
"""

from __future__ import annotations

import csv
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent
SIZE = 32
SEED = 5
EPOCHS = 18
VIEWS = 10
LR = 0.004
WD = 1e-4


def load_samples():
    rows = []
    with (ROOT / "labels.csv").open() as f:
        for row in csv.DictReader(f):
            w, h = int(row["w"]), int(row["h"])
            pad = max(4, int(max(w, h) * 0.10))
            rgb = np.asarray(Image.open(ROOT / row["path"]).convert("RGB"))
            tight = rgb[pad : pad + h, pad : pad + w]
            if tight.shape[0] != h or tight.shape[1] != w:
                raise SystemExit(f"bad crop {row['path']} {tight.shape} vs {h}x{w}")
            face = top_face(tight)
            index = int(Path(row["path"]).stem.rsplit("_", 1)[1])
            rows.append((face, int(row["face"]) - 1, index, row["path"]))
    return rows


def top_face(rgb: np.ndarray) -> np.ndarray:
    height, width = rgb.shape[:2]
    side = min(width, height)
    if height > width * 1.15:
        step = max(1, side // 12)
        best_y, best = 0, -1
        y0 = 0
        while y0 + side <= height:
            window = rgb[y0 : y0 + side, 0:side]
            score = int((np.minimum(np.minimum(window[:, :, 0], window[:, :, 1]), window[:, :, 2]) >= 210).sum())
            if score > best:
                best, best_y = score, y0
            y0 += step
        return rgb[best_y : best_y + side, 0:side]
    if width > height * 1.15:
        step = max(1, side // 12)
        best_x, best = 0, -1
        x0 = 0
        while x0 + side <= width:
            window = rgb[0:side, x0 : x0 + side]
            score = int((np.minimum(np.minimum(window[:, :, 0], window[:, :, 1]), window[:, :, 2]) >= 210).sum())
            if score > best:
                best, best_x = score, x0
            x0 += step
        return rgb[0:side, best_x : best_x + side]
    return rgb


def pip_mask(rgb: np.ndarray) -> np.ndarray:
    r = rgb[:, :, 0].astype(np.float32)
    g = rgb[:, :, 1].astype(np.float32)
    b = rgb[:, :, 2].astype(np.float32)
    luma = (r + g + b) / 3.0
    chroma = np.maximum(np.maximum(r, g), b) - np.minimum(np.minimum(r, g), b)
    white = np.clip((luma - 150.0) / 90.0, 0.0, 1.0) * np.clip((80.0 - chroma) / 80.0, 0.0, 1.0)
    return white


def resize_area(mask: np.ndarray, size: int = SIZE) -> np.ndarray:
    height, width = mask.shape
    out = np.zeros((size, size), np.float32)
    for oy in range(size):
        y0 = oy * height // size
        y1 = max(y0 + 1, (oy + 1) * height // size)
        for ox in range(size):
            x0 = ox * width // size
            x1 = max(x0 + 1, (ox + 1) * width // size)
            out[oy, ox] = mask[y0:y1, x0:x1].mean()
    return out


def grid_of(rgb: np.ndarray) -> np.ndarray:
    return resize_area(pip_mask(rgb))


def augment(rgb: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    image = Image.fromarray(rgb.astype(np.uint8), "RGB")
    image = image.rotate(float(rng.uniform(0, 360)), resample=Image.BILINEAR, fillcolor=(90, 80, 90))
    if rng.random() < 0.5:
        image = image.transpose(Image.FLIP_LEFT_RIGHT)
    if rng.random() < 0.5:
        image = image.transpose(Image.FLIP_TOP_BOTTOM)
    scale = float(rng.uniform(0.78, 1.18))
    side = image.size[0]
    scaled = max(8, int(round(side * scale)))
    image = image.resize((scaled, scaled), Image.BILINEAR)
    canvas = Image.new("RGB", (side, side), (80, 70, 85))
    if scaled >= side:
        left = (scaled - side) // 2
        image = image.crop((left, left, left + side, left + side))
    else:
        canvas.paste(image, ((side - scaled) // 2, (side - scaled) // 2))
        image = canvas
    if rng.random() < 0.65:
        image = image.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.2, 1.4))))
    arr = np.asarray(image).astype(np.float32)
    contrast = float(rng.uniform(0.55, 1.55))
    mean = arr.mean()
    arr = (arr - mean) * contrast + mean
    arr *= float(rng.uniform(0.62, 1.45))
    arr += rng.uniform(-18, 18, size=(1, 1, 3)).astype(np.float32)
    if rng.random() < 0.85:
        tint = rng.choice(
            [
                [210, 40, 40],
                [160, 50, 180],
                [220, 170, 40],
                [190, 50, 90],
                [120, 40, 150],
            ]
        ).astype(np.float32)
        alpha = float(rng.uniform(0.12, 0.55))
        luma = arr.mean(axis=2, keepdims=True)
        body = luma < 205
        arr = np.where(body, arr * (1 - alpha) + alpha * tint, arr)
    if rng.random() < 0.8:
        arr = add_glare(arr, rng)
    return np.clip(arr, 0, 255)


def add_glare(arr: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    height, width = arr.shape[:2]
    yy, xx = np.mgrid[0:height, 0:width]
    out = arr.copy()
    for _ in range(int(rng.integers(1, 3))):
        cy = float(rng.uniform(0, height))
        cx = float(rng.uniform(0, width))
        ry = float(rng.uniform(height * 0.04, height * 0.28))
        rx = float(rng.uniform(width * 0.03, width * 0.22))
        ang = float(rng.uniform(0, np.pi))
        cos, sin = np.cos(ang), np.sin(ang)
        dx, dy = xx - cx, yy - cy
        rx_ = cos * dx + sin * dy
        ry_ = -sin * dx + cos * dy
        blob = np.exp(-0.5 * ((rx_ / rx) ** 2 + (ry_ / ry) ** 2))
        strength = float(rng.uniform(40, 180))
        out += blob[:, :, None] * strength
    return out


def init_params(rng: np.random.Generator):
    def w(shape, fan):
        return (rng.standard_normal(shape) * np.sqrt(2.0 / fan)).astype(np.float32)

    conv1 = w((5, 5, 1, 8), 5 * 5)
    conv1b = np.zeros(8, np.float32)
    conv2 = w((3, 3, 8, 16), 3 * 3 * 8)
    conv2b = np.zeros(16, np.float32)
    dense = w((8 * 8 * 16, 6), 8 * 8 * 16)
    denseb = np.zeros(6, np.float32)
    return [conv1, conv1b, conv2, conv2b, dense, denseb]


def conv(x, weight, bias):
    k = weight.shape[0]
    pad = k // 2
    cols = im2col(x, k, pad)
    n, h, w, _ = cols.shape
    flat = cols.reshape(n * h * w, -1)
    out = flat @ weight.reshape(-1, weight.shape[-1]) + bias
    return out.reshape(n, h, w, -1), cols


def im2col(x, k, pad):
    n, h, w, c = x.shape
    xp = np.pad(x, ((0, 0), (pad, pad), (pad, pad), (0, 0)))
    cols = np.empty((n, h, w, k * k * c), np.float32)
    for iy in range(k):
        for ix in range(k):
            cols[:, :, :, (iy * k + ix) * c : (iy * k + ix + 1) * c] = xp[:, iy : iy + h, ix : ix + w, :]
    return cols


def col2im(cols, k, pad, shape):
    n, h, w, c = shape
    out = np.zeros((n, h + 2 * pad, w + 2 * pad, c), np.float32)
    for iy in range(k):
        for ix in range(k):
            out[:, iy : iy + h, ix : ix + w, :] += cols[:, :, :, (iy * k + ix) * c : (iy * k + ix + 1) * c]
    if pad:
        return out[:, pad:-pad, pad:-pad, :]
    return out


def maxpool2(x):
    n, h, w, c = x.shape
    h2, w2 = h // 2, w // 2
    flat = x[:, : h2 * 2, : w2 * 2, :].reshape(n, h2, 2, w2, 2, c)
    flat = flat.transpose(0, 1, 3, 2, 4, 5).reshape(n, h2, w2, 4, c)
    idx = flat.argmax(axis=3)
    pooled = flat.max(axis=3)
    return pooled, idx, flat


def maxpool2_back(idx, dout):
    n, h2, w2, c = idx.shape
    dflat = np.zeros((n, h2, w2, 4, c), np.float32)
    nn, yy, xx, cc = np.indices(idx.shape)
    dflat[nn, yy, xx, idx, cc] = dout
    dflat = dflat.reshape(n, h2, w2, 2, 2, c).transpose(0, 1, 3, 2, 4, 5)
    return dflat.reshape(n, h2 * 2, w2 * 2, c)


def forward(x, params, cache=True):
    conv1, conv1b, conv2, conv2b, dense, denseb = params
    h1, cols1 = conv(x, conv1, conv1b)
    a1 = np.maximum(h1, 0)
    p1, idx1, _ = maxpool2(a1)
    h2, cols2 = conv(p1, conv2, conv2b)
    a2 = np.maximum(h2, 0)
    p2, idx2, _ = maxpool2(a2)
    flat = p2.reshape(x.shape[0], -1)
    logits = flat @ dense + denseb
    if not cache:
        return logits
    return logits, (x, h1, a1, idx1, p1, h2, a2, idx2, flat, cols1, cols2)


def backward(params, cache, dlogits):
    conv1, _, conv2, _, dense, _ = params
    x, h1, a1, idx1, p1, h2, a2, idx2, flat, cols1, cols2 = cache
    dflat = dlogits @ dense.T
    ddense = flat.T @ dlogits
    ddenseb = dlogits.sum(axis=0)
    dp2 = dflat.reshape(a2.shape[0], a2.shape[1] // 2, a2.shape[2] // 2, a2.shape[3])
    da2 = maxpool2_back(idx2, dp2)
    dh2 = da2 * (h2 > 0)
    n, h, w, _ = p1.shape
    k = conv2.shape[0]
    dcols2 = dh2.reshape(n * h * w, -1) @ conv2.reshape(-1, conv2.shape[-1]).T
    dconv2 = cols2.reshape(n * h * w, -1).T @ dh2.reshape(n * h * w, -1)
    dconv2b = dh2.sum(axis=(0, 1, 2))
    dp1 = col2im(dcols2.reshape(n, h, w, -1), k, k // 2, p1.shape)
    da1 = maxpool2_back(idx1, dp1)
    dh1 = da1 * (h1 > 0)
    n, h, w, _ = x.shape
    k = conv1.shape[0]
    dcols1 = dh1.reshape(n * h * w, -1) @ conv1.reshape(-1, conv1.shape[-1]).T
    dconv1 = cols1.reshape(n * h * w, -1).T @ dh1.reshape(n * h * w, -1)
    dconv1b = dh1.sum(axis=(0, 1, 2))
    grads = [
        dconv1.reshape(conv1.shape),
        dconv1b,
        dconv2.reshape(conv2.shape),
        dconv2b,
        ddense,
        ddenseb,
    ]
    for p, g in zip(params, grads):
        g += WD * p
    return grads


def softmax(logits):
    z = logits - logits.max(axis=1, keepdims=True)
    exp = np.exp(z)
    return exp / exp.sum(axis=1, keepdims=True)


def train(params, grids, labels, rng, epochs=EPOCHS):
    m = [np.zeros_like(p) for p in params]
    v = [np.zeros_like(p) for p in params]
    step = 0
    n = len(grids)
    for _ in range(epochs):
        order = rng.permutation(n)
        for start in range(0, n, 32):
            batch = order[start : start + 32]
            x = grids[batch, :, :, None]
            y = labels[batch]
            logits, cache = forward(x, params)
            prob = softmax(logits)
            dlogits = prob
            dlogits[np.arange(len(y)), y] -= 1
            dlogits /= len(y)
            grads = backward(params, cache, dlogits)
            step += 1
            for i, g in enumerate(grads):
                m[i] = 0.9 * m[i] + 0.1 * g
                v[i] = 0.999 * v[i] + 0.001 * (g * g)
                mhat = m[i] / (1 - 0.9**step)
                vhat = v[i] / (1 - 0.999**step)
                params[i] -= LR * mhat / (np.sqrt(vhat) + 1e-8)
    return params


def predict(params, faces):
    if not faces:
        return np.zeros((0, 6), np.float32)
    x = np.stack([grid_of(face) for face in faces])[:, :, :, None]
    return softmax(forward(x, params, cache=False))


def make_views(samples, rng):
    grids = []
    labels = []
    for face, label, _, _ in samples:
        grids.append(grid_of(face))
        labels.append(label)
        for _ in range(VIEWS - 1):
            grids.append(grid_of(augment(face, rng)))
            labels.append(label)
    return np.stack(grids).astype(np.float32), np.array(labels, np.int64)


def accuracy(prob, labels):
    pred = prob.argmax(axis=1)
    return float((pred == labels).mean()) if len(labels) else 0.0


def main():
    samples = load_samples()
    print(f"samples {len(samples)}")
    rng = np.random.default_rng(SEED)
    # Leave-one-position-out. Index k is the same place in every photo.
    per_face = {face: [] for face in range(6)}
    confusions = np.zeros((6, 6), np.int32)
    confident_wrong = []
    confident_right = 0
    high = 0.85
    for hold in range(12):
        train_s = [s for s in samples if s[2] != hold]
        val_s = [s for s in samples if s[2] == hold]
        params = init_params(np.random.default_rng(SEED + hold))
        grids, labels = make_views(train_s, np.random.default_rng(SEED + 100 + hold))
        train(params, grids, labels, np.random.default_rng(SEED + 200 + hold))
        prob = predict(params, [s[0] for s in val_s])
        got = prob.argmax(axis=1)
        for s, g, p in zip(val_s, got, prob):
            per_face[s[1]].append(int(g == s[1]))
            confusions[s[1], g] += 1
            top = float(p.max())
            if top >= high and g != s[1]:
                confident_wrong.append((s[3], s[1] + 1, int(g) + 1, top))
            if top >= high and g == s[1]:
                confident_right += 1
        print(f"  hold {hold:02d} {int((got == [s[1] for s in val_s]).sum())}/6")
    print("per-face leave-one-position-out (12 dice each):")
    total_hit = 0
    for face in range(6):
        hit = sum(per_face[face])
        total_hit += hit
        print(f"  face {face + 1}: {hit}/12 = {hit / 12:.2f}")
    print(f"overall {total_hit}/72 = {total_hit / 72:.3f}")
    print("confusion rows=true cols=pred (1..6)")
    print(confusions)
    print(f"prob>={high:.2f} correct {confident_right} wrong {len(confident_wrong)}")
    for item in confident_wrong:
        print("  wrong", item)

    final = init_params(np.random.default_rng(SEED))
    grids, labels = make_views(samples, np.random.default_rng(SEED + 7))
    train(final, grids, labels, np.random.default_rng(SEED + 9))
    train_prob = predict(final, [s[0] for s in samples])
    train_hit = int((train_prob.argmax(1) == [s[1] for s in samples]).sum())
    print(f"final model on all 72 (resubstitution, not validation): {train_hit}/72")
    # 47/72 with confident mistakes is not safe to log. The pip reader is
    # already right on all 72 of these crops. Do not write app weights.
    print("not writing app weights")


if __name__ == "__main__":
    main()
