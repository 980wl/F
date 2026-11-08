package com.fproxy.client;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class NodeStore {
    private JSONObject data;
    private final AtomicFile file;

    public NodeStore(Context context) throws Exception {
        this.file = new AtomicFile(new File(context.getFilesDir(), "nodes-v1.json"));
        try {
            this.data = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            this.data = blank();
            save();
        }
        if (this.data.optInt("schema") != 1) {
            throw new IllegalArgumentException("不支持该备份数据库格式");
        }
    }

    private static JSONObject blank() throws Exception {
        return new JSONObject()
                .put("schema", 1)
                .put("selected", "")
                .put("nodes", new JSONArray())
                .put("subscriptions", new JSONArray());
    }

    private static String hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private synchronized void save() throws Exception {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) {
                file.failWrite(out);
            }
            throw e;
        }
    }

    public synchronized String addSubscription(String name, String url) throws Exception {
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("订阅地址必须使用 HTTPS");
        }
        JSONArray subs = data.getJSONArray("subscriptions");
        for (int i = 0; i < subs.length(); i++) {
            if (url.equals(subs.getJSONObject(i).optString("url"))) {
                return subs.getJSONObject(i).getString("id");
            }
        }
        String id = "sub:" + hex(url).substring(0, 24);
        subs.put(new JSONObject()
                .put("id", id)
                .put("name", name == null || name.trim().isEmpty() ? "订阅" : name.trim())
                .put("url", url)
                .put("updated", 0));
        save();
        return id;
    }

    public synchronized void deleteAllNodes() throws Exception {
        JSONObject backup = new JSONObject(data.toString());
        try {
            data.put("nodes", new JSONArray()).put("selected", "");
            save();
        } catch (Exception e) {
            data = backup;
            throw e;
        }
    }

    public synchronized void deleteNode(String id) throws Exception {
        JSONArray oldNodes = data.getJSONArray("nodes");
        JSONArray nextNodes = new JSONArray();
        for (int i = 0; i < oldNodes.length(); i++) {
            JSONObject item = oldNodes.getJSONObject(i);
            if (!id.equals(item.optString("id"))) {
                nextNodes.put(item);
            }
        }
        data.put("nodes", nextNodes);
        if (id.equals(data.optString("selected"))) {
            data.put("selected", nextNodes.length() > 0 ? nextNodes.getJSONObject(0).getString("id") : "");
        }
        save();
    }

    public synchronized void deleteSubscription(String subId) throws Exception {
        JSONArray subs = data.getJSONArray("subscriptions");
        JSONArray nextSubs = new JSONArray();
        for (int i = 0; i < subs.length(); i++) {
            if (!subId.equals(subs.getJSONObject(i).optString("id"))) {
                nextSubs.put(subs.getJSONObject(i));
            }
        }
        JSONArray nodes = data.getJSONArray("nodes");
        JSONArray nextNodes = new JSONArray();
        for (int i = 0; i < nodes.length(); i++) {
            if (!subId.equals(nodes.getJSONObject(i).optString("source"))) {
                nextNodes.put(nodes.getJSONObject(i));
            }
        }
        data.put("subscriptions", nextSubs).put("nodes", nextNodes);
        boolean foundSelected = false;
        for (int i = 0; i < nextNodes.length(); i++) {
            if (data.optString("selected").equals(nextNodes.getJSONObject(i).optString("id"))) {
                foundSelected = true;
            }
        }
        if (!foundSelected) {
            data.put("selected", nextNodes.length() > 0 ? nextNodes.getJSONObject(0).optString("id") : "");
        }
        save();
    }

    public synchronized String exportJson() throws Exception {
        return new JSONObject()
                .put("app", "F-SingBox")
                .put("format", 1)
                .put("exported", System.currentTimeMillis())
                .put("data", new JSONObject(data.toString()))
                .toString(2);
    }

    public synchronized int importManual(String raw) throws Exception {
        List<JSONObject> parsed = NodeParser.parseDocument(raw);
        JSONArray nodes = data.getJSONArray("nodes");
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < nodes.length(); i++) {
            existing.add(nodes.getJSONObject(i).getString("id"));
        }
        int added = 0;
        for (JSONObject cfg : parsed) {
            String id = "manual:" + NodeParser.fingerprint(cfg);
            if (existing.add(id)) {
                nodes.put(new JSONObject()
                        .put("id", id)
                        .put("source", "manual")
                        .put("config", cfg));
                added++;
            }
        }
        if (data.optString("selected").isEmpty() && nodes.length() > 0) {
            data.put("selected", nodes.getJSONObject(0).getString("id"));
        }
        save();
        return added;
    }

    public synchronized JSONArray nodes() throws Exception {
        return new JSONArray(data.getJSONArray("nodes").toString());
    }

    public synchronized void replaceSubscription(String subId, String raw) throws Exception {
        List<JSONObject> parsed = NodeParser.parseDocument(raw);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("订阅没有节点");
        }
        JSONArray oldNodes = data.getJSONArray("nodes");
        JSONArray nextNodes = new JSONArray();
        for (int i = 0; i < oldNodes.length(); i++) {
            JSONObject item = oldNodes.getJSONObject(i);
            if (!subId.equals(item.optString("source"))) {
                nextNodes.put(item);
            }
        }
        Set<String> seen = new HashSet<>();
        for (JSONObject cfg : parsed) {
            String id = subId + ":" + NodeParser.fingerprint(cfg);
            if (seen.add(id)) {
                nextNodes.put(new JSONObject()
                        .put("id", id)
                        .put("source", subId)
                        .put("config", cfg));
            }
        }
        JSONArray subs = data.getJSONArray("subscriptions");
        for (int i = 0; i < subs.length(); i++) {
            if (subId.equals(subs.getJSONObject(i).optString("id"))) {
                subs.getJSONObject(i).put("updated", System.currentTimeMillis());
                data.put("nodes", nextNodes);
                String sel = data.optString("selected", "");
                boolean found = false;
                for (int j = 0; j < nextNodes.length(); j++) {
                    if (sel.equals(nextNodes.getJSONObject(j).optString("id"))) {
                        found = true;
                    }
                }
                if (!found) {
                    data.put("selected", nextNodes.length() > 0 ? nextNodes.getJSONObject(0).getString("id") : "");
                }
                save();
                return;
            }
        }
        throw new IllegalArgumentException("订阅记录已删除");
    }

    public synchronized int restoreMerge(String rawJson) throws Exception {
        if (rawJson.length() > 8000000) {
            throw new IllegalArgumentException("备份超过 8MB");
        }
        JSONObject wrapper = new JSONObject(rawJson);
        if (!"F-SingBox".equals(wrapper.optString("app")) || wrapper.optInt("format") != 1) {
            throw new IllegalArgumentException("这不是 F 的备份文件");
        }
        JSONObject incoming = wrapper.getJSONObject("data");
        if (incoming.optInt("schema") != 1) {
            throw new IllegalArgumentException("不支持的备份版本");
        }
        JSONArray inSubs = incoming.getJSONArray("subscriptions");
        JSONArray inNodes = incoming.getJSONArray("nodes");
        if (inSubs.length() > 300 || inNodes.length() > 2000) {
            throw new IllegalArgumentException("备份记录过多");
        }
        for (int i = 0; i < inSubs.length(); i++) {
            JSONObject s = inSubs.getJSONObject(i);
            if (!s.getString("url").startsWith("https://") || !s.getString("id").startsWith("sub:")) {
                throw new IllegalArgumentException("备份中的订阅信息无效");
            }
        }
        for (int i = 0; i < inNodes.length(); i++) {
            JSONObject n = inNodes.getJSONObject(i);
            JSONObject cfg = n.getJSONObject("config");
            if (!n.getString("id").startsWith(n.getString("source") + ":")
                    || cfg.optString("server").trim().isEmpty()
                    || cfg.optInt("server_port") < 1) {
                throw new IllegalArgumentException("备份节点数据无效");
            }
        }
        JSONObject nextData = new JSONObject(data.toString());
        JSONArray curNodes = nextData.getJSONArray("nodes");
        JSONArray curSubs = nextData.getJSONArray("subscriptions");
        Set<String> nodeIds = new HashSet<>();
        Set<String> subIds = new HashSet<>();
        for (int i = 0; i < curNodes.length(); i++) {
            nodeIds.add(curNodes.getJSONObject(i).getString("id"));
        }
        for (int i = 0; i < curSubs.length(); i++) {
            subIds.add(curSubs.getJSONObject(i).getString("id"));
        }
        for (int i = 0; i < inSubs.length(); i++) {
            JSONObject s = inSubs.getJSONObject(i);
            if (subIds.add(s.getString("id"))) {
                curSubs.put(new JSONObject(s.toString()));
            }
        }
        int addedNodes = 0;
        for (int i = 0; i < inNodes.length(); i++) {
            JSONObject n = inNodes.getJSONObject(i);
            if (nodeIds.add(n.getString("id"))) {
                curNodes.put(new JSONObject(n.toString()));
                addedNodes++;
            }
        }
        if (nextData.optString("selected").trim().isEmpty() && curNodes.length() > 0) {
            String inSel = incoming.optString("selected", "");
            if (!nodeIds.contains(inSel)) {
                inSel = curNodes.getJSONObject(0).getString("id");
            }
            nextData.put("selected", inSel);
        }
        JSONObject oldData = this.data;
        try {
            this.data = nextData;
            save();
            return addedNodes;
        } catch (Exception e) {
            this.data = oldData;
            throw e;
        }
    }

    public synchronized void select(String id) throws Exception {
        data.put("selected", id);
        save();
    }

    /**
     * 从磁盘重读 nodes-v1.json。主页面持有长生命周期的 NodeStore 实例，
     * 配置页是另一个实例写盘，返回主页时必须重读，否则列表仍显示旧数据，
     * 且下一次点卡片（select）会把内存里的旧数据写回盘里，丢掉刚才的修改。
     * 只有解析成功且 schema 校验通过才替换内存数据，读盘失败时保持原数据不动。
     */
    public synchronized void reload() throws Exception {
        JSONObject next = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (next.optInt("schema") != 1) {
            throw new IllegalArgumentException("不支持该备份数据库格式");
        }
        data = next;
    }

    public synchronized JSONObject node(String id) throws Exception {
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject item = nodes.getJSONObject(i);
            if (id.equals(item.optString("id"))) {
                return new JSONObject(item.toString());
            }
        }
        return null;
    }

    public synchronized void updateNodeConfig(String id, JSONObject config) throws Exception {
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject item = nodes.getJSONObject(i);
            if (id.equals(item.optString("id"))) {
                item.put("config", config);
                save();
                return;
            }
        }
        throw new IllegalArgumentException("节点不存在");
    }

    public synchronized String selected() {
        return data.optString("selected", "");
    }

    public synchronized JSONObject selectedConfig() throws Exception {
        String sel = selected();
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject item = nodes.getJSONObject(i);
            if (item.optString("id").equals(sel)) {
                return item.getJSONObject("config");
            }
        }
        return null;
    }

    public synchronized JSONArray subscriptions() throws Exception {
        return new JSONArray(data.getJSONArray("subscriptions").toString());
    }
}
