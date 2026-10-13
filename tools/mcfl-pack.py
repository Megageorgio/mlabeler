"""Packs an exported MCFL model (https://github.com/Megageorgio/MCFL, the package folder with model.onnx) into the
one-file .mcfl format that mLabeler runs by itself (core/.../mcfl/Mcfl.kt), and checks the result.

usage: python tools/mcfl-pack.py <package folder> <out.mcfl> [--name NAME] [--check audio.wav ...]

Needs numpy and onnx (onnxruntime too for --check). The big matrices are kept as float16, the rest as float32;
the batch norm at the end is folded into the output convolutions (the others are already folded by the export).

File: "MCFL", uint32 version, uint32 length of the JSON header, the header (UTF-8), zero padding to 4 bytes, then the
tensors (little-endian) at the offsets the header gives, counted from the start of the data.
"""
import argparse
import json
import math
import os
import struct
import sys

import numpy as np

SR, N_FFT, HOP = 16000, 400, 160
SHARED = ["AP", "SP", "cl", "vf", "exh"]


def read_package(pkg):
    import onnx
    from onnx import numpy_helper as nh
    m = onnx.load(os.path.join(pkg, "model.onnx"))
    init = {i.name: nh.to_array(i) for i in m.graph.initializer}
    convs = {n.name: n for n in m.graph.node if n.op_type == "Conv"}

    def attr(n, name):
        return next(onnx.helper.get_attribute_value(a) for a in n.attribute if a.name == name)

    def wb(name):
        n = convs[name]
        return init[n.input[1]], (init[n.input[2]] if len(n.input) > 2 else None), n

    t = {}
    t["mel_fb"] = init["net.frontend.fb"]
    sub = next(n for n in m.graph.node if n.name == "/net/frontend/Sub")
    div = next(n for n in m.graph.node if n.name == "/net/frontend/Div")
    t["mel_mean"] = init[sub.input[1]].reshape(-1)
    t["mel_std"] = init[div.input[1]].reshape(-1)
    w, b, n = wb("/net/stem/stem.0/Conv")
    assert attr(n, "strides") == [2] and attr(n, "pads") == [2, 2], "unexpected stem"
    t["stem_w"], t["stem_b"] = w, b
    t["lang"] = init["net.lang.weight"]
    dil, i = [], 0
    while f"/net/blocks.{i}/dw/Conv" in convs:
        w, b, n = wb(f"/net/blocks.{i}/dw/Conv")
        dil.append(attr(n, "dilations")[0])
        t[f"b{i}.dw_w"], t[f"b{i}.dw_b"] = w[:, 0, :], b
        t[f"b{i}.pw1_w"], t[f"b{i}.pw1_b"] = init[f"net.blocks.{i}.pw1.weight"][:, :, 0], init[f"net.blocks.{i}.pw1.bias"]
        t[f"b{i}.pw2_w"], t[f"b{i}.pw2_b"] = init[f"net.blocks.{i}.pw2.weight"][:, :, 0], init[f"net.blocks.{i}.pw2.bias"]
        i += 1
    # the last batch norm folded into the two output convolutions
    s = init["net.norm.weight"] / np.sqrt(init["net.norm.running_var"] + 1e-5)
    sh = init["net.norm.bias"] - init["net.norm.running_mean"] * s
    for k in ("cls", "off"):
        w, b = init[f"net.{k}.weight"][:, :, 0], init[f"net.{k}.bias"]
        t[f"{k}_w"], t[f"{k}_b"] = w * s[None, :], b + w @ sh
    # the STFT of the export must be the one computed here (a periodic Hann window, as in MCFL)
    assert np.abs(init["net.frontend.stft"][:, 0, :] - stft_kernel()).max() < 1e-4, "unexpected STFT kernel"

    j = lambda f: os.path.join(pkg, f)  # noqa: E731
    cfg = json.load(open(j("config.json"), encoding="utf-8"))
    labels = [x.strip() for x in open(j("labels.txt"), encoding="utf-8") if x.strip()]
    langs = sorted(((l.split(",")[0], int(l.split(",")[1])) for l in open(j("langs.txt"), encoding="utf-8") if l.strip()),
                   key=lambda x: x[1])
    assert [i for _, i in langs] == list(range(len(langs)))
    meta = {
        "sr": SR, "n_fft": N_FFT, "hop": HOP, "n_mels": int(t["mel_fb"].shape[1]),
        "channels": int(t["stem_w"].shape[0]), "kernel": int(t["b0.dw_w"].shape[1]), "stem_kernel": int(t["stem_w"].shape[2]),
        "expand": int(t["b0.pw1_w"].shape[0] // t["stem_w"].shape[0]), "dilations": [int(d) for d in dil],
        "labels": labels, "langs": [l for l, _ in langs],
        "lang_phonemes": json.load(open(j("lang_phonemes.json"), encoding="utf-8")),
        "merge": json.load(open(j("phoneme_merge_map.json"), encoding="utf-8")),
        "shared": cfg.get("shared_labels", SHARED),
        "decode": {k: cfg[k] for k in ("frame_sec", "viterbi_bias", "silence_threshold", "min_silence_sec", "silence_label",
                                       "window_sec", "overlap_sec") if k in cfg},
    }
    return meta, t


def stft_kernel():
    n_bins = N_FFT // 2 + 1
    k = np.arange(N_FFT, dtype=np.float64)
    f = np.arange(n_bins, dtype=np.float64)[:, None]
    ang = 2 * math.pi * f * k[None, :] / N_FFT
    win = 0.5 - 0.5 * np.cos(2 * math.pi * k / N_FFT)
    return (np.concatenate([np.cos(ang), -np.sin(ang)]) * win[None, :]).astype(np.float32)


def half(name, a):
    return a.ndim == 2 and a.size >= 65536 or name == "stem_w"


def write(path, name, meta, t):
    data, tensors = bytearray(), {}
    for k, a in t.items():
        dt = "f16" if half(k, a) else "f32"
        raw = np.ascontiguousarray(a, dtype="<f2" if dt == "f16" else "<f4").tobytes()
        while len(data) % 4:
            data.append(0)
        tensors[k] = {"shape": list(a.shape), "dtype": dt, "offset": len(data)}
        data += raw
    head = json.dumps({"format": 1, "name": name, **meta, "tensors": tensors}, ensure_ascii=False).encode("utf-8")
    pad = (-(12 + len(head))) % 4
    with open(path, "wb") as f:
        f.write(b"MCFL" + struct.pack("<II", 1, len(head)) + head + b"\0" * pad + bytes(data))


def read(path):
    b = open(path, "rb").read()
    assert b[:4] == b"MCFL"
    _, n = struct.unpack("<II", b[4:12])
    h = json.loads(b[12:12 + n].decode("utf-8"))
    base = 12 + n + (-(12 + n)) % 4
    t = {}
    for k, d in h["tensors"].items():
        dt = np.dtype("<f2" if d["dtype"] == "f16" else "<f4")
        cnt = int(np.prod(d["shape"]))
        t[k] = np.frombuffer(b, dt, cnt, base + d["offset"]).astype(np.float32).reshape(d["shape"])
    return h, t


# ------------------------------------------------------------------ reference forward pass (what Mcfl.kt does)

def erf(x):
    from math import erf as e
    return np.vectorize(e)(x)


def gelu(x):
    return 0.5 * x * (1 + erf(x / math.sqrt(2)))


def forward(h, t, audio, lid):
    """audio: peak-normalised float32 at 16 kHz -> log-probabilities [T, labels], offsets [T, 2]."""
    x = np.pad(audio.astype(np.float64), (N_FFT // 2, N_FFT // 2), mode="reflect")
    nfr = (len(x) - N_FFT) // HOP + 1
    fr = np.stack([x[i * HOP:i * HOP + N_FFT] for i in range(nfr)])
    spec = fr @ stft_kernel().astype(np.float64).T
    nb = N_FFT // 2 + 1
    pw = spec[:, :nb] ** 2 + spec[:, nb:] ** 2
    mel = (np.log(np.maximum(pw @ t["mel_fb"], 1e-5)) - t["mel_mean"]) / t["mel_std"]  # [F, mels]
    k = h["stem_kernel"]
    mp = np.pad(mel, ((k // 2, k // 2), (0, 0)))
    T = (len(mel) + 2 * (k // 2) - k) // 2 + 1
    y = np.stack([np.einsum("ock,kc->o", t["stem_w"], mp[2 * i:2 * i + k]) for i in range(T)]) + t["stem_b"]
    y = gelu(y) + t["lang"][lid]
    for i, d in enumerate(h["dilations"]):
        w = t[f"b{i}.dw_w"]
        K = w.shape[1]
        p = d * (K - 1) // 2
        yp = np.pad(y, ((p, p), (0, 0)))
        z = sum(yp[j * d:j * d + T] * w[:, j] for j in range(K)) + t[f"b{i}.dw_b"]
        z = gelu(z @ t[f"b{i}.pw1_w"].T + t[f"b{i}.pw1_b"])
        y = y + z @ t[f"b{i}.pw2_w"].T + t[f"b{i}.pw2_b"]
    lg = y @ t["cls_w"].T + t["cls_b"]
    lg = lg - lg.max(1, keepdims=True)
    lg = lg - np.log(np.exp(lg).sum(1, keepdims=True))
    off = 1 / (1 + np.exp(-(y @ t["off_w"].T + t["off_b"])))
    return lg.astype(np.float32), off.astype(np.float32)


def check(pkg, path, wavs):
    import onnxruntime as ort
    sys.path.insert(0, pkg)
    from mcfl_infer import load_audio
    h, t = read(path)
    s = ort.InferenceSession(os.path.join(pkg, "model.onnx"), providers=["CPUExecutionProvider"])
    for w in wavs:
        a = load_audio(w)[:SR * 12]
        a = a / (np.abs(a).max() + 1e-8)
        for lid in (0, len(h["langs"]) - 1):
            ref_lp, ref_off = s.run(None, {"audio": a[None], "lang": np.array([lid], np.int64)})
            lp, off = forward(h, t, a, lid)
            n = min(len(lp), ref_lp.shape[1])
            p, q = np.exp(lp[:n]), np.exp(ref_lp[0, :n])
            same = (lp[:n].argmax(1) == ref_lp[0, :n].argmax(1)).mean()
            print(f"{os.path.basename(w)} lang {h['langs'][lid]}: frames {len(lp)} vs {ref_lp.shape[1]}, "
                  f"max |dp| {np.abs(p - q).max():.4f}, max |doffset| {np.abs(off[:n] - ref_off[0, :n]).max():.4f}, "
                  f"same best label {same * 100:.1f}%")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("package")
    ap.add_argument("out")
    ap.add_argument("--name", help="name shown in mLabeler (default: the file name)")
    ap.add_argument("--check", nargs="*", default=[], help="wav files: compare the packed model with model.onnx")
    a = ap.parse_args()
    meta, t = read_package(a.package)
    name = a.name or os.path.splitext(os.path.basename(a.out))[0]
    write(a.out, name, meta, t)
    print(f"{a.out}: {os.path.getsize(a.out) / 2**20:.1f} MB, {len(meta['labels'])} labels, languages {' '.join(meta['langs'])}, "
          f"{meta['channels']} channels, {len(meta['dilations'])} blocks")
    if a.check:
        check(a.package, a.out, a.check)


if __name__ == "__main__":
    main()
