"""Kotlin の `WhisperTinyLanguageDetector`（純 Kotlin の推論）を numpy で写した参照実装。

ONNX のグラフが Kotlin と同じ計算をしていることを確かめるために使う。計算の順序（量子化の解き方、
注意のスケールの掛け方、GELU の erf 近似など）は Kotlin に合わせ、行列積だけ numpy（float32）で行う。
"""

from __future__ import annotations

import numpy as np

from lid_bin import (
    D_HEAD,
    D_MODEL,
    N_AUDIO_CTX,
    N_FRAMES,
    N_HEAD,
    N_MELS,
    LidModel,
)

N_FFT = 400
HOP_LENGTH = 160
TARGET_PCM_SAMPLES = 48_000
DEFAULT_LID_WINDOW_SAMPLES = 32_000
MIN_VALID_PCM_SAMPLES = 1_600
ONSET_RMS_FRAME_SAMPLES = 320
ONSET_PRE_ROLL_SAMPLES = 960
SPEECH_ONSET_RMS_THRESHOLD = 120.0
MIN_PEAK_AMPLITUDE = 64
ATTN_SCALE = np.float32(0.35355338)  # 64^-0.25
LAYER_NORM_EPS = np.float32(1e-5)

_HANN = (0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT))).astype(np.float32)


def erf_kotlin(x: np.ndarray) -> np.ndarray:
    """Kotlin の `erf`（Abramowitz–Stegun 7.1.26、誤差 1.5e-7 以下）を float32 で写したもの。"""
    x = x.astype(np.float32)
    sign = np.where(x < 0, np.float32(-1.0), np.float32(1.0))
    ax = np.abs(x)
    t = np.float32(1.0) / (np.float32(1.0) + np.float32(0.3275911) * ax)
    poly = (
        (
            ((np.float32(1.0614054) * t - np.float32(1.453152)) * t + np.float32(1.4214137)) * t
            - np.float32(0.28449672)
        )
        * t
        + np.float32(0.2548296)
    ) * t
    y = np.float32(1.0) - poly * np.exp(-ax * ax)
    return (sign * y).astype(np.float32)


def gelu_kotlin(x: np.ndarray) -> np.ndarray:
    """Kotlin の `gelu` と同じ式（0.5 x (1 + erf(x / √2))）。"""
    return (
        np.float32(0.5) * x * (np.float32(1.0) + erf_kotlin(x * np.float32(0.70710677)))
    ).astype(np.float32)


def layer_norm(x: np.ndarray, w: np.ndarray, b: np.ndarray) -> np.ndarray:
    """最後の軸の LayerNorm（Kotlin の `layerNormRows` と同じく、分散は平均からの差の二乗平均）。"""
    mean = x.mean(axis=-1, keepdims=True, dtype=np.float32)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True, dtype=np.float32)
    inv_std = np.float32(1.0) / np.sqrt(var + LAYER_NORM_EPS)
    return ((x - mean) * inv_std * w + b).astype(np.float32)


def prepare_window(
    pcm16: np.ndarray, max_window_samples: int = DEFAULT_LID_WINDOW_SAMPLES
) -> tuple[np.ndarray, int]:
    """Kotlin の `preparePcm48kWindow` と同じく、発話の始まりから最大 [max_window_samples] を切り出す。

    戻り値は（48,000 サンプルの float32 配列、コピーしたサンプル数）。無音なら（ゼロ、0）。
    """
    out = np.zeros(TARGET_PCM_SAMPLES, dtype=np.float32)
    if pcm16.size == 0 or int(np.abs(pcm16.astype(np.int32)).max()) < MIN_PEAK_AMPLITUDE:
        return out, 0
    window = int(np.clip(max_window_samples, MIN_VALID_PCM_SAMPLES, TARGET_PCM_SAMPLES))
    start = 0
    if pcm16.size > ONSET_RMS_FRAME_SAMPLES * 2:
        offset = 0
        while offset + ONSET_RMS_FRAME_SAMPLES <= pcm16.size:
            frame = pcm16[offset : offset + ONSET_RMS_FRAME_SAMPLES].astype(np.float64)
            if (
                np.sqrt((frame * frame).sum() / ONSET_RMS_FRAME_SAMPLES)
                >= SPEECH_ONSET_RMS_THRESHOLD
            ):
                start = max(offset - ONSET_PRE_ROLL_SAMPLES, 0)
                break
            offset += ONSET_RMS_FRAME_SAMPLES
    copy_len = max(min(window, pcm16.size - start), 0)
    out[:copy_len] = pcm16[start : start + copy_len].astype(np.float32) * np.float32(1.0 / 32768.0)
    return out, copy_len


