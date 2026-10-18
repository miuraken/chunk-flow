"""ONNX の作り方とセッションの設定の組み合わせごとに、速度と Kotlin（参照実装）との誤差を測る（Mac 用）。

- モデルの作り方: MatMulNBits の accuracy_level（0 / 4）、自己注意（標準の演算 / MultiHeadAttention）、
  MatMulNBits の bias 入力の有無。
- セッションの設定: `session.disable_quant_qdq`（1 なら Q8 の DequantizeLinear を読み込み時に float へ畳む）、
  intra-op のスレッド数。
- 並列: 1 つのセッションを複数のスレッドから同時に呼んだときの 1 チャンクあたりの時間（3 並列 × 1 スレッドなど）。

使い方:
    python bench_onnx.py --bin <whisper_tiny_lid_q8q4.bin> --out-dir <作業用ディレクトリ>
"""

from __future__ import annotations

import argparse
import hashlib
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import onnxruntime as ort

import reference as R
from build_onnx import BuildOptions, build_model
from lid_bin import LidModel, load_lid_model
from verify_onnx import synth_signals


def make_mels(model: LidModel, count: int) -> list[np.ndarray]:
    """合成音声から log-mel を作る。"""
    mels = []
    for pcm in synth_signals(count):
        pcm48, copied = R.prepare_window(pcm)
        mels.append(R.log_mel(model, pcm48, copied)[0][None])
    return mels


def session(path: Path, threads: int, fold: bool) -> tuple[ort.InferenceSession, float]:
    """セッションを作り、作るのにかかった時間（ミリ秒）と一緒に返す。"""
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.add_session_config_entry("session.intra_op.allow_spinning", "0")
    if fold:
        so.add_session_config_entry("session.disable_quant_qdq", "1")
    t0 = time.perf_counter()
    sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    return sess, (time.perf_counter() - t0) * 1000


def time_sequential(sess: ort.InferenceSession, mels: list[np.ndarray], repeat: int) -> float:
    """1 件ずつ推論したときの 1 件あたりの時間（中央値、ミリ秒）。"""
    for mel in mels[:2]:
        sess.run(None, {"mel": mel})
    times = []
    for i in range(repeat):
        mel = mels[i % len(mels)]
        t0 = time.perf_counter()
        sess.run(None, {"mel": mel})
        times.append((time.perf_counter() - t0) * 1000)
    return statistics.median(times)


def time_parallel(
    sess: ort.InferenceSession, mels: list[np.ndarray], workers: int, total: int
) -> float:
    """[workers] 個のスレッドから同時に推論したときの、1 件あたりの壁時計の時間（ミリ秒）。"""
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(lambda i: sess.run(None, {"mel": mels[i % len(mels)]}), range(workers * 2)))
        t0 = time.perf_counter()
        list(pool.map(lambda i: sess.run(None, {"mel": mels[i % len(mels)]}), range(total)))
        return (time.perf_counter() - t0) * 1000 / total


def max_errors(
    sess: ort.InferenceSession, model: LidModel, mels: list[np.ndarray]
) -> tuple[float, float]:
    """参照実装とのロジットとエンコーダ出力の最大誤差。"""
    err_l = 0.0
    err_af = 0.0
    for mel in mels:
        logits, af = sess.run(None, {"mel": mel})
        af_ref = R.encoder(model, mel[0])
        lg_ref = R.decoder(model, af_ref)
        err_l = max(err_l, float(np.abs(logits[0] - lg_ref).max()))
        err_af = max(err_af, float(np.abs(af[0] - af_ref).max()))
    return err_l, err_af


def main() -> None:
    """組み合わせごとに測って表にする。"""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bin", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=40)
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    model = load_lid_model(args.bin)
    sha = hashlib.sha256(args.bin.read_bytes()).hexdigest()
    mels = make_mels(model, 6)

    variants = {
        "base": BuildOptions(),
        "acc4": BuildOptions(q4_accuracy_level=4),
        "mha": BuildOptions(attention="mha"),
        "noBiasIn": BuildOptions(nbits_bias=False),
        "mha_acc4": BuildOptions(attention="mha", q4_accuracy_level=4),
    }
    print(
        "| 作り方 | DQ を畳む | 読み込み ms | 1 スレッド ms | 2 スレッド ms | 3 スレッド ms "
        "| 3 並列×1 スレッド ms/件 | 2 並列×1 スレッド ms/件 | max誤差 logits | max誤差 af |"
    )
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for name, opts in variants.items():
        path = args.out_dir / f"lid_{name}.onnx"
        path.write_bytes(build_model(model, opts, sha).SerializeToString())
        for fold in (False, True):
            sess1, load_ms = session(path, 1, fold)
            t1 = time_sequential(sess1, mels, args.repeat)
            p3 = time_parallel(sess1, mels, 3, args.repeat * 3)
            p2 = time_parallel(sess1, mels, 2, args.repeat * 2)
            err_l, err_af = max_errors(sess1, model, mels)
            sess2, _ = session(path, 2, fold)
            t2 = time_sequential(sess2, mels, args.repeat)
            sess3, _ = session(path, 3, fold)
            t3 = time_sequential(sess3, mels, args.repeat)
            print(
                f"| {name} | {'yes' if fold else 'no'} | {load_ms:.0f} | {t1:.1f} | {t2:.1f} | {t3:.1f} "
                f"| {p3:.1f} | {p2:.1f} | {err_l:.2e} | {err_af:.2e} |",
                flush=True,
            )


if __name__ == "__main__":
    main()
