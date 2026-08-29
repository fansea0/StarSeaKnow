package com.fansea.ai.openapi.auth;

import java.util.ArrayList;
import java.util.List;

public final class IpCidrMatcher {

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
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.indexOf(':') >= 0 ? parseIpv6(value) : parseIpv4(value);
    }

    private static byte[] parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3) {
                return null;
            }
            int number = 0;
            for (int character = 0; character < octet.length(); character++) {
                char valueCharacter = octet.charAt(character);
                if (valueCharacter < '0' || valueCharacter > '9') {
                    return null;
                }
                number = number * 10 + valueCharacter - '0';
            }
            if (number > 255) {
                return null;
            }
            address[index] = (byte) number;
        }
        return address;
    }

    private static byte[] parseIpv6(String value) {
        int compression = value.indexOf("::");
        if (compression != value.lastIndexOf("::")) {
            return null;
        }
        boolean compressed = compression >= 0;
        String left = compressed ? value.substring(0, compression) : value;
        String right = compressed ? value.substring(compression + 2) : "";
        if (compressed && left.indexOf('.') >= 0) {
            return null;
        }
        List<Integer> leftGroups = parseIpv6Groups(left);
        List<Integer> rightGroups = compressed ? parseIpv6Groups(right) : List.of();
        if (leftGroups == null || rightGroups == null) {
            return null;
        }
        int groupCount = leftGroups.size() + rightGroups.size();
        if ((compressed && groupCount >= 8) || (!compressed && groupCount != 8)) {
            return null;
        }
        byte[] address = new byte[16];
        int output = 0;
        for (int group : leftGroups) {
            output = writeGroup(address, output, group);
        }
        output += (8 - groupCount) * 2;
        for (int group : rightGroups) {
            output = writeGroup(address, output, group);
        }
        return address;
    }

    private static List<Integer> parseIpv6Groups(String side) {
        if (side.isEmpty()) {
            return new ArrayList<>();
        }
        String[] groups = side.split(":", -1);
        List<Integer> parsed = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            String group = groups[groupIndex];
            if (group.isEmpty()) {
                return null;
            }
            if (group.indexOf('.') >= 0) {
                if (groupIndex != groups.length - 1) {
                    return null;
                }
                byte[] ipv4 = parseIpv4(group);
                if (ipv4 == null) {
                    return null;
                }
                parsed.add(((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF));
                parsed.add(((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF));
                continue;
            }
            if (group.length() > 4) {
                return null;
            }
            int number = 0;
            for (int character = 0; character < group.length(); character++) {
                int digit = asciiHexDigit(group.charAt(character));
                if (digit < 0) {
                    return null;
                }
                number = (number << 4) | digit;
            }
            parsed.add(number);
        }
        return parsed;
    }

    private static int asciiHexDigit(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static int writeGroup(byte[] address, int output, int group) {
        address[output++] = (byte) (group >>> 8);
        address[output++] = (byte) group;
        return output;
    }
}
