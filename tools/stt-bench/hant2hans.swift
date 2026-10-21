// 繁体字を簡体字に変える（ICU の "Hant-Hans" 変換）。tools/stt-bench/score.py が中国語・広東語の採点の前に呼ぶ。
// 標準入力の 1 行ごとに変換して標準出力に書く。使い方: swift tools/stt-bench/hant2hans.swift < in.txt
import Foundation

while let line = readLine(strippingNewline: true) {
    let converted = line.applyingTransform(StringTransform("Hant-Hans"), reverse: false) ?? line
    print(converted)
}
