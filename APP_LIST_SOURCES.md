# 中国大陆应用和 IP 规则来源

应用包名来源：mnixry/direct-android-ruleset（多个应用市场榜单汇总，包含应用/游戏，并有上游人工增删配置）。

- 项目：https://github.com/mnixry/direct-android-ruleset
- 应用：https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/APP.mutated.yaml
- 游戏：https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/GAME.mutated.yaml
- 获取日期：2026-11-06
- 本次快照：应用 3,100 条、游戏 4,158 条，合并去重 7,243 个包名。
- 上游许可：AGPL-3.0，完整许可文本见 APP_LIST_LICENSE.txt。转换后的纯包名快照位于 app/src/main/assets/cn-apps.txt 和 cn-games.txt。

按包名精确匹配，同时补充上游使用的国产厂商包名前缀。浏览器识别优先于普通国内清单，综合常见浏览器包名、名称以及 Android 对通用 HTTP/HTTPS 网页的处理能力。微信、SystemHelper、银行另作保护识别；银行等应用内嵌 WebView 不等同于独立浏览器。

此清单并非官方完整名录，榜单中可能有漏项或分类不准确。应用内可查看实际识别结果、搜索包名，并补充手动直连；新安装应用或更新清单后重连生效。普通中文界面不作为应用来自中国大陆的判断依据。

大陆 IP 数据：SagerNet/sing-geoip 的 geoip-cn.srs：
https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs

内置二进制包含 IPv4/IPv6，使用 CN 国家/地区规则，不把香港、澳门、台湾自动合并进 CN。首次连接不要求联网下载。应用的“更新全部”可更新 IP 资源和应用清单；下载失败保留已有/内置版本。

浏览器保持进入 VPN，由目标 IP 决定大陆直连或境外代理。已排除的应用通过 Android VpnService.Builder.addDisallowedApplication 整个 UID 绕过 VPN，包括其 DNS 和境外服务器连接；不是仅对几个域名添加直连规则。共享 UID 等系统限制会在识别结果中处理，冲突的强制直连应用不会被静默送入代理。
