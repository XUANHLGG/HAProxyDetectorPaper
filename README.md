# HAProxyDetector

[简体中文](README_CN.md)

A lightweight plugin designed to support both native HAProxy / Proxy Protocol and direct player connections simultaneously on Paper/Folia and Velocity.

## Key Features

- **Hybrid Connection Support**: Simultaneously handles genuine HAProxy/frp proxy traffic and direct player connections.
- **Native Parser Compatibility**: Probes protocols on a per-connection basis; direct connections bypass the native HAProxy decoder directly.
- **Domain / IP / CIDR Whitelisting**: `whitelist` supports individual IPs, CIDR subnets, and domain names.

## Supported Platforms

- **Paper / Folia**: Theoretically supports any version with native HAProxy / Proxy Protocol functionality.
- **Velocity**: Targeted at `3.3.0+`, compatible with Velocity 4.x's current connection initialization architecture.

## Configuration

Both Paper and Velocity share the same `config.yml` structure:

```yaml
language: # detect and save the OS/JVM language; or set en_US / zh_CN
enable-whitelist: true
whitelist:
  - 127.0.0.1
  - "::1"
  - frps.example.com
  - 203.0.113.0/24
```

### Whitelist Rules

- **IP**: Exact match.
- **CIDR**: Subnet matching, e.g., `203.0.113.0/24`.
- **Domain Name**: Automatically resolved to IP addresses when the plugin loads the configuration to participate in matching.

### Domain Whitelist Notes

- Domain names are resolved when the plugin loads or reloads the configuration, not dynamically on every connection.
- If DNS resolution changes later, a server restart or plugin reload is required to refresh the cached IPs.
- If a domain temporarily fails to resolve, the plugin will not fail to start.

## Logging Behavior

Intercept log field descriptions:
- `frps`: The real source IP of the upstream proxy server.
- `client`: The client source IP carried in the HAProxy header.

Example:

```
[HAProxyDetectorPaper] Blocked non-whitelisted frps connection: frps=91.78.69.13, client=91.78.69.13
```

## Usage

1. **Configure the Platform**:
   - **Paper / Folia**: Set `proxy-protocol` to `true` in `config/paper-global.yml` (newer versions) or `paper.yml` (legacy versions).
   - **Velocity**: Set `haproxy-protocol` to `true` under `[advanced]` in `velocity.toml`. The plugin reuses Velocity's native HAProxy decoder.
2. **Install the Plugin**: Place the plugin JAR into the respective platform's `plugins` folder.
3. **Configure Whitelist (Optional)**: If you only want to allow specific frps sources, configure `whitelist` in `config.yml`.
4. **Restart Server / Proxy**.
