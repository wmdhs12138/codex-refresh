# 发布签名

从 v0.3.5 起，正式 APK/AAB 使用固定 release key 签名。密钥本体与口令不得提交到 Git；仓库的 `.gitignore` 已排除常见 Android 签名文件。

## 证书

- Alias：`codex-refresh-release`
- 算法：RSA-4096 / SHA256withRSA
- 有效期：2026-09-13 至 2126-08-20
- SHA-256：`90:C5:1B:E8:D4:4B:02:9E:44:78:97:75:43:9B:BB:5D:20:45:47:1F:B7:7E:20:86:8E:83:EF:7F:84:E7:B5:C3`

## 本地构建

构建签名版本前设置以下环境变量：

```text
CODEX_RELEASE_STORE_FILE=/absolute/path/to/codex-refresh-release.keystore
CODEX_RELEASE_STORE_PASSWORD=...
CODEX_RELEASE_KEY_PASSWORD=...
CODEX_RELEASE_KEY_ALIAS=codex-refresh-release
```

然后执行：

```sh
./gradlew --no-daemon assembleRelease bundleRelease
```

未设置任何签名变量时，日常 debug 构建不受影响；只设置部分变量会让配置立即失败，避免意外生成错误的发布包。

## GitHub Actions Secrets

CI 使用以下仓库 Secrets：

- `CODEX_RELEASE_KEYSTORE_BASE64`
- `CODEX_RELEASE_STORE_PASSWORD`
- `CODEX_RELEASE_KEY_PASSWORD`

只有推送到 `main` 时才恢复密钥并构建签名产物，Pull Request 不接触签名材料。

> 必须安全备份 keystore 与口令。丢失密钥后，Android 不允许新版本覆盖安装此前签名的应用。
