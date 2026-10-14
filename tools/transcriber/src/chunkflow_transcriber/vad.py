"""発話区間の検出（Silero VAD v6、ONNX Runtime）と候補チャンクの作成（§7.4.4 の手順 2）。

macOS と Linux で同じ実装を使う（エンジンによる結果の違いを増やさないため。§12）。モデルは
Silero VAD の作者が PyPI で配布している `silero-vad` 6.2.3 の wheel に含まれる `silero_vad.onnx` を、
wheel と ONNX の両方の SHA-256 を確かめて取り出す（パッケージ自体は入れない。torch に依存するため）。

発話区間を求める処理は、silero-vad 6.2.3 の `get_speech_timestamps_from_probs`（MIT ライセンス）の
主要な部分（閾値のヒステリシス、最短の無音・発話、前後の余白）を移植したもの。最大長での分割は
`merge_regions` で行う。
"""

from __future__ import annotations

import hashlib
import io
import logging
import ssl
import urllib.request
import zipfile
from collections.abc import Callable, Sequence
from pathlib import Path

import numpy as np

from .audio import SAMPLE_RATE
from .config import VadParams
from .model import CandidateChunk

log = logging.getLogger(__name__)

SpeechDetector = Callable[[np.ndarray, VadParams], list[tuple[int, int]]]

WINDOW = 512  # 16kHz で 32ms
CONTEXT = 64

# 取得元とハッシュ（2026-10-14 に PyPI で確認）
SILERO_WHEEL_URL = (
    "https://files.pythonhosted.org/packages/84/ef/"
    "9099037ed6f180ea33220178df4107112c0ce2bf5fb4d6f6ab19db2844ed/silero_vad-6.2.3-py3-none-any.whl"
)
SILERO_WHEEL_SHA256 = "7b7f5436cfcb02fae583a05b512ea96467fd449fe54cb49a5e4f06c51a1e43b8"
SILERO_ONNX_MEMBER = "silero_vad/data/silero_vad.onnx"
SILERO_ONNX_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
SILERO_ONNX_NAME = "silero_vad-6.2.3.onnx"
DEFAULT_CACHE = Path("~/.cache/chunkflow-transcribe")
_MAX_WHEEL_BYTES = 64 * 1024 * 1024


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _ssl_context() -> ssl.SSLContext:
    """証明書の検証に使う SSL コンテキスト。

    python.org 版の macOS の Python は OS の証明書を使わないため、あれば certifi（huggingface-hub
    などの依存で入る）の証明書を使う。
    """
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


def ensure_silero_model(cache_dir: Path | None = None) -> Path:
    """Silero VAD の ONNX モデルを用意してパスを返す（なければ取得し、ハッシュを確かめる）。"""
    root = (cache_dir or DEFAULT_CACHE).expanduser()
    path = root / SILERO_ONNX_NAME
    if path.is_file() and _sha256(path.read_bytes()) == SILERO_ONNX_SHA256:
        return path
    log.info("Silero VAD のモデルを取得します: %s", SILERO_WHEEL_URL)
    with urllib.request.urlopen(  # 固定の https の URL
        SILERO_WHEEL_URL, timeout=120, context=_ssl_context()
    ) as resp:
        wheel = resp.read(_MAX_WHEEL_BYTES + 1)
    if len(wheel) > _MAX_WHEEL_BYTES or _sha256(wheel) != SILERO_WHEEL_SHA256:
        raise RuntimeError("Silero VAD の wheel のハッシュが一致しません")
    with zipfile.ZipFile(io.BytesIO(wheel)) as zf:
        onnx = zf.read(SILERO_ONNX_MEMBER)
    if _sha256(onnx) != SILERO_ONNX_SHA256:
        raise RuntimeError("Silero VAD の ONNX のハッシュが一致しません")
    root.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(onnx)
    tmp.replace(path)
    return path


class SileroVad:
    """Silero VAD v6（ONNX）で、32ms ごとの発話の確率を求める。"""

    def __init__(self, model_path: Path) -> None:
        import onnxruntime

        opts = onnxruntime.SessionOptions()
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = 1
        self._session = onnxruntime.InferenceSession(
            str(model_path), sess_options=opts, providers=["CPUExecutionProvider"]
        )

    def probs(self, audio: np.ndarray) -> list[float]:
        """音声（16kHz・モノラルの float32）の、512 サンプルごとの発話の確率を返す。"""
        state = np.zeros((2, 1, 128), dtype=np.float32)
        context = np.zeros((1, CONTEXT), dtype=np.float32)
        sr = np.array(SAMPLE_RATE, dtype=np.int64)
        out: list[float] = []
        x = audio.astype(np.float32, copy=False)
        for start in range(0, len(x), WINDOW):
            chunk = x[start : start + WINDOW]
            if len(chunk) < WINDOW:
                chunk = np.pad(chunk, (0, WINDOW - len(chunk)))
            inp = np.concatenate([context, chunk[None, :]], axis=1)
            prob, state = self._session.run(None, {"input": inp, "state": state, "sr": sr})
            context = inp[:, -CONTEXT:]
            out.append(float(prob.reshape(-1)[0]))
        return out


