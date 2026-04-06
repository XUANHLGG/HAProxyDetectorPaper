# HAProxyDetector

[English](README.md)

一个轻量级插件，用于在 Paper/Folia 和 Velocity 上同时兼容原生 HAProxy / Proxy Protocol 与普通玩家直连。

## 核心功能

- **混合连接支持**：同时接收真实的 HAProxy/frp 代理流量与普通玩家直连流量。
- **兼容原生解析器**：对直连流量补一个伪造的 HAProxy V2 头，继续走服务端原生 proxy-protocol 解析流程。
- **白名单支持域名 / IP / CIDR**：`whitelist` 支持填写单个 IP、CIDR 网段和域名。
- **最小化拦截日志**：在拦截非白名单代理连接时输出日志，日志中包含 frps 来源 IP 与客户端 IP。

## 工作原理

1. **真实 HAProxy 流量**：检测到合法的 HAProxy v1/v2 头后，直接交给服务端原生解析器处理。
2. **普通直连玩家**：如果没有 HAProxy 头，插件会基于玩家真实套接字地址补一个伪造的 HAProxy V2 头。
3. **伪造头保护**：插件会对自己生成的伪造头做内部标记，避免后续白名单校验把它误判成外部 frps 流量。
4. **白名单拦截**：开启 `enable-whitelist` 后，只会校验真实外部代理来源。

## 支持平台

- **Paper / Folia**：理论支持带原生 HAProxy 功能的版本。
- **Velocity**：理论支持 `3.0.0+`。

## 配置文件

Paper 和 Velocity 使用相同结构的 `config.yml`：

```
enable-whitelist: true
whitelist:
  - 127.0.0.1
  - "::1"
  - frps.example.com
  - 203.0.113.0/24
```

### 白名单规则

- **IP**：精确匹配。
- **CIDR**：按网段匹配，例如 `203.0.113.0/24`。
- **域名**：在插件加载配置时自动解析成 IP 列表参与匹配。

### 域名白名单说明

- 域名是在插件加载或重载配置时解析，不是每次连接实时解析。
- 如果域名解析结果后续发生变化，需要重启或重载插件才能刷新到新的 IP。
- 如果某个域名暂时解析失败，插件不会因此启动失败，而是保留原始配置项。

## 日志行为

仅在拦截到非白名单 HAProxy 连接时输出日志。

日志字段说明：
- `frps`：上游代理服务器的真实来源 IP。
- `client`：HAProxy 头中携带的客户端源 IP。

示例：

```
[HAProxyDetectorPaper] 拦截非白名单 frps 连接: frps=91.78.69.13, client=91.78.69.13
```

## 使用方法

1. **开启服务端原生 HAProxy 支持**：
   - **Paper / Folia**：在 `config/paper-global.yml`（新版本）或 `paper.yml`（旧版本）中将 `proxy-protocol` 设为 `true`。
   - **Velocity**：在 `velocity.toml` 中设置 `haproxy = true`。
2. **安装插件**：将插件 JAR 放入对应平台的插件目录。
3. **按需配置白名单**：如果只允许特定 frps 来源，编辑 `config.yml` 中的 `whitelist`。
4. **重启服务端 / 代理**。

## 停用 / 卸载行为

插件会在停用或关闭阶段主动移除自己注入的 Netty handler，因此卸载后不会继续对新连接生效。
不过对于生产环境中的底层网络插件，完整重启仍然是最稳妥的做法。

## 技术实现

- **Netty ChannelPipeline 注入**：在网络处理链最前端拦截原始 `ByteBuf`。
- **特征探测**：通过反射定位服务端连接结构，不依赖某一个固定字段名。
- **HAProxy V2 头伪造**：对直连连接生成标准 HAProxy V2 头。
- **Synthetic 标记**：避免插件自己伪造的头再次被当成外部代理流量校验。
