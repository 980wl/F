# Native dependency provenance

This project links sing-box/libbox (GPL-3.0-or-later); see LICENSE and upstream source:
https://github.com/SagerNet/sing-box/tree/v1.14.4

The prebuilt Android AAR used for this build comes from a third-party publisher:
https://github.com/singbox-android/libbox/releases/download/1.14.4/libbox.aar
SHA-256: d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465
Its Go build tags include with_quic. It is not byte-identical to the native library
in the official SFA APK. This work verifies integration and protocol behavior,
not an audit of every upstream native dependency. The source ZIP omits the large
AAR; Gradle downloads that exact pinned asset and checks its SHA-256 before use.

SnakeYAML and AndroidX dependencies retain their respective upstream licenses.

0.4.0 adds app package snapshots derived from mnixry/direct-android-ruleset.
See APP_LIST_SOURCES.md and the unmodified APP_LIST_LICENSE.txt (AGPL-3.0).
The program keeps browser exceptions and bank/SystemHelper protections locally.
Bundled geoip-cn.srs is from SagerNet/sing-geoip's rule-set branch.
