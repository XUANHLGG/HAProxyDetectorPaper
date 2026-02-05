# HAProxyDetector

[简体中文](README_CN.md)

A lightweight plugin designed to solve the compatibility issue where direct connections are impossible after enabling HAProxy (Proxy Protocol) support on Paper or Velocity.

### Core Features

* **Mixed Connection Support**: Allows servers with `proxy-protocol: true` to simultaneously receive proxy traffic from HAProxy/frp and direct traffic from players.
* **Native Parser Compatibility**: Instead of taking over complex protocol parsing, the plugin injects a fake HAProxy V2 header into direct traffic. This "tricks" the server's native parser, allowing all traffic to follow the official, most stable parsing path.
* **Zero Configuration / Zero Dependencies**: No ProtocolLib required, no whitelist configuration needed. Plug and play.
* **Cross-Version Compatibility**: Based on Netty-level injection and feature detection, theoretically supports all Minecraft versions with strong cross-version stability.

### Why Choose This Plugin?

Traditional HAProxy plugins are often "patch-style," meaning they force support onto servers that don't natively have it. This causes conflicts in modern Paper versions, which already have a more secure built-in implementation.

This plugin adopts an "auxiliary" approach:
1. **For frp/Proxy Players**: If an existing protocol header is detected, it is passed through directly without interfering with the original IP chain.
2. **For Direct Players**: If a missing protocol header is detected, a fake header containing the player's real IP is immediately prepended. This allows the server's native parser to extract and apply the player's real address directly.

### Supported Platforms

* **Paper/Purpur**: Theoretically supports all versions with native HAProxy functionality (Tested on 1.20.1 - 1.21.4).
* **Velocity**: Theoretically supports all 3.0.0+ versions.

### Usage

1. **Enable Native HAProxy Support**:
   * **Paper/Purpur**: Set `proxy-protocol` to `true` in `config/paper-global.yml` (Newer versions) or `paper.yml` (Older versions).
   * **Velocity**: Set `haproxy = true` in `velocity.toml`.
2. **Install Plugin**: Place the plugin JAR in the `plugins` folder.
3. **Restart Server**.

### Technical Implementation

* **Netty ChannelPipeline Injection**: Intercepts raw `ByteBuf` at the very front of the network processing pipeline.
* **Feature Detection Logic**: Automatically identifies the `ServerConnection` field in NMS without relying on specific field name obfuscation, providing high robustness across versions.
* **Protocol Header Forging**: Implements standard HAProxy V2 protocol format injection.
