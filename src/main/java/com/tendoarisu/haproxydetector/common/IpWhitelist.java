package com.tendoarisu.haproxydetector.common;

import io.netty.channel.local.LocalAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IpWhitelist {
    private final boolean enabled;
    private final List<AddressBlock> blocks;
    private final ProxyLogger logger;

    private IpWhitelist(boolean enabled, List<AddressBlock> blocks, ProxyLogger logger) {
        this.enabled = enabled;
        this.blocks = List.copyOf(blocks);
        this.logger = logger;
    }

    public static IpWhitelist resolve(boolean enabled, List<String> entries, ProxyLogger logger) {
        List<AddressBlock> blocks = new ArrayList<>();
        for (String rawEntry : entries) {
            if (rawEntry == null || rawEntry.isBlank()) {
                continue;
            }

            String entry = rawEntry.trim();
            try {
                int slash = entry.lastIndexOf('/');
                if (slash >= 0) {
                    InetAddress network = InetAddress.getByName(entry.substring(0, slash));
                    int prefixLength = Integer.parseInt(entry.substring(slash + 1));
                    blocks.add(AddressBlock.create(network, prefixLength, logger.messages()));
                } else {
                    for (InetAddress address : InetAddress.getAllByName(entry)) {
                        blocks.add(AddressBlock.create(address, address.getAddress().length * 8, logger.messages()));
                    }
                }
            } catch (Exception exception) {
                logger.warn(logger.messages().get(MessageKey.WHITELIST_INVALID_ENTRY, entry), exception);
            }
        }
        return new IpWhitelist(enabled, blocks, logger);
    }

    public boolean matches(SocketAddress socketAddress) {
        if (!enabled) {
            return true;
        }
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) {
            return socketAddress instanceof LocalAddress;
        }

        InetAddress address = inetSocketAddress.getAddress();
        if (address == null) {
            try {
                address = InetAddress.getByName(inetSocketAddress.getHostString());
            } catch (Exception exception) {
                logger.warn(logger.messages().get(MessageKey.SOURCE_RESOLUTION_FAILED,
                        inetSocketAddress.getHostString()), exception);
                return false;
            }
        }

        for (AddressBlock block : blocks) {
            if (block.matches(address)) {
                return true;
            }
        }
        return false;
    }

    static String describe(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            return address == null ? inetSocketAddress.getHostString() : address.getHostAddress();
        }
        return "unknown";
    }

    private record AddressBlock(byte[] network, int prefixLength) {
        private static AddressBlock create(InetAddress address, int prefixLength, Messages messages) {
            byte[] bytes = normalize(address);
            int maximumPrefix = bytes.length * 8;
            if (prefixLength < 0 || prefixLength > maximumPrefix) {
                throw new IllegalArgumentException(messages.get(MessageKey.INVALID_PREFIX, maximumPrefix));
            }

            byte[] network = Arrays.copyOf(bytes, bytes.length);
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0 && fullBytes < network.length) {
                network[fullBytes] &= (byte) (0xFF << (8 - remainingBits));
                fullBytes++;
            }
            Arrays.fill(network, fullBytes, network.length, (byte) 0);
            return new AddressBlock(network, prefixLength);
        }

        private boolean matches(InetAddress address) {
            byte[] candidate = normalize(address);
            if (candidate.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static byte[] normalize(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (address instanceof Inet6Address && isIpv4Mapped(bytes)) {
                return Arrays.copyOfRange(bytes, 12, 16);
            }
            if (address instanceof Inet4Address || address instanceof Inet6Address) {
                return bytes;
            }
            return bytes;
        }

        private static boolean isIpv4Mapped(byte[] bytes) {
            if (bytes.length != 16) {
                return false;
            }
            for (int index = 0; index < 10; index++) {
                if (bytes[index] != 0) {
                    return false;
                }
            }
            return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
        }
    }
}
