package com.fproxy.client;
import android.content.Context;
import java.io.*;
/** Bundled mainland IPv4/IPv6 rules make the first connection independent of online rule downloads. */
public final class RuleResources {
    public static String mainland(Context c)throws IOException{
        File dir=new File(c.getFilesDir(),"rules");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建分流规则目录");
        File file=new File(dir,"geoip-cn.srs");
        if(file.exists())try(FileInputStream in=new FileInputStream(file)){
            if(file.length()>8&&in.read()=='S'&&in.read()=='R'&&in.read()=='S')return file.getAbsolutePath();
        }
        File tmp=new File(dir,"geoip-cn-bundled.tmp");
        try(InputStream in=c.getAssets().open("geoip-cn.srs");FileOutputStream out=new FileOutputStream(tmp)){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}
        java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return file.getAbsolutePath();
    }
}
