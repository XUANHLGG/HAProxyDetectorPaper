# HAProxyDetector

[English](README.md)

一个轻量级插件，用于在 Paper/Folia 和 Velocity 上同时兼容原生 HAProxy / Proxy Protocol 与普通玩家直连。

## 核心功能

- **混合连接支持**：同时接收真实的 HAProxy/frp 代理流量与普通玩家直连流量。
- **兼容原生解析器**：按连接探测协议，直连流量直接绕过原生 HAProxy 解码器。
- **白名单支持域名 / IP / CIDR**：`whitelist` 支持填写单个 IP、CIDR 网段和域名。

## 支持平台

- **Paper / Folia**：理论支持带原生 HAProxy 功能的版本。
- **Velocity**：面向 `3.3.0+`，兼容 Velocity 4.x 的当前连接初始化结构。

## 配置文件

Paper 和 Velocity 使用相同结构的 `config.yml`：

```
language: # 检测系统/JVM 语言后写回；也可明确设为 en_US 或 zh_CN
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
- 如果某个域名暂时解析失败，插件不会因此启动失败。

## 日志行为

拦截日志字段说明：
- `frps`：上游代理服务器的真实来源 IP。
- `client`：HAProxy 头中携带的客户端源 IP。

示例：

```
[HAProxyDetectorPaper] 拦截非白名单 frps 连接: frps=91.78.69.13, client=91.78.69.13
```

## 使用方法

1. **配置平台**：
   - **Paper / Folia**：在 `config/paper-global.yml`（新版本）或 `paper.yml`（旧版本）中将 `proxy-protocol` 设为 `true`。
   - **Velocity**：在 `velocity.toml` 的 `[advanced]` 中将 `haproxy-protocol` 设为 `true`，插件复用 Velocity 原生 HAProxy 解码器。
2. **安装插件**：将插件 JAR 放入对应平台的插件目录。
3. **按需配置白名单**：如果只允许特定 frps 来源，编辑 `config.yml` 中的 `whitelist`。
4. **重启服务端 / 代理**。
