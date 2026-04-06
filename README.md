# HAProxyDetector

[简体中文](README_CN.md)

A lightweight plugin for Paper/Folia and Velocity that makes native HAProxy / Proxy Protocol support compatible with normal direct player connections.

## Core Features

- **Mixed Connection Support**: Accepts real HAProxy/frp proxy traffic and normal direct player traffic at the same time.
- **Native Parser Compatibility**: Adds a synthetic HAProxy V2 header to direct traffic so the server can continue using its native proxy-protocol parsing flow.
- **Whitelist Supports Domain / IP / CIDR**: `whitelist` supports single IP entries, CIDR ranges, and domain names.
- **Minimal Intercept Logging**: Logs when a non-whitelisted proxy connection is blocked, including the frps source IP and client IP.

## How It Works

1. **Real HAProxy traffic**: If a valid HAProxy v1/v2 header is detected, it is passed directly to the server's native parser.
2. **Normal direct players**: If no HAProxy header exists, the plugin adds a synthetic HAProxy V2 header based on the player's real socket address.
3. **Synthetic header protection**: Internally marks self-generated headers so later whitelist checks will not mistake them for external frps traffic.
4. **Whitelist filtering**: When `enable-whitelist` is enabled, only real external proxy sources are checked.

## Supported Platforms

- **Paper / Folia**: Theoretically supports versions with native HAProxy support.
- **Velocity**: Theoretically supports `3.0.0+`.

## Configuration

Paper and Velocity use the same `config.yml` structure:

```
enable-whitelist: true
whitelist:
  - 127.0.0.1
  - "::1"
  - frps.example.com
  - 203.0.113.0/24
```

### Whitelist Rules

- **IP**: Exact match.
- **CIDR**: Network range match, for example `203.0.113.0/24`.
- **Domain**: Automatically resolved into an IP list when the plugin loads the configuration.

### Notes About Domain Whitelist Entries

- Domain names are resolved when the plugin loads or reloads its configuration, not on every connection.
- If the DNS result changes later, restart or reload the plugin to refresh the resolved IP list.
- If a domain temporarily fails to resolve, the plugin will not fail to start and will keep the original config entry.

## Logging Behavior

Logs are only printed when a non-whitelisted HAProxy connection is blocked.

Log fields:
- `frps`: The real source IP of the upstream proxy server.
- `client`: The client source IP carried in the HAProxy header.

Example:

```
[HAProxyDetectorPaper] 拦截非白名单 frps 连接: frps=91.78.69.13, client=91.78.69.13
```

## Usage

1. **Enable native HAProxy support**:
   - **Paper / Folia**: Set `proxy-protocol` to `true` in `config/paper-global.yml` (newer versions) or `paper.yml` (older versions).
   - **Velocity**: Set `haproxy = true` in `velocity.toml`.
2. **Install the plugin**: Put the plugin JAR into the correct platform plugin directory.
3. **Configure the whitelist if needed**: If you only want to allow specific frps sources, edit `whitelist` in `config.yml`.
4. **Restart the server / proxy**.

## Disable / Unload Behavior

The plugin actively removes its injected Netty handlers during disable or shutdown, so unloading it will not continue affecting new connections.
For low-level network plugins in production environments, a full restart is still the safest option.

## Technical Details

- **Netty ChannelPipeline Injection**: Intercepts raw `ByteBuf` at the front of the network pipeline.
- **Feature Detection**: Uses reflection to locate the server connection structure without depending on one fixed field name.
- **HAProxy V2 Header Forging**: Generates a standard HAProxy V2 header for direct connections.
- **Synthetic Marker**: Prevents self-generated headers from being checked again as external proxy traffic.
