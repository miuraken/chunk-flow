"""作った ONNX が Kotlin（の numpy 写し `reference.py`）と同じ値を出すことを確かめる。

1. protobuf の定義の確認: ONNX Runtime に同梱のモデル（`onnxruntime/datasets/*.onnx`）を読めること。
2. 演算ごとの確認（.bin の実際の重みで小さなグラフを作って ONNX Runtime で実行し、numpy と比べる）:
   - MatMulNBits（Q4）: .bin の packed と scale をそのまま入れた結果が `dequantizeQ4Row` と同じになること。
     ニブルの順を入れ替えた重みでは一致しないこと（確認の感度）。
   - DequantizeLinear + Gemm（Q8）、im2col + Gemm の畳み込み（conv1 / conv2）。
3. モデル全体: 合成音声の log-mel で、ロジットとエンコーダ出力の最大誤差、上位の言語の一致。

使い方:
    python verify_onnx.py --bin <whisper_tiny_lid_q8q4.bin> --onnx <whisper_tiny_lid.onnx> [--config key=value]

Kotlin の実装との比較は、Kotlin の単体テスト（`WhisperLidOnnxRuntimeTest`）で行う。
"""

from __future__ import annotations

import argparse
import glob
from pathlib import Path

import numpy as np
import onnxruntime as ort

import onnx_proto as P
import reference as R
from build_onnx import MS_DOMAIN
from lid_bin import Q4_GROUP_SIZE, LidModel, Q4Matrix, Q8Matrix, load_lid_model


def _session(
    model: object, threads: int = 1, extra: dict[str, str] | None = None
) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    for key, value in (extra or {}).items():
        so.add_session_config_entry(key, value)
    return ort.InferenceSession(model.SerializeToString(), so, providers=["CPUExecutionProvider"])


def _single_op_model(
    nodes: list[object], inits: list[object], inputs: list[object], outputs: list[object]
) -> object:
    graph = P.GraphProto(name="op_check")
    graph.node.extend(nodes)
    graph.initializer.extend(inits)
    graph.input.extend(inputs)
    graph.output.extend(outputs)
    return P.make_model(graph, opsets={"": 17, MS_DOMAIN: 1}, ir_version=8, producer_name="verify")


def check_proto_definitions() -> None:
    """同梱のモデルを読み、ノードと入出力が読めることを表示する。"""
    root = Path(ort.__file__).parent / "datasets"
    for path in sorted(glob.glob(str(root / "*.onnx"))):
        m = P.ModelProto()
        m.ParseFromString(Path(path).read_bytes())
        ops = [n.op_type for n in m.graph.node]
        ins = [i.name for i in m.graph.input]
        print(
            f"  {Path(path).name}: ir={m.ir_version} opsets={[(o.domain, o.version) for o in m.opset_import]} "
            f"ops={ops} inputs={ins}"
        )


