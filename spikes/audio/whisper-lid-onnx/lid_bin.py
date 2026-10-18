"""`whisper_tiny_lid_q8q4.bin`（Kotlin の `WhisperTinyLanguageDetector.parseModelBytes` が読む形式）の読み込み。

Kotlin と同じ順にテンソルを読み、値（numpy 配列）と、ファイルの中の位置（バイトのオフセットと長さ）を返す。
位置は、重みを持たない ONNX（外部データとして .bin を参照する方式）を作るときに使う。

形式（すべてリトルエンディアン）:
- ヘッダ: マジック `WLID`、int32 × 12（version, nMels, nFrames, nAudioCtx, dModel, nHead, dHead, dFf,
  nAudioLayer, nTextLayer, nLangs, q4GroupSize）
- 言語コード: nLangs 個の（長さ 1 バイト + UTF-8）
- テンソル: Q8 は「行ごとの scale（float32 × rows）+ 量子化値（int8 × rows × cols）」、
  Q4 は「64 要素ごとの scale（float32 × rows × cols / 64）+ 4 bit の値（uint8 × rows × cols / 2、下位ニブルが偶数列）」、
  そのほかは float32 の配列。
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

N_MELS = 80
N_FRAMES = 300
N_AUDIO_CTX = 150
D_MODEL = 384
N_HEAD = 6
D_HEAD = 64
D_FF = 1536
N_AUDIO_LAYER = 4
N_TEXT_LAYER = 4
Q4_GROUP_SIZE = 64
N_FREQ_BINS = 201


@dataclass(frozen=True)
class Segment:
    """ファイルの中のテンソルの位置。"""

    offset: int
    length: int


@dataclass
class FloatTensor:
    """float32 のテンソル。"""

    values: np.ndarray
    segment: Segment


@dataclass
class Q8Matrix:
    """行ごとに scale を持つ int8 の行列（[rows, cols]、値 = quants × scale）。"""

    rows: int
    cols: int
    scales: np.ndarray
    quants: np.ndarray
    scales_segment: Segment
    quants_segment: Segment

    def dequantize(self) -> np.ndarray:
        """Kotlin の `quants[i].toFloat() * scale` と同じ値の float32 行列を返す。"""
        return (self.quants.astype(np.float32) * self.scales[:, None]).astype(np.float32)


@dataclass
class Q4Matrix:
    """64 要素ごとに scale を持つ 4 bit の行列（[rows, cols]、値 = (ニブル − 8) × scale）。"""

    rows: int
    cols: int
    scales: np.ndarray
    packed: np.ndarray
    scales_segment: Segment
    packed_segment: Segment

    def dequantize(self) -> np.ndarray:
        """Kotlin の `dequantizeQ4Row` と同じ値の float32 行列を返す。"""
        low = (self.packed & 0x0F).astype(np.int32) - 8
        high = ((self.packed >> 4) & 0x0F).astype(np.int32) - 8
        q = np.empty(self.packed.size * 2, dtype=np.int32)
        q[0::2] = low
        q[1::2] = high
        q = q.reshape(self.rows, self.cols // Q4_GROUP_SIZE, Q4_GROUP_SIZE)
        scales = self.scales.reshape(self.rows, self.cols // Q4_GROUP_SIZE, 1)
        return (q.astype(np.float32) * scales).reshape(self.rows, self.cols).astype(np.float32)


@dataclass
class EncoderBlock:
    """エンコーダの 1 層の重み。"""

    attn_ln_w: FloatTensor
    attn_ln_b: FloatTensor
    q_w: Q8Matrix
    q_b: FloatTensor
    k_w: Q8Matrix
    v_w: Q8Matrix
    v_b: FloatTensor
    out_w: Q8Matrix
    out_b: FloatTensor
    mlp_ln_w: FloatTensor
    mlp_ln_b: FloatTensor
    mlp1_w: Q4Matrix
    mlp1_b: FloatTensor
    mlp2_w: Q4Matrix
    mlp2_b: FloatTensor


@dataclass
class DecoderBlock:
    """融合デコーダの 1 層の重み（層 0 は自己注意とクロス注意のクエリの重みを持たない）。"""

    attn_ln_w: FloatTensor | None
    attn_ln_b: FloatTensor | None
    w_self: Q8Matrix | None
    b_self: FloatTensor | None
    cross_ln_w: FloatTensor | None
    cross_ln_b: FloatTensor | None
    cq_w: Q8Matrix | None
    cq_b: FloatTensor | None
    ck_w: Q8Matrix | None
    cv_w: Q8Matrix
    cv_b: FloatTensor
    cout_w: Q8Matrix
    cout_b: FloatTensor
    mlp_ln_w: FloatTensor
    mlp_ln_b: FloatTensor
    mlp1_w: Q4Matrix
    mlp1_b: FloatTensor
    mlp2_w: Q4Matrix
    mlp2_b: FloatTensor


@dataclass
class LidModel:
    """`whisper_tiny_lid_q8q4.bin` の中身。"""

    language_codes: list[str]
    mel_filters: FloatTensor
    conv1_w: Q8Matrix
    conv1_b: FloatTensor
    conv2_w: Q8Matrix
    conv2_b: FloatTensor
    enc_pos: FloatTensor
    enc_blocks: list[EncoderBlock]
    ln_post_w: FloatTensor
    ln_post_b: FloatTensor
    x0_after_self: FloatTensor
    qk0_const: FloatTensor
    dec_blocks: list[DecoderBlock]
    dec_ln_w: FloatTensor
    dec_ln_b: FloatTensor
    lang_emb_w: Q8Matrix
    file_size: int = 0
    extra: dict[str, object] = field(default_factory=dict)


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def take(self, n: int) -> tuple[bytes, Segment]:
        if self.pos + n > len(self.data):
            raise ValueError("ファイルが途中で終わっている")
        seg = Segment(self.pos, n)
        chunk = self.data[self.pos : self.pos + n]
        self.pos += n
        return chunk, seg

    def int32(self) -> int:
        chunk, _ = self.take(4)
        return struct.unpack("<i", chunk)[0]

    def floats(self, count: int, shape: tuple[int, ...] | None = None) -> FloatTensor:
        chunk, seg = self.take(count * 4)
        arr = np.frombuffer(chunk, dtype="<f4").astype(np.float32)
        return FloatTensor(arr.reshape(shape) if shape else arr, seg)

    def q8(self, rows: int, cols: int) -> Q8Matrix:
        scales = self.floats(rows)
        chunk, seg = self.take(rows * cols)
        quants = np.frombuffer(chunk, dtype=np.int8).reshape(rows, cols).copy()
        return Q8Matrix(rows, cols, scales.values, quants, scales.segment, seg)

    def q4(self, rows: int, cols: int) -> Q4Matrix:
        total = rows * cols
        scales = self.floats(total // Q4_GROUP_SIZE)
        chunk, seg = self.take(total // 2)
        packed = np.frombuffer(chunk, dtype=np.uint8).copy()
        return Q4Matrix(rows, cols, scales.values, packed, scales.segment, seg)


def load_lid_model(path: Path) -> LidModel:
    """[path] の .bin を Kotlin と同じ順に読む。形式が違う場合は ValueError。"""
    data = path.read_bytes()
    r = _Reader(data)
    magic, _ = r.take(4)
    if magic != b"WLID":
        raise ValueError("マジックが WLID ではない")
    header = [r.int32() for _ in range(12)]
    (
        version,
        n_mels,
        n_frames,
        n_audio_ctx,
        d_model,
        n_head,
        d_head,
        d_ff,
        n_al,
        n_tl,
        n_langs,
        q4g,
    ) = header
    expected = (
        1,
        N_MELS,
        N_FRAMES,
        N_AUDIO_CTX,
        D_MODEL,
        N_HEAD,
        D_HEAD,
        D_FF,
        N_AUDIO_LAYER,
        N_TEXT_LAYER,
    )
    if tuple(header[:10]) != expected or n_langs <= 0 or q4g != Q4_GROUP_SIZE:
        raise ValueError(f"ヘッダが想定と違う: {header}")
    del version, n_mels, n_frames, n_audio_ctx, d_model, n_head, d_head, d_ff, n_al, n_tl
    codes = []
    for _ in range(n_langs):
        (length,) = r.take(1)[0]
        codes.append(r.take(length)[0].decode("utf-8"))

    mel_filters = r.floats(N_FREQ_BINS * N_MELS, (N_FREQ_BINS, N_MELS))
    conv1_w = r.q8(D_MODEL, 3 * N_MELS)
    conv1_b = r.floats(D_MODEL)
    conv2_w = r.q8(D_MODEL, 3 * D_MODEL)
    conv2_b = r.floats(D_MODEL)
    enc_pos = r.floats(N_AUDIO_CTX * D_MODEL, (N_AUDIO_CTX, D_MODEL))
    enc_blocks = []
    for _ in range(N_AUDIO_LAYER):
        enc_blocks.append(
            EncoderBlock(
                attn_ln_w=r.floats(D_MODEL),
                attn_ln_b=r.floats(D_MODEL),
                q_w=r.q8(D_MODEL, D_MODEL),
                q_b=r.floats(D_MODEL),
                k_w=r.q8(D_MODEL, D_MODEL),
                v_w=r.q8(D_MODEL, D_MODEL),
                v_b=r.floats(D_MODEL),
                out_w=r.q8(D_MODEL, D_MODEL),
                out_b=r.floats(D_MODEL),
                mlp_ln_w=r.floats(D_MODEL),
                mlp_ln_b=r.floats(D_MODEL),
                mlp1_w=r.q4(D_FF, D_MODEL),
                mlp1_b=r.floats(D_FF),
                mlp2_w=r.q4(D_MODEL, D_FF),
                mlp2_b=r.floats(D_MODEL),
            )
        )
    ln_post_w = r.floats(D_MODEL)
    ln_post_b = r.floats(D_MODEL)
    x0 = r.floats(D_MODEL)
    qk0 = r.floats(N_HEAD * D_MODEL, (N_HEAD, D_MODEL))
    dec_blocks = []
    for i in range(N_TEXT_LAYER):
        if i == 0:
            dec_blocks.append(
                DecoderBlock(
                    attn_ln_w=None,
                    attn_ln_b=None,
                    w_self=None,
                    b_self=None,
                    cross_ln_w=None,
                    cross_ln_b=None,
                    cq_w=None,
                    cq_b=None,
                    ck_w=None,
                    cv_w=r.q8(D_MODEL, D_MODEL),
                    cv_b=r.floats(D_MODEL),
                    cout_w=r.q8(D_MODEL, D_MODEL),
                    cout_b=r.floats(D_MODEL),
                    mlp_ln_w=r.floats(D_MODEL),
                    mlp_ln_b=r.floats(D_MODEL),
                    mlp1_w=r.q4(D_FF, D_MODEL),
                    mlp1_b=r.floats(D_FF),
                    mlp2_w=r.q4(D_MODEL, D_FF),
                    mlp2_b=r.floats(D_MODEL),
                )
            )
        else:
            dec_blocks.append(
                DecoderBlock(
                    attn_ln_w=r.floats(D_MODEL),
                    attn_ln_b=r.floats(D_MODEL),
                    w_self=r.q8(D_MODEL, D_MODEL),
                    b_self=r.floats(D_MODEL),
                    cross_ln_w=r.floats(D_MODEL),
                    cross_ln_b=r.floats(D_MODEL),
                    cq_w=r.q8(D_MODEL, D_MODEL),
                    cq_b=r.floats(D_MODEL),
                    ck_w=r.q8(D_MODEL, D_MODEL),
                    cv_w=r.q8(D_MODEL, D_MODEL),
                    cv_b=r.floats(D_MODEL),
                    cout_w=r.q8(D_MODEL, D_MODEL),
                    cout_b=r.floats(D_MODEL),
                    mlp_ln_w=r.floats(D_MODEL),
                    mlp_ln_b=r.floats(D_MODEL),
                    mlp1_w=r.q4(D_FF, D_MODEL),
                    mlp1_b=r.floats(D_FF),
                    mlp2_w=r.q4(D_MODEL, D_FF),
                    mlp2_b=r.floats(D_MODEL),
                )
            )
    dec_ln_w = r.floats(D_MODEL)
    dec_ln_b = r.floats(D_MODEL)
    lang_emb_w = r.q8(n_langs, D_MODEL)
    return LidModel(
        language_codes=codes,
        mel_filters=mel_filters,
        conv1_w=conv1_w,
        conv1_b=conv1_b,
        conv2_w=conv2_w,
        conv2_b=conv2_b,
        enc_pos=enc_pos,
        enc_blocks=enc_blocks,
        ln_post_w=ln_post_w,
        ln_post_b=ln_post_b,
        x0_after_self=x0,
        qk0_const=qk0,
        dec_blocks=dec_blocks,
        dec_ln_w=dec_ln_w,
        dec_ln_b=dec_ln_b,
        lang_emb_w=lang_emb_w,
        file_size=len(data),
        extra={"trailing_bytes": len(data) - r.pos},
    )
