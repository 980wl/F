# F 0.5.6

Android 8.0+；ARM32/ARM64；包名 com.fproxy.client；内核 libbox 1.14.4。使用原发布签名，可覆盖安装保留节点和订阅。

0.5.6 修复：节点配置页保存后主页仍显示旧值的问题——主页的 NodeStore 是启动时读入内存的旧数据，配置页是另一个实例写盘，刷新列表时不重读磁盘；现刷新前先重读，之后点卡片也不会把旧数据写回盘里丢掉修改；“配置”按钮移到每行“测速”与“删除”之间，卡片更紧凑。另：构建的三处下载都绕开 GitHub 的 504——plan 读清单与 libbox.aar 改走 api.github.com 资产下载（带重试），Gradle 发行版改走腾讯镜像（SHA-256 校验不变，已核对与官方一致）。

0.5.5 新增节点配置页：每个节点卡片测速按钮上方加“配置”按钮，点进可改备注、服务器、端口、密码/UUID、TLS SNI；hysteria2 节点另有混淆（salamander/gecko）、BBR 档位、上/下行带宽与端口跳跃（server_ports + hop_interval）；页底“保存”写回并返回主页、“返回”丢弃修改。返回主页后节点列表自动刷新。

0.5.4 修复：主界面版本号改为读 BuildConfig.VERSION_NAME，不再写死（0.5.3 误显示 0.5.2）；测速代理鉴权改走 java.net.Authenticator（按端口分发各并发任务的口令），修复 0.5.3 测速报“Failed to authenticate with proxy”。

0.5.3 安全与性能修复：bootstrap DNS 由明文 UDP 改为加密 DoH，接入 geosite-cn.srs 域名级分流（国内域名走阿里 DoH 直连、境外走代理 DoH）；测速本地代理端口增加一次性随机鉴权；tailscale 主机名不再返回真实设备型号；正式版关闭 Log.d 调试日志并对错误日志脱敏；移除厂商包名前缀一刀切直连，改为只认精确包名清单；开启 R8 代码压缩、ABI 分包与 useLegacyPackaging=false；测速改为 4 并发；状态轮询与列表渲染移出主线程并加防抖。

0.5.2 修复应用内更新在部分机型（小米/HyperOS）上报“无法读取安装包签名”的问题：读不到 v2/v3 签名信息时改用 v1 签名比对证书。从 0.5.0/0.5.1 更新请先覆盖安装 0.5.2 APK，之后应用内更新即可正常工作。

0.5.1 修复 VLESS REALITY 分享链接导入缺少 uTLS 的问题。新链接保留 `fp`，已有 REALITY 节点在连接和测速时自动补齐 uTLS，缺省使用 `chrome`。从 0.5.0 更新请先覆盖安装 0.5.1 APK。0.5.1 起可通过更新清单安装应用版本更高且内核相同的更新。

默认已改为：中国大陆 IP 直连、其他 IP 走代理。私有/局域网地址也直连。

国内应用使用整应用直连：内置应用/游戏包名清单合并去重 7,243 条；0.5.3 起不再按厂商包名前缀一刀切，只认精确包名清单。浏览器作为例外保持 IP 分流。微信、SystemHelper 和银行应用额外按包名/名称识别。详见 APP_LIST_SOURCES.md。

页面下方的“应用直连”默认收起。点击“再显示40个应用”可按批展开，按名称或包名搜索，并为漏识别应用勾选补充直连。浏览器和自动保护项会显示不可随意切换的状态。修改设置会断开正在使用的 VPN，重连后按新列表建立连接；新安装应用或更新清单后也请重连。

“更新全部”同时更新订阅、IP/Geo 资源、国内应用包名清单并检查稳定内核版本。资源已内置，首次连接不依赖在线更新；更新失败保留原规则。

VPN 中的业务 DNS：境外域名使用经代理发送的 Cloudflare DoH（1.1.1.1:443、/dns-query、TLS server_name=cloudflare-dns.com）；国内域名走阿里 DoH 直连；节点域名启动解析走加密 bootstrap DoH 完成。被整应用排除的应用不使用 VPN 的 DNS。

