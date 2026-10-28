#!/usr/bin/env bash
# Source this from the repository root: `source env.sh`. Touches only the current shell.
_P="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export MC4_PROBE="$_P"
export MC4_REPO="$_P/port"
export MC4_GAMEDIR="$_P/mc4data"

[ -d "$_P/jdk" ] && export JAVA_HOME="${JAVA_HOME:-$_P/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$_P/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [ -d "$_P/sdl-arm/lib/pkgconfig" ]; then
    export PKG_CONFIG_PATH="$_P/sdl-arm/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
fi

export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_HOME:+$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/cmdline-tools/latest/bin:}$PATH"
unset _P
