#!/usr/bin/env bash
set -euo pipefail

apk=${1:?APK path}
core=${2:?core version}
code=${3:?version code}
bt=${ANDROID_BUILD_TOOLS:?Android build tools path}
expected_cert=48ad881206c917145e41963a1b67fd4b536668adc61c4ab43edf67369ba1c65c

signing=$("$bt/apksigner" verify --verbose --print-certs "$apk")
grep -Fxq "Number of signers: 1" <<< "$signing"
grep -Fxq "Signer #1 certificate SHA-256 digest: $expected_cert" <<< "$signing"
"$bt/zipalign" -c -P 16 4 "$apk"
badging=$("$bt/aapt2" dump badging "$apk")
grep -Fq "package: name='com.fproxy.client' versionCode='$code'" <<< "$badging"
grep -Fxq "native-code: 'arm64-v8a' 'armeabi-v7a'" <<< "$badging"
manifest=$("$bt/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)
grep -A1 '="com.fproxy.CORE_VERSION"' <<< "$manifest" |
    grep -Fq "=\"$core\""
version_name=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<< "$badging")
printf '%s\n' "${version_name:?APK versionName missing}" > "$(dirname "$apk")/version-name.txt"
