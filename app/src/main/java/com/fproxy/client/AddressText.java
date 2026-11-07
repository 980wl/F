package com.fproxy.client;
/** libbox expects a netip.Prefix, whose address must not contain an IPv6 zone. */
public final class AddressText {
    private AddressText(){}
    public static String prefix(String address,int bits){
        if(address==null||address.isEmpty())throw new IllegalArgumentException("Empty interface address");
        int zone=address.indexOf('%');if(zone>=0)address=address.substring(0,zone);
        return address+"/"+bits;
    }
}
