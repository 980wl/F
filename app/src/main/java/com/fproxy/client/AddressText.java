package com.fproxy.client;

public final class AddressText {
    private AddressText() {
    }

    public static String prefix(String address, int prefixLength) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Empty interface address");
        }
        int zoneIdx = address.indexOf('%');
        if (zoneIdx >= 0) {
            address = address.substring(0, zoneIdx);
        }
        return address + "/" + prefixLength;
    }
}