def timestamps_from_probs(
    probs: Sequence[float], params: VadParams, total_samples: int
) -> list[tuple[int, int]]:
    """発話の確率の列から発話区間（ミリ秒）を求める（純粋な関数）。

    確率が `threshold` 以上で発話が始まり、`threshold - 0.15` 未満が `min_silence_ms` 続くと終わる。
    `min_speech_ms` 以下の区間は捨て、前後に `speech_pad_ms` の余白を付ける（隣と近ければ間を分ける）。
    """
    thr = params.threshold
    neg = max(thr - 0.15, 0.01)
    min_speech = SAMPLE_RATE * params.min_speech_ms / 1000
    min_silence = SAMPLE_RATE * params.min_silence_ms / 1000
    pad = SAMPLE_RATE * params.speech_pad_ms / 1000
    speeches: list[list[int]] = []
    triggered = False
    start = 0
    temp_end = 0
    for i, p in enumerate(probs):
        cur = WINDOW * i
        if p >= thr and temp_end:
            temp_end = 0
        if p >= thr and not triggered:
            triggered = True
            start = cur
            continue
        if p < neg and triggered:
            if not temp_end:
                temp_end = cur
            if cur - temp_end < min_silence:
                continue
            if temp_end - start > min_speech:
                speeches.append([start, temp_end])
            triggered = False
            temp_end = 0
    if triggered and total_samples - start > min_speech:
        speeches.append([start, total_samples])
    for i, sp in enumerate(speeches):
        if i == 0:
            sp[0] = int(max(0, sp[0] - pad))
        if i != len(speeches) - 1:
            silence = speeches[i + 1][0] - sp[1]
            if silence < 2 * pad:
                sp[1] += int(silence // 2)
                speeches[i + 1][0] = int(max(0, speeches[i + 1][0] - silence // 2))
            else:
                sp[1] = int(min(total_samples, sp[1] + pad))
                speeches[i + 1][0] = int(max(0, speeches[i + 1][0] - pad))
        else:
            sp[1] = int(min(total_samples, sp[1] + pad))
    return [(s * 1000 // SAMPLE_RATE, e * 1000 // SAMPLE_RATE) for s, e in speeches]


def merge_regions(
    regions: Sequence[tuple[int, int]], merge_gap_ms: int, max_chunk_ms: int
) -> list[CandidateChunk]:
    """発話区間（ミリ秒）を、短い無音でつながるものどうしまとめて候補チャンクにする。

    まとめると `max_chunk_ms` を超える場合はまとめない。1 つの区間だけで上限を超える場合は、
    上限の長さで等分する。純粋な関数。
    """
    cleaned = sorted((s, e) for s, e in regions if e > s)
    groups: list[list[int]] = []
    for s, e in cleaned:
        if groups:
            g = groups[-1]
            if s - g[1] < merge_gap_ms and max(e, g[1]) - g[0] <= max_chunk_ms:
                g[1] = max(g[1], e)
                continue
            if s < g[1]:  # 重なっているがまとめられない: 開始を前の終了に合わせる
                s = g[1]
                if e <= s:
                    continue
        groups.append([s, e])
    out: list[CandidateChunk] = []
    for s, e in groups:
        length = e - s
        if length <= max_chunk_ms:
            out.append(CandidateChunk(s, e))
            continue
        n = -(-length // max_chunk_ms)
        step = length / n
        for k in range(n):
            out.append(CandidateChunk(s + round(k * step), s + round((k + 1) * step)))
    return out


def recognition_window(
    chunks: Sequence[CandidateChunk], index: int, pad_ms: int, total_ms: int
) -> tuple[int, int]:
    """認識に渡す音声の範囲（ミリ秒）。前後に `pad_ms` を足すが、隣のチャンクの手前で止める。"""
    c = chunks[index]
    prev_end = chunks[index - 1].end_ms if index > 0 else 0
    next_start = chunks[index + 1].start_ms if index + 1 < len(chunks) else total_ms
    start = max(0, prev_end, c.start_ms - pad_ms)
    end = min(total_ms, next_start, c.end_ms + pad_ms)
    return min(start, c.start_ms), max(end, c.end_ms)


def vad_info(params: VadParams) -> dict[str, object]:
    """`generator.vad` に記録する VAD の名前・版・主なパラメータ。"""
    return {
        "name": "silero-vad",
        "version": "6.2.3",
        "runtime": "onnxruntime",
        "modelSha256": SILERO_ONNX_SHA256,
        "threshold": params.threshold,
        "minSpeechMs": params.min_speech_ms,
        "minSilenceMs": params.min_silence_ms,
        "speechPadMs": params.speech_pad_ms,
        "mergeGapMs": params.merge_gap_ms,
        "maxChunkMs": params.max_chunk_ms,
    }


def silero_detector(cache_dir: Path | None = None) -> SpeechDetector:
    """Silero VAD を使う `SpeechDetector` を作る（モデルはここで用意する）。"""
    vad = SileroVad(ensure_silero_model(cache_dir))

    def detect(audio: np.ndarray, params: VadParams) -> list[tuple[int, int]]:
        return timestamps_from_probs(vad.probs(audio), params, len(audio))

    return detect


def find_candidate_chunks(
    audio: np.ndarray, params: VadParams, detector: SpeechDetector
) -> list[CandidateChunk]:
    """音声から候補チャンクを求める（VAD → まとめる）。"""
    regions = detector(audio, params)
    return merge_regions(regions, params.merge_gap_ms, params.max_chunk_ms)
