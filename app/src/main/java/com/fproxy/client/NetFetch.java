package com.fproxy.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Bounded HTTPS downloads, no HTTP downgrade and no unlimited-memory responses. */
public final class NetFetch {
    private NetFetch(){}
    public static byte[] bytes(String address,int limit)throws Exception {
        URL url=new URL(address);if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IllegalArgumentException("仅支持 HTTPS");
        for(int hops=0;hops<5;hops++){
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent","F-SingBox/0.1 (+https://github.com/SagerNet/sing-box)");
            c.setRequestProperty("Accept","*/*");
            try{
                int status=c.getResponseCode();
                if(status>=300&&status<400){
                    String redirect=c.getHeaderField("Location");
                    if(redirect==null)throw new IllegalArgumentException("重定向缺少 Location");
                    URL next=new URL(url,redirect);
                    if(!"https".equalsIgnoreCase(next.getProtocol()))throw new SecurityException("禁止降级到 HTTP");
                    url=next;continue;
                }
                if(status!=200)throw new java.io.IOException("HTTP "+status+" ("+url.getHost()+")");
                if(c.getContentLengthLong()>limit)throw new IllegalArgumentException("响应体过大");
                ByteArrayOutputStream o=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()){
                    byte[] b=new byte[8192];int n;
                    while((n=in.read(b))!=-1){if(o.size()+n>limit)throw new IllegalArgumentException("文件超过大小限制");o.write(b,0,n);}
                }
                return o.toByteArray();
            }finally{c.disconnect();}
        }
        throw new java.io.IOException("HTTPS 重定向次数过多");
    }
    /** Stream large APKs to disk (never buffer the whole download in RAM). */
    public static void downloadTo(String address,File target,long maxSize,String sha256)throws Exception{
        URL url=new URL(address);
        if(!"https".equalsIgnoreCase(url.getProtocol()))throw new SecurityException("仅允许 HTTPS APK");
        target.getParentFile().mkdirs();
        try{
            for(int hops=0;hops<5;hops++){
                HttpURLConnection c=(HttpURLConnection)url.openConnection();
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(35000);
                c.setRequestProperty("User-Agent","F-SingBox/0.1");
                try{
                    int status=c.getResponseCode();
                    if(status>=300&&status<400){
                        String location=c.getHeaderField("Location");
                        if(location==null)throw new java.io.IOException("无重定向地址");
                        URL next=new URL(url,location);
                        if(!"https".equalsIgnoreCase(next.getProtocol()))throw new SecurityException("拒绝降级至 HTTP");
                        url=next;continue;
                    }
                    if(status!=200)throw new java.io.IOException("APK 下载 HTTP "+status);
                    if(c.getContentLengthLong()>maxSize)throw new java.io.IOException("APK 文件过大");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    long total=0;
                    try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(target)){
                        byte[] buffer=new byte[16384];int n;
                        while((n=in.read(buffer))!=-1){
                            total+=n;if(total>maxSize)throw new java.io.IOException("下载超过大小限制");
                            out.write(buffer,0,n);digest.update(buffer,0,n);
                        }
                        out.flush();out.getFD().sync();
                    }
                    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.US,"%02x",b&255));
                    if(!hex.toString().equalsIgnoreCase(sha256))throw new SecurityException("APK SHA-256 不符");
                    return;
                }finally{c.disconnect();}
            }
            throw new java.io.IOException("HTTPS 重定向过多");
        }catch(Exception e){target.delete();throw e;}
    }
    public static String text(String url,int bytes)throws Exception{return new String(bytes(url,bytes),StandardCharsets.UTF_8);}
}
