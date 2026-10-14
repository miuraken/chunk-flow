"""マージ後のマニフェストの権限とエクスポートされたコンポーネントが、設計書 §8.2 の一覧を超えないことを確かめる。

使い方:
    ./gradlew :app:processDebugMainManifest :app:processReleaseMainManifest
    python3 .github/scripts/check_manifest.py            # app/build の下のマージ後のマニフェストをすべて調べる
    python3 .github/scripts/check_manifest.py <AndroidManifest.xml> ...

標準ライブラリだけで動く（CI とローカルの両方で使う）。一覧を変えるときは ADR に理由を書く（AGENTS.md §10）。
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[2]
APPLICATION_ID = "com.miuraken.chunkflow"

# §8.2 で宣言してよい権限。
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.WAKE_LOCK",
    "android.permission.POST_NOTIFICATIONS",
    # §9: WorkManager が持ち込むネットワーク関連の権限で、許容するのはこれだけ。
    "android.permission.ACCESS_NETWORK_STATE",
    # androidx.core が宣言・使用する signature 保護の独自権限（動的に登録する非公開のレシーバー用）。
    # 他のアプリには与えられないため、§8.2 の一覧の対象外として許容する（ADR 0001）。
    f"{APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}

# §8.2 でエクスポートしてよいコンポーネント（タグ, 完全修飾名の判定）。
ALLOWED_EXPORTED = [
    ("activity", lambda name: name == f"{APPLICATION_ID}.MainActivity"),
    ("service", lambda name: name.startswith(f"{APPLICATION_ID}.") and name.endswith(".PlaybackService")),
    ("receiver", lambda name: name == "androidx.media3.session.MediaButtonReceiver"),
]

# android.permission.DUMP（signature|privileged。shell とシステムだけが持つ）で保護された、AndroidX の診断用の受信機。
# 他のアプリからは呼べないため、エクスポートの一覧の対象外として許容する（ADR 0001）。
DUMP_PROTECTED_ALLOWED = {
    "androidx.profileinstaller.ProfileInstallerReceiver",
    "androidx.work.impl.diagnostics.DiagnosticsReceiver",
}

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def full_name(name: str, package: str) -> str:
    """マニフェストの android:name を完全修飾名にする。"""
    if name.startswith("."):
        return package + name
    if "." not in name:
        return f"{package}.{name}"
    return name


def check(path: Path) -> list[str]:
    """1 つのマニフェストを調べ、問題の一覧を返す。"""
    problems: list[str] = []
    root = ET.parse(path).getroot()
    package = root.get("package", APPLICATION_ID)

    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for el in root.iter(tag):
            perm = el.get(f"{ANDROID}name", "")
            if perm not in ALLOWED_PERMISSIONS:
                problems.append(f"許可されていない権限: {perm}")

    app = root.find("application")
    if app is not None:
        if app.get(f"{ANDROID}usesCleartextTraffic") == "true":
            problems.append("usesCleartextTraffic=true は許可しない（§8.2）")
        for tag in COMPONENT_TAGS:
            for el in app.iter(tag):
                name = full_name(el.get(f"{ANDROID}name", ""), package)
                exported_attr = el.get(f"{ANDROID}exported")
                has_filter = el.find("intent-filter") is not None
                exported = exported_attr == "true" or (exported_attr is None and has_filter)
                if not exported:
                    continue
                if any(t == tag and ok(name) for t, ok in ALLOWED_EXPORTED):
                    continue
                permission = el.get(f"{ANDROID}permission")
                if name in DUMP_PROTECTED_ALLOWED and permission == "android.permission.DUMP":
                    continue
                problems.append(f"許可されていないエクスポート: <{tag}> {name}（permission={permission}）")
    return problems


def find_manifests() -> list[Path]:
    """app/build の下のマージ後のマニフェストを探す。"""
    base = ROOT / "app" / "build" / "intermediates"
    return sorted(p for p in base.glob("merged_manifest*/**/AndroidManifest.xml") if p.is_file())


def main(argv: list[str]) -> int:
    manifests = [Path(a) for a in argv] if argv else find_manifests()
    if not manifests:
        print(
            "マージ後のマニフェストが見つからない。先に ./gradlew :app:processDebugMainManifest を実行する。",
            file=sys.stderr,
        )
        return 2
    failed = False
    for path in manifests:
        problems = check(path)
        rel = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
        if problems:
            failed = True
            print(f"NG  {rel}")
            for p in problems:
                print(f"    - {p}")
        else:
            print(f"OK  {rel}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
