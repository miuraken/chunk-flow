"""ONNX のモデル（onnx.proto3）を、`onnx` パッケージなしで読み書きするための最小限の定義。

`onnx` パッケージ（と `torch.onnx.export`）が使えない環境で、`google.protobuf` の記述子（descriptor）を
その場で組み立てて ONNX のメッセージを作る。必要なメッセージとフィールドだけを定義する。

フィールド番号は onnx/onnx.proto3（IR version 8〜10）に合わせている。enum のフィールド
（`AttributeProto.type`、`TensorProto.data_location`）はワイヤ形式が同じ int32 として定義し、
oneof（`TypeProto.value`、`TensorShapeProto.Dimension.value`）は普通のフィールドとして定義する。
定義が正しいことは、ONNX Runtime に同梱のモデル（`onnxruntime/datasets/*.onnx`）を読めることと、
このモジュールで書いたモデルを ONNX Runtime が読み込めることで確かめる（`verify_onnx.py`）。
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence

import numpy as np
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

_F = descriptor_pb2.FieldDescriptorProto

# (名前, 番号, 型, ラベル, メッセージ型の名前)
_FieldSpec = tuple[str, int, int, int, str | None]

_OPT = _F.LABEL_OPTIONAL
_REP = _F.LABEL_REPEATED

_MESSAGES: dict[str, list[_FieldSpec]] = {
    "StringStringEntryProto": [
        ("key", 1, _F.TYPE_STRING, _OPT, None),
        ("value", 2, _F.TYPE_STRING, _OPT, None),
    ],
    "TensorShapeProto": [
        ("dim", 1, _F.TYPE_MESSAGE, _REP, ".onnx.TensorShapeProto.Dimension"),
    ],
    "TensorShapeProto.Dimension": [
        ("dim_value", 1, _F.TYPE_INT64, _OPT, None),
        ("dim_param", 2, _F.TYPE_STRING, _OPT, None),
        ("denotation", 3, _F.TYPE_STRING, _OPT, None),
    ],
    "TypeProto": [
        ("tensor_type", 1, _F.TYPE_MESSAGE, _OPT, ".onnx.TypeProto.Tensor"),
        ("denotation", 6, _F.TYPE_STRING, _OPT, None),
    ],
    "TypeProto.Tensor": [
        ("elem_type", 1, _F.TYPE_INT32, _OPT, None),
        ("shape", 2, _F.TYPE_MESSAGE, _OPT, ".onnx.TensorShapeProto"),
    ],
    "ValueInfoProto": [
        ("name", 1, _F.TYPE_STRING, _OPT, None),
        ("type", 2, _F.TYPE_MESSAGE, _OPT, ".onnx.TypeProto"),
        ("doc_string", 3, _F.TYPE_STRING, _OPT, None),
    ],
    "TensorProto": [
        ("dims", 1, _F.TYPE_INT64, _REP, None),
        ("data_type", 2, _F.TYPE_INT32, _OPT, None),
        ("float_data", 4, _F.TYPE_FLOAT, _REP, None),
        ("int32_data", 5, _F.TYPE_INT32, _REP, None),
        ("string_data", 6, _F.TYPE_BYTES, _REP, None),
        ("int64_data", 7, _F.TYPE_INT64, _REP, None),
        ("name", 8, _F.TYPE_STRING, _OPT, None),
        ("raw_data", 9, _F.TYPE_BYTES, _OPT, None),
        ("double_data", 10, _F.TYPE_DOUBLE, _REP, None),
        ("uint64_data", 11, _F.TYPE_UINT64, _REP, None),
        ("doc_string", 12, _F.TYPE_STRING, _OPT, None),
        ("external_data", 13, _F.TYPE_MESSAGE, _REP, ".onnx.StringStringEntryProto"),
        ("data_location", 14, _F.TYPE_INT32, _OPT, None),
    ],
    "AttributeProto": [
        ("name", 1, _F.TYPE_STRING, _OPT, None),
        ("f", 2, _F.TYPE_FLOAT, _OPT, None),
        ("i", 3, _F.TYPE_INT64, _OPT, None),
        ("s", 4, _F.TYPE_BYTES, _OPT, None),
        ("t", 5, _F.TYPE_MESSAGE, _OPT, ".onnx.TensorProto"),
        ("g", 6, _F.TYPE_MESSAGE, _OPT, ".onnx.GraphProto"),
        ("floats", 7, _F.TYPE_FLOAT, _REP, None),
        ("ints", 8, _F.TYPE_INT64, _REP, None),
        ("strings", 9, _F.TYPE_BYTES, _REP, None),
        ("tensors", 10, _F.TYPE_MESSAGE, _REP, ".onnx.TensorProto"),
        ("doc_string", 13, _F.TYPE_STRING, _OPT, None),
        ("type", 20, _F.TYPE_INT32, _OPT, None),
        ("ref_attr_name", 21, _F.TYPE_STRING, _OPT, None),
    ],
    "NodeProto": [
        ("input", 1, _F.TYPE_STRING, _REP, None),
        ("output", 2, _F.TYPE_STRING, _REP, None),
        ("name", 3, _F.TYPE_STRING, _OPT, None),
        ("op_type", 4, _F.TYPE_STRING, _OPT, None),
        ("attribute", 5, _F.TYPE_MESSAGE, _REP, ".onnx.AttributeProto"),
        ("doc_string", 6, _F.TYPE_STRING, _OPT, None),
        ("domain", 7, _F.TYPE_STRING, _OPT, None),
    ],
    "GraphProto": [
        ("node", 1, _F.TYPE_MESSAGE, _REP, ".onnx.NodeProto"),
        ("name", 2, _F.TYPE_STRING, _OPT, None),
        ("initializer", 5, _F.TYPE_MESSAGE, _REP, ".onnx.TensorProto"),
        ("doc_string", 10, _F.TYPE_STRING, _OPT, None),
        ("input", 11, _F.TYPE_MESSAGE, _REP, ".onnx.ValueInfoProto"),
        ("output", 12, _F.TYPE_MESSAGE, _REP, ".onnx.ValueInfoProto"),
        ("value_info", 13, _F.TYPE_MESSAGE, _REP, ".onnx.ValueInfoProto"),
    ],
    "OperatorSetIdProto": [
        ("domain", 1, _F.TYPE_STRING, _OPT, None),
        ("version", 2, _F.TYPE_INT64, _OPT, None),
    ],
    "ModelProto": [
        ("ir_version", 1, _F.TYPE_INT64, _OPT, None),
        ("producer_name", 2, _F.TYPE_STRING, _OPT, None),
        ("producer_version", 3, _F.TYPE_STRING, _OPT, None),
        ("domain", 4, _F.TYPE_STRING, _OPT, None),
        ("model_version", 5, _F.TYPE_INT64, _OPT, None),
        ("doc_string", 6, _F.TYPE_STRING, _OPT, None),
        ("graph", 7, _F.TYPE_MESSAGE, _OPT, ".onnx.GraphProto"),
        ("opset_import", 8, _F.TYPE_MESSAGE, _REP, ".onnx.OperatorSetIdProto"),
        ("metadata_props", 14, _F.TYPE_MESSAGE, _REP, ".onnx.StringStringEntryProto"),
    ],
}

# AttributeProto.AttributeType
ATTR_FLOAT = 1
ATTR_INT = 2
ATTR_STRING = 3
ATTR_TENSOR = 4
ATTR_FLOATS = 6
ATTR_INTS = 7

# TensorProto.DataType
FLOAT = 1
UINT8 = 2
INT8 = 3
INT32 = 6
INT64 = 7

# TensorProto.DataLocation
DATA_LOCATION_EXTERNAL = 1

_NP_TO_ONNX = {
    np.dtype(np.float32): FLOAT,
    np.dtype(np.uint8): UINT8,
    np.dtype(np.int8): INT8,
    np.dtype(np.int32): INT32,
    np.dtype(np.int64): INT64,
}


def _build_classes() -> dict[str, type]:
    """記述子を組み立てて、メッセージのクラスを返す。"""
    fdp = descriptor_pb2.FileDescriptorProto(
        name="chunkflow_onnx_subset.proto", package="onnx", syntax="proto3"
    )
    top: dict[str, descriptor_pb2.DescriptorProto] = {}
    for full_name in _MESSAGES:
        parts = full_name.split(".")
        if len(parts) == 1:
            top[parts[0]] = fdp.message_type.add(name=parts[0])
    for full_name, fields in _MESSAGES.items():
        parts = full_name.split(".")
        msg = top[parts[0]] if len(parts) == 1 else top[parts[0]].nested_type.add(name=parts[1])
        for name, number, ftype, label, type_name in fields:
            field = msg.field.add(name=name, number=number, type=ftype, label=label)
            if type_name is not None:
                field.type_name = type_name
    pool = descriptor_pool.DescriptorPool()
    pool.Add(fdp)
    return {
        name: message_factory.GetMessageClass(pool.FindMessageTypeByName(f"onnx.{name}"))
        for name in _MESSAGES
        if "." not in name
    }


_CLASSES = _build_classes()
ModelProto = _CLASSES["ModelProto"]
GraphProto = _CLASSES["GraphProto"]
NodeProto = _CLASSES["NodeProto"]
TensorProto = _CLASSES["TensorProto"]
ValueInfoProto = _CLASSES["ValueInfoProto"]
AttributeProto = _CLASSES["AttributeProto"]


def make_attribute(name: str, value: object) -> object:
    """Python の値から AttributeProto を作る（int・float・str・int の列・float の列に対応）。"""
    attr = AttributeProto(name=name)
    if isinstance(value, bool):
        raise TypeError(f"bool の属性には対応していない: {name}")
    if isinstance(value, int):
        attr.type = ATTR_INT
        attr.i = value
    elif isinstance(value, float):
        attr.type = ATTR_FLOAT
        attr.f = value
    elif isinstance(value, str):
        attr.type = ATTR_STRING
        attr.s = value.encode("utf-8")
    elif isinstance(value, Sequence) and all(isinstance(v, int) for v in value):
        attr.type = ATTR_INTS
        attr.ints.extend(value)
    elif isinstance(value, Sequence) and all(isinstance(v, float) for v in value):
        attr.type = ATTR_FLOATS
        attr.floats.extend(value)
    else:
        raise TypeError(f"対応していない属性の値: {name}={value!r}")
    return attr


def make_node(
    op_type: str,
    inputs: Iterable[str],
    outputs: Iterable[str],
    name: str,
    domain: str = "",
    **attrs: object,
) -> object:
    """NodeProto を作る。省略する任意の入力は空文字列で表す。"""
    node = NodeProto(op_type=op_type, name=name, domain=domain)
    node.input.extend(inputs)
    node.output.extend(outputs)
    for key in sorted(attrs):
        node.attribute.append(make_attribute(key, attrs[key]))
    return node


def make_tensor(name: str, array: np.ndarray) -> object:
    """numpy 配列から、値を raw_data（リトルエンディアン）に持つ TensorProto を作る。"""
    arr = np.ascontiguousarray(array)
    data_type = _NP_TO_ONNX[arr.dtype]
    tensor = TensorProto(name=name, data_type=data_type)
    tensor.dims.extend(int(d) for d in arr.shape)
    tensor.raw_data = arr.astype(arr.dtype.newbyteorder("<"), copy=False).tobytes()
    return tensor


def make_external_tensor(
    name: str,
    dtype: np.dtype,
    shape: Sequence[int],
    location: str,
    offset: int,
    length: int,
) -> object:
    """値をモデルの外のファイル（[location] の [offset] から [length] バイト）に持つ TensorProto を作る。"""
    tensor = TensorProto(name=name, data_type=_NP_TO_ONNX[np.dtype(dtype)])
    tensor.dims.extend(int(d) for d in shape)
    tensor.data_location = DATA_LOCATION_EXTERNAL
    for key, value in (("location", location), ("offset", str(offset)), ("length", str(length))):
        tensor.external_data.add(key=key, value=value)
    return tensor


def make_tensor_value_info(name: str, elem_type: int, shape: Sequence[int | str]) -> object:
    """テンソル型の ValueInfoProto を作る（文字列の次元は記号の次元）。"""
    info = ValueInfoProto(name=name)
    tensor_type = info.type.tensor_type
    tensor_type.elem_type = elem_type
    for dim in shape:
        d = tensor_type.shape.dim.add()
        if isinstance(dim, str):
            d.dim_param = dim
        else:
            d.dim_value = int(dim)
    return info


def make_model(
    graph: object,
    opsets: dict[str, int],
    ir_version: int,
    producer_name: str,
    metadata: dict[str, str] | None = None,
) -> object:
    """ModelProto を作る。[opsets] はドメイン（既定のドメインは空文字列）から版への対応。"""
    model = ModelProto(ir_version=ir_version, producer_name=producer_name)
    model.graph.CopyFrom(graph)
    for domain, version in opsets.items():
        model.opset_import.add(domain=domain, version=version)
    for key, value in (metadata or {}).items():
        model.metadata_props.add(key=key, value=value)
    return model
