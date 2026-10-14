#!/bin/zsh
# S2: ビルド → インストール → アプリにフォルダを作らせる → テスト音源を push する（エミュレータ / 実機共通）。
# 使い方: spikes/playback/testdata/install_and_push.sh   （事前に generate_testdata.py を実行しておく）
set -eu
ROOT=${0:A:h:h:h:h}
PKG=com.miuraken.chunkflow.spike.playback
D=/sdcard/Android/data/$PKG/files/audio
cd "$ROOT"
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk} \
JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"} \
  gtimeout 600 ./gradlew :spikes:playback:assembleDebug --console=plain -q < /dev/null
adb install -r spikes/playback/build/outputs/apk/debug/playback-debug.apk < /dev/null
# adb shell mkdir で作ると所有者が shell になりアプリから読めないため、アプリを起動してフォルダを作らせる
adb shell am start -W -n $PKG/.SpikeActivity < /dev/null > /dev/null
sleep 2
cd spikes/playback/testdata/out
adb push manifest.json cbr.mp3 cbr_noheader.mp3 vbr_xing.mp3 vbr_xing_notoc.mp3 vbr_noheader.mp3 \
  aac.m4a vbr_in_mp4.mp4 loop.m4a $D/ < /dev/null
