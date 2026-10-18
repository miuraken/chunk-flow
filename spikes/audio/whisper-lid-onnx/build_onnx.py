"""`whisper_tiny_lid_q8q4.bin` の重み（量子化の値もそのまま）から、エンコーダと融合デコーダの ONNX を作る。

入力は Kotlin が計算した正規化 log-mel、出力は 99 言語のロジットとエンコーダ出力（LayerNorm 後）。
mel の計算、平均プーリング、同系言語の合算と 2 言語の判定は Kotlin 側に残す。

グラフの構成（すべて 2 次元のテンソルで計算する。時間が先 = Kotlin の配列の並び）:
- 入力 `mel` [1, 300, 80] → [300, 80]
- conv1 / conv2: im2col（0 埋めの Pad + Slice + Concat）+ Gemm。重みは .bin の並び [o][k][c] のまま使える。
- Q8 の重み（行ごとの scale）: DequantizeLinear(int8, axis=0) → Gemm(transB=1)。
  `session.disable_quant_qdq=1` にすると、読み込み時の定数畳み込みで float の重みになる。
- Q4 の重み（64 要素ごとの scale）: com.microsoft.MatMulNBits（bits=4, block_size=64、zero point は省略 = 8）。
  .bin の packed（[N][K/64][32 バイト]、下位ニブルが偶数列）と scale（[N][K/64]）をそのまま入れる。
- 注意: 標準の演算（Reshape・Transpose・MatMul・Softmax）か com.microsoft.MultiHeadAttention。
- 融合デコーダ: Kotlin と同じ式。ヘッドごとの積は、ブロック対角のマスクで 1 回の行列積にまとめる。
- 出力 `logits` [1, 99]、`audio_features` [1, 150, 384]。

`--external-data` を付けると、.bin の中のテンソルを外部データ（.bin のオフセットと長さ）として参照する、
重みを持たないグラフを作る（APK を増やさない案の検証用）。

使い方:
    python build_onnx.py --bin ../../../core/media/src/main/assets/whisper_tiny_lid_q8q4.bin \
        --out ../../../core/media/src/main/assets/whisper_tiny_lid.onnx
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np

import onnx_proto as P
from lid_bin import (
    D_HEAD,
    D_MODEL,
    N_AUDIO_CTX,
    N_FRAMES,
    N_HEAD,
    N_MELS,
    Q4_GROUP_SIZE,
    FloatTensor,
    LidModel,
    Q4Matrix,
    Q8Matrix,
    load_lid_model,
)

MS_DOMAIN = "com.microsoft"
OPSET = 17
IR_VERSION = 8
INPUT_MEL = "mel"
OUTPUT_LOGITS = "logits"
OUTPUT_AUDIO_FEATURES = "audio_features"


@dataclass(frozen=True)
class BuildOptions:
    """グラフの作り方の選択肢。"""

    q4_accuracy_level: int = 0
    """MatMulNBits の accuracy_level（0 = float と同じ計算、4 = 入力を int8 にして計算。精度が変わる）。"""
    nbits_bias: bool = True
    """MatMulNBits の bias 入力にバイアスを渡すか（False なら Add で足す）。"""
    attention: str = "matmul"
    """エンコーダの自己注意: "matmul"（標準の演算）か "mha"（com.microsoft.MultiHeadAttention）。"""
    q8: str = "dq"
    """Q8 の重みの表し方: "dq"（DequantizeLinear + Gemm）か "nbits8"（MatMulNBits bits=8、重みは int8 のまま）。"""
    q8_accuracy_level: int = 0
    """q8="nbits8" のときの MatMulNBits の accuracy_level。"""
    external_data: str | None = None
    """.bin のファイル名。指定すると、.bin のテンソルを外部データとして参照する（重みを持たないグラフ）。"""


class GraphBuilder:
    """ノードと初期値（initializer）を集めて GraphProto を作る。"""

    def __init__(self, model: LidModel, options: BuildOptions) -> None:
        self.model = model
        self.options = options
        self.nodes: list[object] = []
        self.initializers: list[object] = []
        self._names: set[str] = set()
        self._const_cache: dict[tuple[str, bytes, tuple[int, ...]], str] = {}

    # ---- 名前と初期値 ----

    def unique(self, base: str) -> str:
        """重ならない名前を返す。"""
        name = base
        i = 1
        while name in self._names:
            name = f"{base}_{i}"
            i += 1
        self._names.add(name)
        return name

    def const(self, base: str, array: np.ndarray) -> str:
        """グラフに埋め込む定数（.bin にない小さな値）。同じ値は使い回す。"""
        arr = np.ascontiguousarray(array)
        key = (str(arr.dtype), arr.tobytes(), arr.shape)
        cached = self._const_cache.get(key)
        if cached is not None:
            return cached
        name = self.unique(base)
        self.initializers.append(P.make_tensor(name, arr))
        self._const_cache[key] = name
        return name

    def weight(
        self, base: str, array: np.ndarray, segment: object, shape: tuple[int, ...] | None = None
    ) -> str:
        """.bin のテンソル。外部データ方式では .bin の位置を参照し、そうでなければ値を埋め込む。"""
        name = self.unique(base)
        shape = shape if shape is not None else array.shape
        if self.options.external_data is not None:
            self.initializers.append(
                P.make_external_tensor(
                    name,
                    array.dtype,
                    shape,
                    self.options.external_data,
                    segment.offset,
                    segment.length,
                )
            )
        else:
            self.initializers.append(P.make_tensor(name, array.reshape(shape)))
        return name

    def float_weight(
        self, base: str, tensor: FloatTensor, shape: tuple[int, ...] | None = None
    ) -> str:
        """float32 の .bin テンソル。"""
        return self.weight(base, tensor.values, tensor.segment, shape)

    # ---- ノード ----

    def node(
        self,
        op: str,
        inputs: list[str],
        base: str,
        domain: str = "",
        n_out: int = 1,
        **attrs: object,
    ) -> str:
        """ノードを足して、最初の出力の名前を返す。"""
        outputs = [self.unique(f"{base}_out" if i == 0 else f"{base}_out{i}") for i in range(n_out)]
        self.nodes.append(
            P.make_node(op, inputs, outputs, self.unique(base), domain=domain, **attrs)
        )
        return outputs[0]

    def node_to(
        self, op: str, inputs: list[str], output: str, base: str, domain: str = "", **attrs: object
    ) -> str:
        """出力の名前を指定してノードを足す（グラフの出力用）。"""
        self._names.add(output)
        self.nodes.append(
            P.make_node(op, inputs, [output], self.unique(base), domain=domain, **attrs)
        )
        return output

    def reshape(self, x: str, shape: list[int], base: str) -> str:
        """Reshape。"""
        return self.node("Reshape", [x, self.const("shape", np.array(shape, dtype=np.int64))], base)

    def layer_norm(self, x: str, w: FloatTensor, b: FloatTensor, base: str) -> str:
        """最後の軸の LayerNormalization（epsilon = 1e-5）。"""
        scale = self.float_weight(f"{base}.weight", w)
        bias = self.float_weight(f"{base}.bias", b)
        return self.node("LayerNormalization", [x, scale, bias], base, axis=-1, epsilon=1e-5)

    def dequant_q8(self, m: Q8Matrix, base: str) -> str:
        """Q8 の行列 [rows, cols] を DequantizeLinear で float にする（行ごとの scale、zero point 0）。"""
        q = self.weight(f"{base}.qweight", m.quants, m.quants_segment)
        s = self.weight(f"{base}.scale", m.scales, m.scales_segment)
        return self.node("DequantizeLinear", [q, s], f"{base}.dq", axis=0)

    def linear_q8(self, x: str, m: Q8Matrix, bias: FloatTensor | None, base: str) -> str:
        """y = x W^T + b（W は Q8）。"""
        if self.options.q8 == "nbits8":
            return self.linear_q8_nbits(x, m, bias, base)
        w = self.dequant_q8(m, base)
        inputs = [x, w]
        if bias is not None:
            inputs.append(self.float_weight(f"{base}.bias", bias))
        return self.node("Gemm", inputs, f"{base}.gemm", transB=1)

    def linear_q8_nbits(self, x: str, m: Q8Matrix, bias: FloatTensor | None, base: str) -> str:
        """y = x W^T + b を MatMulNBits（bits=8）で計算する。

        値は uint8（int8 + 128、zero point の既定値 128 で int8 と同じ値になる）、scale は行の scale をブロックの数だけ並べる。
        """
        if self.options.external_data is not None:
            raise ValueError("nbits8 は外部データ方式では使えない（.bin と並びが違う）")
        block = 128
        while m.cols % block != 0:
            block //= 2
        if block < 16:
            raise ValueError(f"K={m.cols} に合うブロックの大きさがない")
        n_blocks = m.cols // block
        packed = (m.quants.view(np.uint8) ^ np.uint8(0x80)).reshape(m.rows, n_blocks, block)
        scales = np.repeat(m.scales[:, None], n_blocks, axis=1).astype(np.float32)
        b = self.unique(f"{base}.qweight_u8")
        self.initializers.append(P.make_tensor(b, packed))
        s = self.unique(f"{base}.scales_rep")
        self.initializers.append(P.make_tensor(s, scales))
        attrs: dict[str, object] = {"K": m.cols, "N": m.rows, "bits": 8, "block_size": block}
        if self.options.q8_accuracy_level:
            attrs["accuracy_level"] = self.options.q8_accuracy_level
        inputs = [x, b, s]
        if bias is not None:
            inputs += ["", "", self.float_weight(f"{base}.bias", bias)]
        return self.node("MatMulNBits", inputs, f"{base}.nbits8", domain=MS_DOMAIN, **attrs)

    def linear_q4(self, x: str, m: Q4Matrix, bias: FloatTensor, base: str) -> str:
        """y = x W^T + b（W は Q4、MatMulNBits）。"""
        n_blocks = m.cols // Q4_GROUP_SIZE
        b = self.weight(
            f"{base}.qweight", m.packed, m.packed_segment, (m.rows, n_blocks, Q4_GROUP_SIZE // 2)
        )
        s = self.weight(f"{base}.scales", m.scales, m.scales_segment, (m.rows, n_blocks))
        bias_name = self.float_weight(f"{base}.bias", bias)
        attrs: dict[str, object] = {
            "K": m.cols,
            "N": m.rows,
            "bits": 4,
            "block_size": Q4_GROUP_SIZE,
        }
        if self.options.q4_accuracy_level:
            attrs["accuracy_level"] = self.options.q4_accuracy_level
        if self.options.nbits_bias:
            return self.node(
                "MatMulNBits",
                [x, b, s, "", "", bias_name],
                f"{base}.nbits",
                domain=MS_DOMAIN,
                **attrs,
            )
        y = self.node("MatMulNBits", [x, b, s], f"{base}.nbits", domain=MS_DOMAIN, **attrs)
        return self.node("Add", [y, bias_name], f"{base}.add")

    def gelu(self, x: str, base: str) -> str:
        """GELU（erf を使う正確な式、com.microsoft.Gelu）。"""
        return self.node("Gelu", [x], base, domain=MS_DOMAIN)

    def conv_k3(
        self, x: str, t_in: int, stride: int, m: Q8Matrix, bias: FloatTensor, base: str
    ) -> str:
        """カーネル 3・両端 0 埋め 1 の畳み込みを im2col + Gemm で行う。x は [T, C]（時間が先）。"""
        pads = self.const("pads_t1", np.array([1, 0, 1, 0], dtype=np.int64))
        padded = self.node("Pad", [x, pads], f"{base}.pad")
        t_out = (t_in - 1) // stride + 1
        axes = self.const("axes0", np.array([0], dtype=np.int64))
        steps = self.const(f"steps{stride}", np.array([stride], dtype=np.int64))
        parts = []
        for k in range(3):
            starts = self.const(f"start{k}", np.array([k], dtype=np.int64))
            ends = self.const(
                f"end{k + stride * (t_out - 1) + 1}",
                np.array([k + stride * (t_out - 1) + 1], dtype=np.int64),
            )
            parts.append(
                self.node("Slice", [padded, starts, ends, axes, steps], f"{base}.slice{k}")
            )
        cols = self.node("Concat", parts, f"{base}.im2col", axis=1)
        return self.linear_q8(cols, m, bias, base)

    # ---- モデル ----

    def build(self) -> object:
        """グラフ全体を作る。"""
        model = self.model
        self._names.update({INPUT_MEL, OUTPUT_LOGITS, OUTPUT_AUDIO_FEATURES})
        mel = self.reshape(INPUT_MEL, [N_FRAMES, N_MELS], "mel2d")
        x = self.gelu(
            self.conv_k3(mel, N_FRAMES, 1, model.conv1_w, model.conv1_b, "conv1"), "conv1.gelu"
        )
        x = self.gelu(
            self.conv_k3(x, N_FRAMES, 2, model.conv2_w, model.conv2_b, "conv2"), "conv2.gelu"
        )
        x = self.node("Add", [x, self.float_weight("enc_pos", model.enc_pos)], "enc_pos.add")
        for i, blk in enumerate(model.enc_blocks):
            x = self.encoder_block(x, blk, f"enc{i}")
        af = self.layer_norm(x, model.ln_post_w, model.ln_post_b, "ln_post")
        self.node_to(
            "Reshape",
            [af, self.const("shape", np.array([1, N_AUDIO_CTX, D_MODEL], dtype=np.int64))],
            OUTPUT_AUDIO_FEATURES,
            "audio_features.reshape",
        )
        logits = self.decoder(af)
        self.node_to("Identity", [logits], OUTPUT_LOGITS, "logits.identity")
        graph = P.GraphProto(name="whisper_tiny_lid")
        graph.node.extend(self.nodes)
        graph.initializer.extend(self.initializers)
        graph.input.append(P.make_tensor_value_info(INPUT_MEL, P.FLOAT, [1, N_FRAMES, N_MELS]))
        graph.output.append(
            P.make_tensor_value_info(OUTPUT_LOGITS, P.FLOAT, [1, len(model.language_codes)])
        )
        graph.output.append(
            P.make_tensor_value_info(OUTPUT_AUDIO_FEATURES, P.FLOAT, [1, N_AUDIO_CTX, D_MODEL])
        )
        return graph

    def encoder_block(self, x: str, blk: object, base: str) -> str:
        """エンコーダの 1 層（pre-LN、K のバイアスなし、6 ヘッド、マスクなし）。"""
        h = self.layer_norm(x, blk.attn_ln_w, blk.attn_ln_b, f"{base}.attn_ln")
        q = self.linear_q8(h, blk.q_w, blk.q_b, f"{base}.q")
        k = self.linear_q8(h, blk.k_w, None, f"{base}.k")
        v = self.linear_q8(h, blk.v_w, blk.v_b, f"{base}.v")
        if self.options.attention == "mha":
            shape3 = [1, N_AUDIO_CTX, D_MODEL]
            o = self.node(
                "MultiHeadAttention",
                [
                    self.reshape(q, shape3, f"{base}.q3"),
                    self.reshape(k, shape3, f"{base}.k3"),
                    self.reshape(v, shape3, f"{base}.v3"),
                ],
                f"{base}.mha",
                domain=MS_DOMAIN,
                num_heads=N_HEAD,
                scale=0.125,
            )
            o = self.reshape(o, [N_AUDIO_CTX, D_MODEL], f"{base}.o2")
        else:
            qs = self.node(
                "Mul",
                [q, self.const("attn_scale", np.array(0.125, dtype=np.float32))],
                f"{base}.qscale",
            )
            head_shape = [N_AUDIO_CTX, N_HEAD, D_HEAD]
            qh = self.node(
                "Transpose",
                [self.reshape(qs, head_shape, f"{base}.qh")],
                f"{base}.qt",
                perm=[1, 0, 2],
            )
            kh = self.node(
                "Transpose",
                [self.reshape(k, head_shape, f"{base}.kh")],
                f"{base}.kt",
                perm=[1, 2, 0],
            )
            vh = self.node(
                "Transpose",
                [self.reshape(v, head_shape, f"{base}.vh")],
                f"{base}.vt",
                perm=[1, 0, 2],
            )
            scores = self.node("MatMul", [qh, kh], f"{base}.scores")
            probs = self.node("Softmax", [scores], f"{base}.softmax", axis=-1)
            o = self.node("MatMul", [probs, vh], f"{base}.pv")
            o = self.node("Transpose", [o], f"{base}.ot", perm=[1, 0, 2])
            o = self.reshape(o, [N_AUDIO_CTX, D_MODEL], f"{base}.o2")
        x = self.node(
            "Add", [x, self.linear_q8(o, blk.out_w, blk.out_b, f"{base}.out")], f"{base}.attn_res"
        )
        h = self.layer_norm(x, blk.mlp_ln_w, blk.mlp_ln_b, f"{base}.mlp_ln")
        m = self.gelu(self.linear_q4(h, blk.mlp1_w, blk.mlp1_b, f"{base}.mlp1"), f"{base}.mlp_gelu")
        m = self.linear_q4(m, blk.mlp2_w, blk.mlp2_b, f"{base}.mlp2")
        return self.node("Add", [x, m], f"{base}.mlp_res")

    def decoder(self, af: str) -> str:
        """融合デコーダ（Kotlin の `runFusedDecoder` と同じ式）。af は [150, 384]、戻り値は [1, 99]。"""
        model = self.model
        mask = np.zeros((N_HEAD, D_MODEL), dtype=np.float32)
        for h in range(N_HEAD):
            mask[h, h * D_HEAD : (h + 1) * D_HEAD] = 1.0
        head_mask = self.const("head_block_mask", mask)
        qk_scale = self.const("qk_scale", np.array(0.125, dtype=np.float32))
        axes0 = self.const("axes0", np.array([0], dtype=np.int64))
        xd = self.float_weight("dec.x0_after_self", model.x0_after_self, (1, D_MODEL))
        for i, blk in enumerate(model.dec_blocks):
            base = f"dec{i}"
            if i == 0:
                qk = self.float_weight("dec0.qk_const", model.qk0_const)
            else:
                h = self.layer_norm(xd, blk.attn_ln_w, blk.attn_ln_b, f"{base}.attn_ln")
                xd = self.node(
                    "Add",
                    [xd, self.linear_q8(h, blk.w_self, blk.b_self, f"{base}.self")],
                    f"{base}.self_res",
                )
                h = self.layer_norm(xd, blk.cross_ln_w, blk.cross_ln_b, f"{base}.cross_ln")
                q = self.linear_q8(h, blk.cq_w, blk.cq_b, f"{base}.cq")
                q = self.node("Mul", [q, qk_scale], f"{base}.qscale")
                q_block = self.node("Mul", [q, head_mask], f"{base}.qblock")
                qk = self.node(
                    "MatMul", [q_block, self.dequant_q8(blk.ck_w, f"{base}.ck")], f"{base}.qk"
                )
            scores = self.node("Gemm", [qk, af], f"{base}.scores", transB=1)
            probs = self.node("Softmax", [scores], f"{base}.softmax", axis=-1)
            pooled = self.node("MatMul", [probs, af], f"{base}.pooled")
            wv_all = self.node(
                "Gemm",
                [pooled, self.dequant_q8(blk.cv_w, f"{base}.cv")],
                f"{base}.wv_all",
                transB=1,
            )
            wv = self.node("Mul", [wv_all, head_mask], f"{base}.wv_block")
            wv = self.node("ReduceSum", [wv, axes0], f"{base}.wv_sum", keepdims=1)
            wv = self.node(
                "Add", [wv, self.float_weight(f"{base}.cv.bias", blk.cv_b)], f"{base}.wv_bias"
            )
            xd = self.node(
                "Add",
                [xd, self.linear_q8(wv, blk.cout_w, blk.cout_b, f"{base}.cout")],
                f"{base}.cross_res",
            )
            h = self.layer_norm(xd, blk.mlp_ln_w, blk.mlp_ln_b, f"{base}.mlp_ln")
            m = self.gelu(
                self.linear_q4(h, blk.mlp1_w, blk.mlp1_b, f"{base}.mlp1"), f"{base}.mlp_gelu"
            )
            m = self.linear_q4(m, blk.mlp2_w, blk.mlp2_b, f"{base}.mlp2")
            xd = self.node("Add", [xd, m], f"{base}.mlp_res")
        h = self.layer_norm(xd, model.dec_ln_w, model.dec_ln_b, "dec.ln")
        return self.linear_q8(h, model.lang_emb_w, None, "lang_emb")


def build_model(model: LidModel, options: BuildOptions, source_sha256: str) -> object:
    """ModelProto を作る。"""
    graph = GraphBuilder(model, options).build()
    metadata = {
        "chunkflow.source": "whisper_tiny_lid_q8q4.bin",
        "chunkflow.source_sha256": source_sha256,
        "chunkflow.language_codes": ",".join(model.language_codes),
        "chunkflow.q4_accuracy_level": str(options.q4_accuracy_level),
        "chunkflow.attention": options.attention,
        "chunkflow.q8": options.q8,
        "chunkflow.mel_layout": "time_major_300x80",
    }
    return P.make_model(
        graph,
        opsets={"": OPSET, MS_DOMAIN: 1},
        ir_version=IR_VERSION,
        producer_name="chunkflow-whisper-lid-onnx",
        metadata=metadata,
    )


def main() -> None:
    """コマンドラインから ONNX を作る。"""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bin", type=Path, required=True, help="whisper_tiny_lid_q8q4.bin")
    parser.add_argument("--out", type=Path, required=True, help="出力する .onnx")
    parser.add_argument("--q4-accuracy-level", type=int, default=0, choices=[0, 1, 4])
    parser.add_argument("--attention", choices=["matmul", "mha"], default="matmul")
    parser.add_argument("--q8", choices=["dq", "nbits8"], default="dq", help="Q8 の重みの表し方")
    parser.add_argument("--q8-accuracy-level", type=int, default=0, choices=[0, 1, 4])
    parser.add_argument(
        "--no-nbits-bias", action="store_true", help="MatMulNBits の bias 入力を使わず Add で足す"
    )
    parser.add_argument(
        "--external-data", action="store_true", help=".bin を外部データとして参照する（重みなし）"
    )
    args = parser.parse_args()

    data = args.bin.read_bytes()
    model = load_lid_model(args.bin)
    options = BuildOptions(
        q4_accuracy_level=args.q4_accuracy_level,
        nbits_bias=not args.no_nbits_bias,
        attention=args.attention,
        q8=args.q8,
        q8_accuracy_level=args.q8_accuracy_level,
        external_data=args.bin.name if args.external_data else None,
    )
    proto = build_model(model, options, hashlib.sha256(data).hexdigest())
    args.out.write_bytes(proto.SerializeToString())
    print(
        f"wrote {args.out} ({args.out.stat().st_size} bytes, {len(proto.graph.node)} nodes, "
        f"{len(proto.graph.initializer)} initializers)"
    )


if __name__ == "__main__":
    main()
