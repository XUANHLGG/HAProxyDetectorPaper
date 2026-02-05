# HAProxyDetector

一个轻量级的插件，旨在解决 Paper 或 Velocity 开启 HAProxy (Proxy Protocol) 支持后，无法兼容直连玩家的问题。

### 核心功能

* **混合连接支持**：允许开启了 `proxy-protocol: true` 的服务端同时接收来自 HAProxy/frp 的代理流量和玩家的直连流量。
* **原生解析兼容**：插件不接管复杂的协议解析逻辑，而是通过向直连流量注入伪造的 HAProxy V2 头部，欺骗服务端原生解析器，从而让所有流量统一走官方最稳定的解析路径。
* **零配置/零依赖**：不需要 ProtocolLib，不需要配置白名单，放入即用。
* **全版本适配**：基于 Netty 底层注入和特征探测逻辑，理论支持所有 Minecraft 版本，且具备极强的跨版本兼容性。

### 为什么选择本插件？

传统的 HAProxy 插件通常是“补丁式”的，即在服务端不支持的情况下强行添加支持。这在现代 Paper 版本中会产生冲突，因为 Paper 已经内置了更安全的解析实现。

本插件采用“辅助式”思路：
1. **针对 frp/代理玩家**：检测到已有协议头，直接放行，不干扰原有 IP 链。
2. **针对直连玩家**：检测到缺失协议头，立即补全一个包含玩家真实 IP 的伪造头，让服务端原生解析器能够直接提取并应用玩家的真实地址。

### 支持平台

* **Paper/Purpur**：理论支持所有具备原生 HAProxy 功能的版本（已在 1.20.1 - 1.21.4 实测通过）
* **Velocity**：理论支持 3.0.0+ 所有版本

### 使用方法

1. **开启服务端原生 HAProxy 支持**：
   * **Paper/Purpur**：在 `config/paper-global.yml` (新版) 或 `paper.yml` (旧版) 中设置 `proxy-protocol` 为 `true`。
   * **Velocity**：在 `velocity.toml` 中设置 `haproxy = true`。
2. **安装插件**：将本插件放入 `plugins` 文件夹。
3. **重启服务器**。

### 技术实现

* **Netty ChannelPipeline 注入**：在网络处理流的最前端拦截原始 ByteBuf。
* **特征探测逻辑**：自动识别 NMS 中的 `ServerConnection` 字段，不依赖具体的字段名混淆，具有极高的版本鲁棒性。
* **协议头伪造**：实现标准的 HAProxy V2 协议格式注入。
