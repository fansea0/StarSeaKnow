package com.fansea.ai.openapi.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public final class IpCidrMatcher {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private IpCidrMatcher() {
    }

    public static boolean matches(String address, String cidr) {
        if (address == null || cidr == null) {
            return false;
        }
        int slash = cidr.lastIndexOf('/');
        if (slash <= 0 || slash == cidr.length() - 1) {
            return false;
        }
        byte[] candidate = literalAddress(address);
        byte[] network = literalAddress(cidr.substring(0, slash));
        if (candidate == null || network == null || candidate.length != network.length) {
            return false;
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (prefixLength < 0 || prefixLength > network.length * Byte.SIZE) {
            return false;
        }
        int wholeBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        for (int index = 0; index < wholeBytes; index++) {
            if (candidate[index] != network[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (Byte.SIZE - remainingBits);
        return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
    }

    public static boolean isIpLiteral(String value) {
        return literalAddress(value) != null;
    }

    private static byte[] literalAddress(String value) {
        if (value == null || !(IPV4_LITERAL.matcher(value).matches() || IPV6_LITERAL.matcher(value).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
