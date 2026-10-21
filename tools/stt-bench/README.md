# tools/stt-bench — 端末内の音声認識エンジンのベンチマーク

端末内の音声認識エンジン（ML Kit GenAI・`SpeechRecognizer`・sherpa-onnx Whisper Tiny/Base/Small・SenseVoice Small・
Moonshine Tiny/Base）の速度と精度を、言語ごとに実機で測るための PC 側のスクリプトです。
端末側の計測は debug ビルドだけに入る `SttBenchActivity`（`core/media/src/debug/.../bench/`）が行います。

> [!IMPORTANT]
> 実際の教材の音声・切り出したクリップ・文字起こし（正解を含む）は著作物なので、コミットしません（AGENTS.md §10）。
> 出力はすべて gitignore された `sample_files/stt-bench/` の下に置きます。合成音声のクリップも `make_tts_clips.py` で作り直せるのでコミットしません。

## 使うもの

- macOS の `python3`（標準ライブラリだけ）、`ffmpeg`・`ffprobe`、`say`（合成音声）、`swift`（繁体字→簡体字の変換）
- debug ビルドのアプリを入れた端末（`adb`）

## 手順

```sh
D=<端末のシリアル>
BENCH=/sdcard/Android/data/com.miuraken.chunkflow/files/stt-bench
start() { adb -s $D shell am start -n com.miuraken.chunkflow/com.miuraken.chunkflow.core.media.bench.SttBenchActivity "$@"; }

# 0. 端末の準備（SpeechRecognizer に音声を渡すため、録音の権限を与える）
adb -s $D shell pm grant com.miuraken.chunkflow android.permission.RECORD_AUDIO
adb -s $D shell mkdir -p $BENCH

# 1. モデルの状態を見て、足りないモデルを取得する
start --es mode status --es langs en,ja,zh,yue,ko,hi,es,ar,uk,vi --es tag status
start --es mode download --es langs en,ja,zh,yue,ko,hi,es,ar,uk,vi --ei downloadWaitSec 900 --es tag dl

# 2. 実教材のクリップを切り出し、端末の言語判定で言語を付けて、言語ごとに数をそろえる
python3 tools/stt-bench/prepare_real_clips.py            # → sample_files/stt-bench/real/{clips/,candidates.tsv}
adb -s $D push sample_files/stt-bench/real/clips $BENCH/
adb -s $D push sample_files/stt-bench/real/candidates.tsv $BENCH/
# adb が作ったディレクトリは所有者が shell で、アプリから開けない。アプリが読めるように権限を広げる
adb -s $D shell chmod 777 $BENCH/clips
start --es mode lid --es manifest candidates.tsv --es tag real
adb -s $D pull $BENCH/results-real-lid-<日時>.jsonl sample_files/stt-bench/real/
python3 tools/stt-bench/select_real_clips.py --lid sample_files/stt-bench/real/results-real-lid-*.jsonl
adb -s $D push sample_files/stt-bench/real/real.tsv $BENCH/

# 3. 合成音声のクリップ（正解つき）を作る
python3 tools/stt-bench/make_tts_clips.py                # → sample_files/stt-bench/tts/{clips/,tts.tsv,refs.tsv}
adb -s $D push sample_files/stt-bench/tts/clips/. $BENCH/clips/
adb -s $D push sample_files/stt-bench/tts/tts.tsv $BENCH/

# 4. 計測（画面を点けたまま。終わるまで端末を操作しない）
start --es mode run --es manifest real.tsv --es tag real
start --es mode run --es manifest tts.tsv --es tag tts
adb -s $D pull $BENCH/results-real-run-<日時>.jsonl sample_files/stt-bench/real/
adb -s $D pull $BENCH/results-tts-run-<日時>.jsonl sample_files/stt-bench/tts/

# 5. 集計（合成音声は正解がある。実教材は全エンジンの結果から正解の下書きを作り、確かめて直してから採点する）
python3 tools/stt-bench/score.py --results sample_files/stt-bench/tts/results-tts-run-*.jsonl \
    --refs sample_files/stt-bench/tts/refs.tsv --title 合成音声 --out sample_files/stt-bench/tts/score
python3 tools/stt-bench/consensus.py --results sample_files/stt-bench/real/results-real-run-*.jsonl \
    --manifest sample_files/stt-bench/real/real.tsv --out sample_files/stt-bench/real/consensus
#   → consensus/review.txt を見ながら refs_draft.tsv を直して real/refs.tsv にする（採点しない行は text を EXCLUDE に）
python3 tools/stt-bench/score.py --results sample_files/stt-bench/real/results-real-run-*.jsonl \
    --refs sample_files/stt-bench/real/refs.tsv --title 実教材 --out sample_files/stt-bench/real/score \
    --json sample_files/stt-bench/real/score/summary.json

# 6. 合成音声を声で分けて採点する（日本語・中国語・韓国語の 2 人目の Eddy はロボット的な声なので、主な結果から外して別に見る）
cd sample_files/stt-bench/tts
awk -F'\t' 'NR==FNR{if(FNR>1)v[$1]=$4; next} FNR==1 || v[$1] !~ /^Eddy/' tts.tsv refs.tsv > refs_natural.tsv
awk -F'\t' 'NR==FNR{if(FNR>1)v[$1]=$4; next} FNR==1 || v[$1] ~ /^Eddy/' tts.tsv refs.tsv > refs_eddy.tsv
cd -
python3 tools/stt-bench/score.py --results sample_files/stt-bench/tts/results-tts-run-*.jsonl \
    --refs sample_files/stt-bench/tts/refs_natural.tsv --title 合成音声（自然な声） \
    --out sample_files/stt-bench/tts/score_natural --json sample_files/stt-bench/tts/score_natural/summary.json

# 7. 実教材と合成音声（自然な声）の集計を、言語ごとの 1 つの表にまとめる
python3 tools/stt-bench/combine.py --real sample_files/stt-bench/real/score/summary.json \
    --tts sample_files/stt-bench/tts/score_natural/summary.json --out sample_files/stt-bench/combined.md
```

