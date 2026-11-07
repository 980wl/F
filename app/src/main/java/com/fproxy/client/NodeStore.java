package com.fproxy.client;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A single atomic local database. One failed subscription never destroys old records. */
public final class NodeStore {
    private final AtomicFile file;
    private JSONObject data;

    public NodeStore(Context c) throws Exception {
        file=new AtomicFile(new File(c.getFilesDir(),"nodes-v1.json"));
        try { data=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8)); }
        catch (java.io.FileNotFoundException missing){data=blank();save();}
        if(data.optInt("schema")!=1)throw new IllegalArgumentException("不支持该备份数据库格式");
    }
    private static JSONObject blank() throws Exception {
        return new JSONObject().put("schema",1).put("selected","").put("nodes",new JSONArray()).put("subscriptions",new JSONArray());
    }
    private synchronized void save() throws Exception {
        FileOutputStream o=null;
        try {o=file.startWrite();o.write(data.toString(2).getBytes(StandardCharsets.UTF_8));o.flush();o.getFD().sync();file.finishWrite(o);}
        catch(Exception e){if(o!=null)file.failWrite(o);throw e;}
    }
    public synchronized JSONArray nodes() throws Exception {return new JSONArray(data.getJSONArray("nodes").toString());}
    public synchronized JSONArray subscriptions() throws Exception {return new JSONArray(data.getJSONArray("subscriptions").toString());}
    public synchronized String selected(){return data.optString("selected","");}
    public synchronized void select(String id) throws Exception {data.put("selected",id);save();}
    public synchronized JSONObject selectedConfig() throws Exception {
        String selected=selected();JSONArray nodes=data.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optString("id").equals(selected))return n.getJSONObject("config");}
        return null;
    }
    public synchronized int importManual(String text) throws Exception {
        List<JSONObject> parsed=NodeParser.parseDocument(text);
        JSONArray current=data.getJSONArray("nodes");Set<String> ids=new HashSet<>();
        for(int i=0;i<current.length();i++)ids.add(current.getJSONObject(i).getString("id"));
        int added=0;
        for(JSONObject conf:parsed){
            String id="manual:"+NodeParser.fingerprint(conf);
            if(ids.add(id)){current.put(new JSONObject().put("id",id).put("source","manual").put("config",conf));added++;}
        }
        if(data.optString("selected").isEmpty()&&current.length()>0)data.put("selected",current.getJSONObject(0).getString("id"));
        save();return added;
    }
    public synchronized String addSubscription(String name,String url) throws Exception {
        if(!url.startsWith("https://"))throw new IllegalArgumentException("订阅地址必须使用 HTTPS");
        JSONArray subs=data.getJSONArray("subscriptions");
        for(int i=0;i<subs.length();i++)if(url.equals(subs.getJSONObject(i).optString("url")))return subs.getJSONObject(i).getString("id");
        String id="sub:"+hex(url).substring(0,24);
        subs.put(new JSONObject().put("id",id).put("name",name.isBlank()?"订阅":name.trim())
                .put("url",url).put("updated",0));
        save();return id;
    }
    public synchronized void replaceSubscription(String id,String text) throws Exception {
        List<JSONObject> parsed=NodeParser.parseDocument(text);
        if(parsed.isEmpty())throw new IllegalArgumentException("订阅没有节点");
        // Prepare new list entirely before touching durable state.
        JSONArray original=data.getJSONArray("nodes");JSONArray next=new JSONArray();
        for(int i=0;i<original.length();i++){
            JSONObject item=original.getJSONObject(i);
            if(!id.equals(item.optString("source")))next.put(item);
        }
        Set<String> seen=new HashSet<>();
        for(JSONObject conf:parsed){
            String nodeId=id+":"+NodeParser.fingerprint(conf);
            if(seen.add(nodeId))next.put(new JSONObject().put("id",nodeId).put("source",id).put("config",conf));
        }
        JSONArray subs=data.getJSONArray("subscriptions");
        boolean found=false;
        for(int i=0;i<subs.length();i++)if(id.equals(subs.getJSONObject(i).optString("id"))){
            subs.getJSONObject(i).put("updated",System.currentTimeMillis());found=true;break;
        }
        if(!found)throw new IllegalArgumentException("订阅记录已删除");
        data.put("nodes",next);
        String oldSelected=data.optString("selected","");
        boolean kept=false;
        for(int i=0;i<next.length();i++)if(oldSelected.equals(next.getJSONObject(i).optString("id")))kept=true;
        if(!kept)data.put("selected",next.length()>0?next.getJSONObject(0).getString("id"):"");
        save();
    }
    public synchronized void deleteAllNodes() throws Exception {
        JSONObject previous=new JSONObject(data.toString());
        data.put("nodes",new JSONArray()).put("selected","");
        try{save();}catch(Exception e){data=previous;throw e;}
    }
    public synchronized void deleteNode(String nodeId) throws Exception {
        JSONArray old=data.getJSONArray("nodes");JSONArray next=new JSONArray();
        for(int i=0;i<old.length();i++)if(!nodeId.equals(old.getJSONObject(i).optString("id")))next.put(old.getJSONObject(i));
        data.put("nodes",next);
        if(nodeId.equals(data.optString("selected")))data.put("selected",next.length()>0?next.getJSONObject(0).getString("id"):"");
        save();
    }
    public synchronized void deleteSubscription(String id) throws Exception {
        JSONArray subs=data.getJSONArray("subscriptions");JSONArray newSubs=new JSONArray();
        for(int i=0;i<subs.length();i++)if(!id.equals(subs.getJSONObject(i).optString("id")))newSubs.put(subs.getJSONObject(i));
        JSONArray nodes=data.getJSONArray("nodes");JSONArray newNodes=new JSONArray();
        for(int i=0;i<nodes.length();i++)if(!id.equals(nodes.getJSONObject(i).optString("source")))newNodes.put(nodes.getJSONObject(i));
        data.put("subscriptions",newSubs).put("nodes",newNodes);
        boolean selectedExists=false;
        for(int i=0;i<newNodes.length();i++)if(data.optString("selected").equals(newNodes.getJSONObject(i).optString("id")))selectedExists=true;
        if(!selectedExists)data.put("selected",newNodes.length()>0?newNodes.getJSONObject(0).optString("id"):"");
        save();
    }
    public synchronized String exportJson() throws Exception {
        return new JSONObject().put("app","F-SingBox").put("format",1).put("exported",System.currentTimeMillis())
                .put("data",new JSONObject(data.toString())).toString(2);
    }
    /** Merge is the default: do not delete pre-existing nodes or subscriptions. */
    public synchronized int restoreMerge(String text) throws Exception {
        if(text.length()>8_000_000)throw new IllegalArgumentException("备份超过 8MB");
        JSONObject root=new JSONObject(text);
        if(!"F-SingBox".equals(root.optString("app"))||root.optInt("format")!=1)
            throw new IllegalArgumentException("这不是 F 的备份文件");
        JSONObject saved=root.getJSONObject("data");
        if(saved.optInt("schema")!=1)throw new IllegalArgumentException("不支持的备份版本");
        // Validate ENTIRE untrusted backup first; no mutation on validation failure.
        JSONArray fromSubs=saved.getJSONArray("subscriptions"),fromNodes=saved.getJSONArray("nodes");
        if(fromSubs.length()>300||fromNodes.length()>NodeParser.MAX_NODES)
            throw new IllegalArgumentException("备份记录过多");
        for(int i=0;i<fromSubs.length();i++){
            JSONObject sub=fromSubs.getJSONObject(i);
            if(!sub.getString("url").startsWith("https://")||!sub.getString("id").startsWith("sub:"))
                throw new IllegalArgumentException("备份中的订阅信息无效");
        }
        for(int i=0;i<fromNodes.length();i++){
            JSONObject node=fromNodes.getJSONObject(i),conf=node.getJSONObject("config");
            String id=node.getString("id"),source=node.getString("source");
            if(!id.startsWith(source+":")||conf.optString("server").isBlank()||conf.optInt("server_port")<1)
                throw new IllegalArgumentException("备份节点数据无效");
        }
        // Copy-on-write is essential: failed imports must not alter in-memory state.
        JSONObject next=new JSONObject(data.toString());
        JSONArray nodes=next.getJSONArray("nodes"),subs=next.getJSONArray("subscriptions");
        Set<String> nodeIds=new HashSet<>(),subIds=new HashSet<>();
        for(int i=0;i<nodes.length();i++)nodeIds.add(nodes.getJSONObject(i).getString("id"));
        for(int i=0;i<subs.length();i++)subIds.add(subs.getJSONObject(i).getString("id"));
        for(int i=0;i<fromSubs.length();i++){
            JSONObject sub=fromSubs.getJSONObject(i);
            if(subIds.add(sub.getString("id")))subs.put(new JSONObject(sub.toString()));
        }
        int added=0;
        for(int i=0;i<fromNodes.length();i++){
            JSONObject node=fromNodes.getJSONObject(i);
            if(nodeIds.add(node.getString("id"))){nodes.put(new JSONObject(node.toString()));added++;}
        }
        if(next.optString("selected").isBlank()&&nodes.length()>0){
            String desired=saved.optString("selected","");
            if(!nodeIds.contains(desired))desired=nodes.getJSONObject(0).getString("id");
            next.put("selected",desired);
        }
        JSONObject previous=data;data=next;
        try{save();}catch(Exception e){data=previous;throw e;}
        return added;
    }
    private static String hex(String text)throws Exception{
        byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte a:b)s.append(String.format("%02x",a));return s.toString();
    }
}
