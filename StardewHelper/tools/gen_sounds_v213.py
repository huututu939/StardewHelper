#!/usr/bin/env python3
"""v2.13 UI 音效合成：纯 stdlib（wave/struct/math），16-bit mono PCM。
产出 4 个 res/raw 资产：snd_check / snd_complete / snd_flip / snd_error。
每段 (f0, f1, dur_ms, vol)：从 f0 滑到 f1、指数衰减包络的正弦波。"""
import wave, struct, math, os

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res", "raw")


def synth(path: str, segments, total_ms: int):
    n = int(SR * total_ms / 1000)
    samples = []
    for (f0, f1, dur_ms, vol) in segments:
        m = int(SR * dur_ms / 1000)
        for i in range(m):
            t = i / SR
            freq = f0 + (f1 - f0) * (i / m)
            env = (1 - i / m) ** 1.5
            samples.append(vol * env * math.sin(2 * math.pi * freq * t))
    while len(samples) < n:
        samples.append(0.0)
    samples = samples[:n]
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767))
            for s in samples))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    # 1. 勾选叮咚：880→1320Hz 上扬两段（100ms）
    synth(os.path.join(OUT, "snd_check.wav"),
          [(880, 1320, 60, 0.6), (1320, 1320, 40, 0.3)], 100)
    # 2. 完成琶音：C-E-G-C（470ms）
    synth(os.path.join(OUT, "snd_complete.wav"),
          [(523, 523, 90, 0.5), (659, 659, 90, 0.5),
           (784, 784, 90, 0.5), (1046, 1046, 200, 0.6)], 470)
    # 3. 翻页短滑音：400→800Hz（50ms）
    synth(os.path.join(OUT, "snd_flip.wav"),
          [(400, 800, 50, 0.4)], 50)
    # 4. 失败 buzz：200→180Hz 低频短促（150ms）
    synth(os.path.join(OUT, "snd_error.wav"),
          [(200, 180, 150, 0.6)], 150)
    for f in ("snd_check.wav", "snd_complete.wav", "snd_flip.wav", "snd_error.wav"):
        p = os.path.join(OUT, f)
        print(f, os.path.getsize(p), "bytes")