- `score.py` は、同じエンジン・クリップの結果が複数あると後のファイルを使います。一部のエンジンだけ測り直したときは、
  元の結果のあとに測り直した結果のファイルを並べます。

## 引数（SttBenchActivity）

| 引数 | 意味 |
| --- | --- |
| `--es mode` | `status`（モデルの状態）・`download`（取得）・`lid`（言語判定）・`run`（計測） |
| `--es manifest` | クリップ一覧（TSV。列 `id`・`lang`・`file` は必須、`pair` は `lid` で使う） |
| `--es langs` | 対象の言語（`status`・`download` は必須、`run` は絞り込み） |
| `--es engines` | `mlkit,sr,wtiny,wbase,wsmall,sensevoice,mtiny,mbase` から選ぶ（既定はすべて。この順に測る） |
| `--ei maxClips` | 言語ごとのクリップ数の上限（0 ですべて） |
| `--ez warmup` | エンジン × 言語ごとに 1 回ウォームアップする（既定 true。モデルの読み込みを計測から外す） |
| `--ei cooldownSec` / `--ei thermalWaitSec` | 各回の前の待ち時間・温度が下がるまで待つ上限 |
| `--es tag` | 結果のファイル名に付ける名前 |

## 計測のしかた

- 本番の「より詳しく文字起こし」と同じ経路（`transcribeChunkTrial`）で、学習言語だけを指定し、エンジンを 1 つに固定して（ほかのエンジンへ切り替えずに）1 クリップずつ文字起こしします。
  時間は呼び出しの前後で測るので、PCM の切り出し（WAV なので数 ms）と、`SpeechRecognizer`・ML Kit に音声を流す時間（本番と同じ速さ）を含みます。
- エンジン × 言語ごとに、クールダウン → 先頭のクリップで 1 回ウォームアップ（モデルの読み込みを含む「初回」の時間として記録）→ 全クリップを計測、の順に進めます。
- 精度は `score.py` で、日本語・中国語・広東語・韓国語は CER、ほかは WER を求めます（正規化は `score.py` の説明のとおり）。
