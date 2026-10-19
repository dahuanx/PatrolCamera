#!/usr/bin/env bash
# 打包正式版：安全同步源码 → 编译 → 校验包内实际内容 → 拷回 APK/ 与 dist/
#
# 用法：bash tools/build_release.sh 31 1.3.10
#
# 为什么要有这个脚本（v1.3.8 / v1.3.9 的教训）：
#   原来手写的同步命令是 `rm -rf "$DST/app/src"; cp -rf "$SRC/app/src" "$DST/app/src"`。
#   一旦 rm 没生效（目录被占用、路径写错一级），cp 会把 src **塞进已存在的 src 里**，
#   变成 app/src/src，而 app/src/main 仍是上一版的老代码 —— 编译能成功、versionCode 也会变，
#   但包里装的还是老界面。v1.3.8、v1.3.9 就是这么连废两版的。
#
#   本脚本的三条硬约束：
#     ① 先删目标目录，删完必须确认不存在，再用 `cp -r src/. dst/`（点号结尾，内容进目录而不是套目录）；
#     ② 同步后强制 diff，源码与构建目录必须完全一致，否则直接退出；
#     ③ 编译后不只看 versionCode，还要用 aapt 核对包内字符串资源，确认改动真的进了包。
set -euo pipefail

VC="${1:?用法: bash tools/build_release.sh <versionCode> <versionName>}"
VN="${2:?用法: bash tools/build_release.sh <versionCode> <versionName>}"

export PATH="/c/Users/Administrator/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:/usr/bin:/bin:$PATH"
export JAVA_HOME="/c/Users/Administrator/.workbuddy/binaries/jdk-17.0.2"
export ANDROID_HOME="/c/Users/Administrator/.workbuddy/binaries/android-sdk"
export MSYS2_ARG_CONV_EXCL="*"

ROOT="/c/Users/Administrator/WorkBuddy/边合区巡查相机"
SRC="$ROOT/PatrolCamera"
BP="/c/Users/Administrator/.workbuddy/build-projects/PatrolCamera"
AAPT="$ANDROID_HOME/build-tools/34.0.0/aapt.exe"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner.bat"

step() { printf '\n=== %s ===\n' "$1"; }

step "0. 核对源码里的版本号"
grep -q "versionCode = $VC" "$SRC/app/build.gradle.kts" || { echo "源码 versionCode 不是 $VC"; exit 1; }
grep -q "versionName = \"$VN\"" "$SRC/app/build.gradle.kts" || { echo "源码 versionName 不是 $VN"; exit 1; }
echo "versionCode=$VC versionName=$VN ✓"

step "1. 同步源码（先删干净再拷内容）"
rm -rf "$BP/app/src"
if [ -e "$BP/app/src" ]; then echo "删除 $BP/app/src 失败，中止"; exit 1; fi
mkdir -p "$BP/app/src"
cp -r "$SRC/app/src/." "$BP/app/src/"
cp -f "$SRC/app/build.gradle.kts" "$BP/app/build.gradle.kts"

step "2. 校验同步结果"
if [ -d "$BP/app/src/src" ]; then echo "出现嵌套 src/，同步失败，中止"; exit 1; fi
if ! diff -rq "$SRC/app/src" "$BP/app/src"; then echo "构建目录与源码不一致，中止"; exit 1; fi
echo "源码与构建目录完全一致 ✓"

step "3. 编译 release"
cd "$BP"
LOG="build_v${VC}.log"
> "$LOG"
../../binaries/gradle-8.5/bin/gradle clean assembleRelease --console=plain >> "$LOG" 2>&1 || { tail -30 "$LOG"; exit 1; }
WARNS=$(grep -c -i warning "$LOG" || true)
echo "warning 行数: ${WARNS:-0}"

step "4. 校验包内实际内容"
APK_OUT="$BP/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_OUT" ] || { echo "没找到编译产物"; exit 1; }
cp "$APK_OUT" "$BP/check-v$VC.apk"
BADGING=$("$AAPT" dump badging "C:\\Users\\Administrator\\.workbuddy\\build-projects\\PatrolCamera\\check-v$VC.apk" 2>/dev/null | head -1)
echo "$BADGING"
echo "$BADGING" | grep -q "versionCode='$VC'" || { echo "包内 versionCode 不符"; exit 1; }
echo "$BADGING" | grep -q "versionName='$VN'" || { echo "包内 versionName 不符"; exit 1; }

# 反例检查：包内不能残留写死的旧版本号（version_info 必须是 %1$s 占位）
if "$AAPT" dump --values resources "C:\\Users\\Administrator\\.workbuddy\\build-projects\\PatrolCamera\\check-v$VC.apk" 2>/dev/null \
    | grep -a -q '巡查水印相机 v1\.[0-9]'; then
  echo "警告：包内仍有写死版本号的文案，请确认 version_info 是否用 %1\$s 占位"
fi

"$APKSIGNER" verify --print-certs "C:\\Users\\Administrator\\.workbuddy\\build-projects\\PatrolCamera\\check-v$VC.apk" 2>&1 | sed -n '1,2p'

step "5. 拷回工作区"
cp "$BP/check-v$VC.apk" "$ROOT/APK/巡查水印相机-v$VN-正式版.apk"
cp "$BP/check-v$VC.apk" "$ROOT/dist/PatrolCamera-v$VN.apk"
echo "size   = $(stat -c %s "$BP/check-v$VC.apk")"
echo "sha256 = $(sha256sum "$BP/check-v$VC.apk" | cut -d' ' -f1)"

step "完成"
echo "下一步：python tools/gen_version_json.py $VC $VN \"APK/巡查水印相机-v$VN-正式版.apk\" notes.txt"
echo "发布顺序：先建 Release 传附件（名字必须是 PatrolCamera-v$VN.apk），再推 version.json"