节点逐个/全部删除、真实 HTTPS 测速、备份恢复功能保留。测速是经被测节点进行 HTTPS 204 请求，不是 TCP ping；新分流策略不作用于测速临时代理，避免误报直连速度。

VPN 优先使用 Android 已验证可上网的底层网络。HY2 分享链接和 Clash 订阅支持导入 Gecko 混淆与显式 BBR 档位。启用 Gecko 时，节点服务端需使用相同混淆类型和密码。

## 构建

JDK 17、Android SDK 35、Build Tools 35.0.0：

```bash
./gradlew assembleDebug testDebugUnitTest lintRelease
```

Windows 使用 gradlew.bat。首次构建会下载固定版本 libbox AAR 并验证 SHA-256；数据资产已包含在源码包。要生成可覆盖发布版的 APK，继续使用已交付的签名备份，把 f-release.jks 和 signing.properties 放在根目录，属性中增加 storeFile=f-release.jks，然后运行 ./gradlew assembleRelease。不要公开签名私钥。

## 验证与更新边界

验证结果见 CHECK_REPORT.md。公开应用清单可能有漏项或误分类，请在本机核对；整个识别过程和本机安装列表均在本地处理。

内核更新依旧通过应用内下载、校验和系统确认安装同签名新版 APK 生效；页面底部需配置自己的 HTTPS 更新清单地址。本次未部署更新站点。

Android 测试源码包含原生启动/关闭及路由验证。分应用路由测试需要安装专用隔离测试包，验证的是 UID 网络归属，不包含实际银行/微信账户登录。

## GitHub 自动发布稳定内核

1. 建一个用于 F 发布的公开 GitHub 仓库，把本压缩包内 `F-SingBox-Android` 文件夹的**内容**推送到默认分支。GitHub 仓库根目录应能看到 `.github/workflows/core-release.yml`。这个仓库的 Latest Release 由 F 工作流管理。
2. 打开仓库的 **Settings → Secrets and variables → Actions**。用此前交付的签名备份添加四项 Repository secrets。

   | Secret | 值 |
   |---|---|
   | `F_KEYSTORE_BASE64` | 原 `f-release.jks` 的 Base64 字符串 |
   | `F_STORE_PASSWORD` | `signing.properties` 的 `storePassword` |
   | `F_KEY_ALIAS` | `signing.properties` 的 `keyAlias` |
   | `F_KEY_PASSWORD` | `signing.properties` 的 `keyPassword` |

   Linux、macOS 可用 `openssl base64 -A -in f-release.jks`，Windows PowerShell 可用 `[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'f-release.jks').Path))`。签名文件、密码和 Base64 字符串只通过 Actions Secrets 录入。工作流按 0.5.0 的证书指纹校验签名。能改默认分支工作流的人也能改签名步骤，因此请限制仓库写权限。
3. 打开 **Actions → F stable core release → Run workflow**，选择默认分支执行。首次发布成功后，进入 **Releases** 可看到 `F-SingBox.apk` 和 `f-updates.json`。
4. 把下方地址中的 `OWNER/REPO` 换成实际 GitHub 仓库名，填到已安装 F 的「内核更新地址」，点击「保存更新地址」。之后使用「更新全部」。工作流生成的 APK 已内置此地址。更新安装由 Android 系统确认。

   ```text
   https://github.com/OWNER/REPO/releases/latest/download/f-updates.json
   ```

工作流每天检查两次 SagerNet 的最新稳定版，同时检测仓库里的应用版本是否升版。若发现新内核，等待现有 AAR 来源 `singbox-android/libbox` 发布同版本 `libbox.aar`，校验 GitHub 发布资产的 SHA-256，然后重新编译 F。当前基线继续使用已固定的 AAR 校验值。通过单元测试、Lint、签名和 APK 元数据校验后，先上传草稿的两个文件，再发布为 Latest。发现新内核的 Android 绑定不兼容时，构建失败会保留此前发布的版本。

首次运行可以用当前稳定内核生成初始发布和更新地址。更新清单同时支持同内核的应用升版和稳定内核升级。公开仓库连续 60 天无活动时，GitHub 可能停用定时任务，可在 Actions 页面重新启用。
