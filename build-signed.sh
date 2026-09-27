#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 里运行：从本地签名目录读密码，经 android-build 容器构建签名 release 包。
# 密码只通过 proot-distro --env 传入，不落在仓库里。
set -euo pipefail

PASS_FILE="${CODEX_RELEASE_PASS_FILE:-$HOME/.local/share/codex-refresh-signing/release.pass}"
[[ -r "$PASS_FILE" ]] || { echo "找不到签名密码文件：$PASS_FILE" >&2; exit 1; }
[[ -r "$HOME/projects/codex-refresh/signing.keystore" ]] || { echo "找不到 signing.keystore" >&2; exit 1; }
PASS="$(<"$PASS_FILE")"

proot-distro login android-build --bind "$HOME/projects:/workspace" --shared-tmp \
    --env CODEX_RELEASE_STORE_FILE=/workspace/codex-refresh/signing.keystore \
    --env CODEX_RELEASE_STORE_PASSWORD="$PASS" \
    --env CODEX_RELEASE_KEY_PASSWORD="$PASS" \
    --env CODEX_RELEASE_KEY_ALIAS=codex-refresh-release \
    -- /bin/bash -lc 'source /etc/profile.d/android-sdk.sh; cd /workspace/codex-refresh; ./gradlew testReleaseUnitTest assembleRelease "$@"' _ "$@"

APK="$HOME/projects/codex-refresh/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "没有产出签名 APK（可能只生成了 unsigned 包）" >&2; exit 1; }
echo "$APK"
