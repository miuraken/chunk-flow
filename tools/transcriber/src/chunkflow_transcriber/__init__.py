"""Chunk Flow の PC 用文字起こしツール。

音声を Whisper で文字起こしし、`chunkflow-transcript` v1 の JSON（design_doc.md §7.4.3）を作る。
"""

__version__ = "0.1.0"

TOOL_NAME = "chunkflow-transcribe"


def tool_id() -> str:
    """`generator.tool` に書くツール名と版を返す。"""
    return f"{TOOL_NAME} {__version__}"