def active_mel_frames(copied_samples: int) -> int:
    """Kotlin の `activeMelFrameCount`。"""
    safe = int(np.clip(copied_samples, 1, TARGET_PCM_SAMPLES))
    return min(N_FRAMES, max((N_FFT // 2 + safe + HOP_LENGTH - 1) // HOP_LENGTH, 1))


def log_mel(model: LidModel, pcm48k: np.ndarray, copied_samples: int) -> tuple[np.ndarray, int]:
    """Kotlin の `computeLogMelSpectrogram` と同じ正規化 log-mel（[300, 80]、時間が先）を返す。"""
    pad = N_FFT // 2
    padded = np.empty(TARGET_PCM_SAMPLES + N_FFT, dtype=np.float32)
    padded[:pad] = pcm48k[pad:0:-1]
    padded[pad : pad + TARGET_PCM_SAMPLES] = pcm48k
    padded[pad + TARGET_PCM_SAMPLES :] = pcm48k[
        TARGET_PCM_SAMPLES - 2 : TARGET_PCM_SAMPLES - 2 - pad : -1
    ]
    active = active_mel_frames(copied_samples)
    idx = np.arange(active)[:, None] * HOP_LENGTH + np.arange(N_FFT)[None, :]
    frames = padded[idx] * _HANN[None, :]
    spec = np.fft.rfft(frames.astype(np.float64), axis=1)
    mag = (spec.real**2 + spec.imag**2).astype(np.float32)
    mel = mag @ model.mel_filters.values
    log10 = np.log10(np.maximum(mel, np.float32(1e-10))).astype(np.float32)
    out = np.full((N_FRAMES, N_MELS), np.float32(-10.0), dtype=np.float32)
    out[:active] = log10
    global_max = float(log10.max())
    if active < N_FRAMES:
        global_max = max(global_max, -10.0)
    min_clamp = np.float32(global_max - 8.0)
    return ((np.maximum(out, min_clamp) + np.float32(4.0)) * np.float32(0.25)).astype(
        np.float32
    ), active


def _conv1d_k3(x: np.ndarray, w: np.ndarray, b: np.ndarray, stride: int) -> np.ndarray:
    """カーネル 3・両端 0 埋め 1 の 1 次元畳み込み。x は [T, C]、w は [O, 3, C]（k=0 が t−1）。"""
    t_in, channels = x.shape
    padded = np.zeros((t_in + 2, channels), dtype=np.float32)
    padded[1:-1] = x
    t_out = (t_in - 1) // stride + 1
    cols = np.concatenate(
        [padded[k : k + stride * (t_out - 1) + 1 : stride] for k in range(3)], axis=1
    )
    return (cols @ w.reshape(w.shape[0], -1).T + b).astype(np.float32)


def encoder(model: LidModel, mel: np.ndarray) -> np.ndarray:
    """Kotlin の `runAudioEncoder`。mel は [300, 80]、戻り値は LayerNorm 後のエンコーダ出力 [150, 384]。"""
    w1 = model.conv1_w.dequantize().reshape(D_MODEL, 3, N_MELS)
    x = gelu_kotlin(_conv1d_k3(mel, w1, model.conv1_b.values, stride=1))
    w2 = model.conv2_w.dequantize().reshape(D_MODEL, 3, D_MODEL)
    x = gelu_kotlin(_conv1d_k3(x, w2, model.conv2_b.values, stride=2))
    x = (x + model.enc_pos.values).astype(np.float32)
    for blk in model.enc_blocks:
        h = layer_norm(x, blk.attn_ln_w.values, blk.attn_ln_b.values)
        q = h @ blk.q_w.dequantize().T + blk.q_b.values
        k = h @ blk.k_w.dequantize().T
        v = h @ blk.v_w.dequantize().T + blk.v_b.values
        qh = (q * ATTN_SCALE).reshape(N_AUDIO_CTX, N_HEAD, D_HEAD).transpose(1, 0, 2)
        kh = (k * ATTN_SCALE).reshape(N_AUDIO_CTX, N_HEAD, D_HEAD).transpose(1, 0, 2)
        vh = v.reshape(N_AUDIO_CTX, N_HEAD, D_HEAD).transpose(1, 0, 2)
        scores = qh @ kh.transpose(0, 2, 1)
        scores = np.exp(scores - scores.max(axis=-1, keepdims=True))
        probs = scores / scores.sum(axis=-1, keepdims=True)
        wv = (probs @ vh).transpose(1, 0, 2).reshape(N_AUDIO_CTX, D_MODEL)
        x = (x + wv @ blk.out_w.dequantize().T + blk.out_b.values).astype(np.float32)
        h = layer_norm(x, blk.mlp_ln_w.values, blk.mlp_ln_b.values)
        m = gelu_kotlin((h @ blk.mlp1_w.dequantize().T + blk.mlp1_b.values).astype(np.float32))
        x = (x + m @ blk.mlp2_w.dequantize().T + blk.mlp2_b.values).astype(np.float32)
    return layer_norm(x, model.ln_post_w.values, model.ln_post_b.values)


def decoder(model: LidModel, audio_features: np.ndarray) -> np.ndarray:
    """Kotlin の `runFusedDecoder`。99 言語のロジットを返す。"""
    xd = model.x0_after_self.values.copy()
    for layer_idx, blk in enumerate(model.dec_blocks):
        if layer_idx == 0:
            qk = model.qk0_const.values
        else:
            assert blk.attn_ln_w is not None and blk.w_self is not None and blk.cq_w is not None
            assert blk.attn_ln_b is not None and blk.b_self is not None and blk.cq_b is not None
            assert (
                blk.cross_ln_w is not None and blk.cross_ln_b is not None and blk.ck_w is not None
            )
            h = layer_norm(xd, blk.attn_ln_w.values, blk.attn_ln_b.values)
            xd = (xd + blk.w_self.dequantize() @ h + blk.b_self.values).astype(np.float32)
            h = layer_norm(xd, blk.cross_ln_w.values, blk.cross_ln_b.values)
            q = (blk.cq_w.dequantize() @ h + blk.cq_b.values) * ATTN_SCALE
            wk = blk.ck_w.dequantize().reshape(N_HEAD, D_HEAD, D_MODEL)
            qk = np.einsum("hd,hde->he", (q * ATTN_SCALE).reshape(N_HEAD, D_HEAD), wk).astype(
                np.float32
            )
        scores = qk @ audio_features.T
        scores = np.exp(scores - scores.max(axis=-1, keepdims=True))
        probs = scores / scores.sum(axis=-1, keepdims=True)
        pooled = probs @ audio_features
        wv = np.einsum("hc,hdc->hd", pooled, blk.cv_w.dequantize().reshape(N_HEAD, D_HEAD, D_MODEL))
        wv = (wv.reshape(D_MODEL) + blk.cv_b.values).astype(np.float32)
        xd = (xd + blk.cout_w.dequantize() @ wv + blk.cout_b.values).astype(np.float32)
        h = layer_norm(xd, blk.mlp_ln_w.values, blk.mlp_ln_b.values)
        m = gelu_kotlin((blk.mlp1_w.dequantize() @ h + blk.mlp1_b.values).astype(np.float32))
        xd = (xd + blk.mlp2_w.dequantize() @ m + blk.mlp2_b.values).astype(np.float32)
    h = layer_norm(xd, model.dec_ln_w.values, model.dec_ln_b.values)
    return (model.lang_emb_w.dequantize() @ h).astype(np.float32)


def mean_pool(audio_features: np.ndarray, copied_samples: int) -> np.ndarray:
    """Kotlin の `meanPoolEncoderOutput`（音声を含む位置の平均）。"""
    positions = int(np.clip((active_mel_frames(copied_samples) + 1) // 2, 1, N_AUDIO_CTX))
    return audio_features[:positions].mean(axis=0, dtype=np.float32)