def matmul_nbits_output(x: np.ndarray, m: Q4Matrix, packed: np.ndarray) -> np.ndarray:
    """MatMulNBits 1 つだけのグラフで x W^T を計算する（bias なし）。"""
    n_blocks = m.cols // Q4_GROUP_SIZE
    node = P.make_node(
        "MatMulNBits",
        ["x", "b", "s"],
        ["y"],
        "nbits",
        domain=MS_DOMAIN,
        K=m.cols,
        N=m.rows,
        bits=4,
        block_size=Q4_GROUP_SIZE,
    )
    inits = [
        P.make_tensor("b", packed.reshape(m.rows, n_blocks, Q4_GROUP_SIZE // 2)),
        P.make_tensor("s", m.scales.reshape(m.rows, n_blocks)),
    ]
    model = _single_op_model(
        [node],
        inits,
        [P.make_tensor_value_info("x", P.FLOAT, list(x.shape))],
        [P.make_tensor_value_info("y", P.FLOAT, [x.shape[0], m.rows])],
    )
    return _session(model).run(None, {"x": x})[0]


def check_matmul_nbits(m: Q4Matrix, name: str) -> None:
    """Q4 の重みの解釈（並び・ニブルの順・zero point の既定値 8）が Kotlin と同じことを確かめる。"""
    rng = np.random.default_rng(1)
    x = rng.standard_normal((7, m.cols)).astype(np.float32)
    expected = x @ m.dequantize().T
    got = matmul_nbits_output(x, m, m.packed)
    err = float(np.abs(got - expected).max())
    swapped = ((m.packed & 0x0F) << 4) | (m.packed >> 4)
    err_swapped = float(np.abs(matmul_nbits_output(x, m, swapped) - expected).max())
    scale = float(np.abs(expected).max())
    print(
        f"  MatMulNBits {name}: max|ORT−Kotlin|={err:.3e}（出力の最大 {scale:.2f}）、"
        f"ニブルを入れ替えた場合 {err_swapped:.3e}"
    )
    assert err < 1e-4 * max(1.0, scale), (
        "MatMulNBits の結果が Kotlin の dequantizeQ4Row と一致しない"
    )
    assert err_swapped > 100 * err, "ニブルの順の確認が効いていない"


def check_q8_gemm(m: Q8Matrix, name: str) -> None:
    """DequantizeLinear(axis=0) + Gemm(transB=1) が Kotlin の linearQ8Batch と同じことを確かめる。"""
    rng = np.random.default_rng(2)
    x = rng.standard_normal((5, m.cols)).astype(np.float32)
    nodes = [
        P.make_node("DequantizeLinear", ["q", "s"], ["w"], "dq", axis=0),
        P.make_node("Gemm", ["x", "w"], ["y"], "gemm", transB=1),
    ]
    inits = [P.make_tensor("q", m.quants), P.make_tensor("s", m.scales)]
    model = _single_op_model(
        nodes,
        inits,
        [P.make_tensor_value_info("x", P.FLOAT, list(x.shape))],
        [P.make_tensor_value_info("y", P.FLOAT, [x.shape[0], m.rows])],
    )
    got = _session(model).run(None, {"x": x})[0]
    expected = x @ m.dequantize().T
    err = float(np.abs(got - expected).max())
    print(
        f"  DQ+Gemm {name}: max|ORT−Kotlin|={err:.3e}（出力の最大 {float(np.abs(expected).max()):.2f}）"
    )
    assert err < 1e-3


def full_model_errors(
    sess: ort.InferenceSession, model: LidModel, pcm16: np.ndarray
) -> dict[str, float]:
    """1 つの音声で、ONNX と参照実装のロジット・エンコーダ出力・平均ベクトルの誤差を返す。"""
    pcm48, copied = R.prepare_window(pcm16)
    mel, _ = R.log_mel(model, pcm48, copied)
    logits, af = sess.run(None, {"mel": mel[None]})
    af_ref = R.encoder(model, mel)
    lg_ref = R.decoder(model, af_ref)
    emb = R.mean_pool(af[0], copied)
    emb_ref = R.mean_pool(af_ref, copied)
    cos = float(emb @ emb_ref / (np.linalg.norm(emb) * np.linalg.norm(emb_ref)))
    return {
        "logits": float(np.abs(logits[0] - lg_ref).max()),
        "audio_features": float(np.abs(af[0] - af_ref).max()),
        "embedding": float(np.abs(emb - emb_ref).max()),
        "embedding_cos": cos,
        "top1_same": float(int(np.argmax(logits[0])) == int(np.argmax(lg_ref))),
    }


def synth_signals(n: int = 6) -> list[np.ndarray]:
    """確認用の合成音声（倍音を持つ声のような音・雑音・無音を挟む）を作る。"""
    rng = np.random.default_rng(3)
    sr = 16_000
    out = []
    for i in range(n):
        length = int(sr * (0.6 + 0.5 * i))
        t = np.arange(length) / sr
        f0 = 110.0 + 40.0 * i
        sig = np.zeros(length)
        for h in range(1, 12):
            sig += np.sin(2 * np.pi * f0 * h * t * (1 + 0.02 * np.sin(2 * np.pi * 4 * t))) / h
        sig *= 0.5 * (1 + np.sin(2 * np.pi * (2 + i) * t)) * 0.2
        sig += 0.01 * rng.standard_normal(length)
        if i % 2 == 1:
            sig[: length // 5] = 0.0
        out.append(np.clip(sig * 32767, -32768, 32767).astype(np.int16))
    return out


def main() -> None:
    """確認を順に実行する。"""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bin", type=Path, required=True)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument(
        "--config", action="append", default=[], help="セッションの設定（key=value）"
    )
    args = parser.parse_args()
    model = load_lid_model(args.bin)

    print("1. protobuf の定義（同梱のモデルを読む）")
    check_proto_definitions()

    print("2. 演算ごとの確認")
    check_matmul_nbits(model.enc_blocks[0].mlp1_w, "enc0.mlp1 [1536x384]")
    check_matmul_nbits(model.enc_blocks[3].mlp2_w, "enc3.mlp2 [384x1536]")
    check_matmul_nbits(model.dec_blocks[2].mlp1_w, "dec2.mlp1 [1536x384]")
    check_q8_gemm(model.enc_blocks[0].q_w, "enc0.q [384x384]")
    check_q8_gemm(model.conv1_w, "conv1 [384x240]")
    check_q8_gemm(model.conv2_w, "conv2 [384x1152]")
    check_q8_gemm(model.lang_emb_w, "lang_emb [99x384]")

    print("3. モデル全体（合成音声）")
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    for item in args.config:
        key, value = item.split("=", 1)
        so.add_session_config_entry(key, value)
    sess = ort.InferenceSession(str(args.onnx), so, providers=["CPUExecutionProvider"])
    worst: dict[str, float] = {}
    for i, pcm in enumerate(synth_signals()):
        errs = full_model_errors(sess, model, pcm)
        print(
            f"  signal{i} ({pcm.size / 16000:.1f}s): "
            + ", ".join(f"{k}={v:.3e}" for k, v in errs.items())
        )
        for k, v in errs.items():
            worst[k] = (
                max(worst.get(k, 0.0), v)
                if k not in ("embedding_cos", "top1_same")
                else min(worst.get(k, 1.0), v)
            )
    print("  最悪値: " + ", ".join(f"{k}={v:.3e}" for k, v in worst.items()))


if __name__ == "__main__":
    main()
