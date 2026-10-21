#!/bin/sh
# 端末内の音声認識ベンチマークを、エンジンごとにアプリのプロセスを立ち上げ直しながら順に実行する（Chunk Flow / tools/stt-bench）。
#
# - エンジンごとに force-stop してから SttBenchActivity を起動する（前のエンジンのモデルがメモリに残らないようにし、
#   エンジンの読み込みの時間とメモリを毎回同じ条件で測るため。1 つのエンジンが止まってもほかのエンジンは続ける）。
# - ログが STALL_SEC 秒のあいだ増えなければ、そのエンジンは止まったとみなして次へ進む。
# - 計測の条件をそろえるため、計測のあいだ画面を点けたままにする（画面が消えると CPU の動き方が変わりうる。
#   USB 接続中は画面を消さない設定 `svc power stayon usb` を使い、終わったら元に戻す）。
#
# 使い方: tools/stt-bench/run_all.sh <シリアル> <クリップ一覧.tsv> <タグ> <エンジン...>
#   例: tools/stt-bench/run_all.sh 3B271FDJG005PQ real.tsv real mtiny mbase sensevoice wtiny wbase mlkit sr wsmall
set -u

D="$1"
MANIFEST="$2"
TAG="$3"
shift 3
B=/sdcard/Android/data/com.miuraken.chunkflow/files/stt-bench
ACTIVITY=com.miuraken.chunkflow/com.miuraken.chunkflow.core.media.bench.SttBenchActivity
STALL_SEC=${STALL_SEC:-300}
COOLDOWN_SEC=${COOLDOWN_SEC:-10}

adb -s "$D" shell svc power stayon usb
trap 'adb -s "$D" shell svc power stayon false' EXIT

for engine in "$@"; do
    run_tag="${TAG}_${engine}"
    echo "$(date '+%H:%M:%S') START ${run_tag}"
    adb -s "$D" shell am force-stop com.miuraken.chunkflow
    sleep 2
    adb -s "$D" shell input keyevent KEYCODE_WAKEUP
    adb -s "$D" shell am start -n "$ACTIVITY" --es mode run --es manifest "$MANIFEST" --es engines "$engine" \
        --ei cooldownSec "$COOLDOWN_SEC" --ei thermalWaitSec 300 --es tag "$run_tag" > /dev/null
    sleep 5
    log=$(adb -s "$D" shell "ls -t $B/${run_tag}-run-*.log 2>/dev/null | head -1" | tr -d '\r')
    last_size=-1
    still=0
    while true; do
        sleep 10
        if [ -z "$log" ]; then
            log=$(adb -s "$D" shell "ls -t $B/${run_tag}-run-*.log 2>/dev/null | head -1" | tr -d '\r')
            continue
        fi
        if adb -s "$D" shell "grep -q '^END' $log"; then
            echo "$(date '+%H:%M:%S') END ${run_tag} $(adb -s "$D" shell "grep -c '^RESULT ' $log" | tr -d '\r') results"
            break
        fi
        size=$(adb -s "$D" shell "wc -c < $log" | tr -d '\r ')
        if [ "$size" = "$last_size" ]; then
            still=$((still + 10))
            if [ "$still" -ge "$STALL_SEC" ]; then
                echo "$(date '+%H:%M:%S') STALL ${run_tag}（${STALL_SEC} 秒ログが増えないので次へ進む）"
                break
            fi
        else
            still=0
            last_size="$size"
        fi
    done
done
adb -s "$D" shell am force-stop com.miuraken.chunkflow
echo "$(date '+%H:%M:%S') ALL_DONE ${TAG}"
