package com.fproxy.client;
import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Flat node list: all selection, delete, probe, subscription and backup actions are visible. */
public final class MainActivity extends Activity {
    private static final int REQUEST_VPN=501,REQUEST_EXPORT=502,REQUEST_IMPORT=503,REQUEST_INSTALL=504;
    private static final int RED=0xffd3222a,DARK=0xff202632,MUTED=0xff7a8291;
    private NodeStore store;private JSONArray items=new JSONArray();
    private JSONObject vpn=new JSONObject(),probe=new JSONObject(),measurements=new JSONObject();
    private TextView status,detail,nodeCount,probeInfo,logs;
    private LinearLayout appPolicyPanel, appPolicyRows;
    private EditText appSearch;
    private Button moreApps;
    private AppDirectPolicy.Snapshot appPolicy;
    private boolean scanningApps;
    private int appLimit;
    private static final int APP_PAGE_SIZE = 40;
    private LinearLayout subscriptions;private EditText updateUrl;
    private Button toggle,refresh,testAll;
    private NodeAdapter adapter;private boolean busy,testing;
    private JSONObject pendingUpdate;
    private long probeVersion=-1;private String oldPhase="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){if(!isFinishing()){readStates();handler.postDelayed(this,900);}}};
    @Override public void onCreate(Bundle b){super.onCreate(b);CrashDiary.install(this,"界面");
        try{store=new NodeStore(this);}catch(Exception e){fatal(e.toString());return;}
        buildUI();refreshNodeList();readStates();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},601);
    }
    @Override public void onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll);}
    @Override public void onPause(){handler.removeCallbacks(poll);super.onPause();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String value,boolean primary){Button b=new Button(this);b.setText(value);b.setTextSize(13);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(7),0,dp(7),0);b.setTextColor(primary?Color.WHITE:DARK);b.setBackground(bg(primary?RED:0xffe9edf4,10));return b;}
    private void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private void pair(LinearLayout parent,Button a,Button b){LinearLayout r=row();
        for(Button x:new Button[]{a,b}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(43),1);p.setMargins(dp(3),dp(4),dp(3),dp(4));r.addView(x,p);}parent.addView(r);}
    private void buildUI(){
        ListView list=new ListView(this);list.setBackgroundColor(0xfff5f7fb);list.setDivider(null);list.setClipToPadding(false);list.setPadding(dp(14),0,dp(14),0);
        LinearLayout header=vertical();header.setPadding(dp(3),dp(18),dp(3),dp(8));
        LinearLayout heading = row();
        TextView title = text("F", 25, DARK);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title);
        TextView version = text("大陆直连 / 境外代理 · 内核 " + BuildConfig.CORE_VERSION
                + " · v" + BuildConfig.VERSION_NAME, 12, MUTED);
        LinearLayout.LayoutParams versionLayout = new LinearLayout.LayoutParams(0, -2, 1);
        versionLayout.leftMargin = dp(9);
        heading.addView(version, versionLayout);
        header.addView(heading);
        gap(header, 14);
        status=text("未连接",23,DARK);status.setTypeface(null,Typeface.BOLD);header.addView(status);
        detail=text("点击节点选择，再连接；测速会真实访问 HTTPS。",12,MUTED);detail.setTextIsSelectable(true);header.addView(detail);gap(header,8);
        toggle=button("连接所选节点",true);refresh=button("更新全部",false);pair(header,toggle,refresh);
        toggle.setOnClickListener(v->toggleVpn());refresh.setOnClickListener(v->updateAll());
        Button add=button("导入节点",false),sub=button("添加订阅",false),backup=button("导出备份",false),restore=button("恢复备份",false);
        LinearLayout quick=row();
        for(Button action:new Button[]{add,sub,backup,restore}){
            action.setTextSize(12);LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(0,dp(42),1);
            qp.setMargins(dp(2),dp(4),dp(2),dp(4));quick.addView(action,qp);
        }
        header.addView(quick);
        add.setOnClickListener(v->showImportDialog());sub.setOnClickListener(v->addSubscriptionDialog());
        backup.setOnClickListener(v->requestExport());restore.setOnClickListener(v->requestImport());gap(header,10);
        LinearLayout toolbar=row();nodeCount=text("节点  0",16,DARK);nodeCount.setTypeface(null,Typeface.BOLD);toolbar.addView(nodeCount,new LinearLayout.LayoutParams(0,-2,1));
        testAll=button("测速全部",false);Button clear=button("全部删除",false);
        toolbar.addView(testAll,new LinearLayout.LayoutParams(dp(90),dp(40)));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(84),dp(40));cp.leftMargin=dp(6);toolbar.addView(clear,cp);header.addView(toolbar);
        testAll.setOnClickListener(v->{if(testing){stopService(new Intent(this,ProbeService.class));probeInfo.setText("正在停止测速…");}else probeNode(null);});clear.setOnClickListener(v->clearNodes());
        probeInfo=text("测速显示 HTTPS 真连接耗时（ms），不是 TCP ping。",11,MUTED);header.addView(probeInfo);
        list.addHeaderView(header,null,false);
        LinearLayout footer=vertical();footer.setPadding(dp(3),dp(14),dp(3),dp(24));
        footer.addView(text("订阅",17,DARK));subscriptions=vertical();footer.addView(subscriptions);gap(footer,16);
        footer.addView(text("应用直连",17,DARK));
        appPolicyPanel = vertical();
        appPolicyPanel.setVisibility(View.GONE);
        footer.addView(appPolicyPanel);
        Button rescan = button("刷新本机应用列表", false);
        Button collapseApps = button("收起应用列表", false);
        pair(appPolicyPanel, rescan, collapseApps);
        rescan.setOnClickListener(v -> scanApps());
        collapseApps.setOnClickListener(v -> {
            appLimit = 0;
            appPolicyPanel.setVisibility(View.GONE);
            appPolicyRows.removeAllViews();
            moreApps.setVisibility(View.VISIBLE);
        });
        appSearch = new EditText(this);
        appSearch.setTextSize(13);
        appSearch.setSingleLine(true);
        appSearch.setHint("查找应用名称或包名");
        appPolicyPanel.addView(appSearch);
        appPolicyRows = vertical();
        appPolicyPanel.addView(appPolicyRows);
        appSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (appLimit == 0) return;
                appLimit = APP_PAGE_SIZE;
                renderAppPolicy();
            }
            public void afterTextChanged(android.text.Editable value) {}
        });
        moreApps = button("再显示40个应用", false);
        footer.addView(moreApps);
        moreApps.setOnClickListener(v -> {
            appLimit += APP_PAGE_SIZE;
            appPolicyPanel.setVisibility(View.VISIBLE);
            if (appPolicy == null) scanApps();
            else renderAppPolicy();
        });
        gap(footer, 16);
        footer.addView(text("内核更新地址",15,DARK));footer.addView(text("填入同签名 F 安装包的 HTTPS 更新清单地址。",11,MUTED));
        updateUrl=new EditText(this);updateUrl.setTextSize(12);updateUrl.setSingleLine(true);updateUrl.setHint("https://…/f-updates.json");updateUrl.setText(UpdateManager.updateSource(this));footer.addView(updateUrl);
        Button save=button("保存更新地址",false);footer.addView(save);save.setOnClickListener(v->{String value=updateUrl.getText().toString().trim();
            if(!value.isEmpty()&&!value.startsWith("https://")){message("请使用 HTTPS 地址");return;}
            getSharedPreferences("updates",MODE_PRIVATE).edit().putString("manifest",value).apply();detail.setText("更新地址已保存");});gap(footer,12);
        Button diagnostic=button("查看 / 刷新错误日志",false);footer.addView(diagnostic);
        logs=text("",11,MUTED);logs.setTextIsSelectable(true);footer.addView(logs);diagnostic.setOnClickListener(v->logs.setText(CrashDiary.read(this)));
        list.addFooterView(footer,null,false);adapter=new NodeAdapter();list.setAdapter(adapter);setContentView(list);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(list,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(14)+bars.left,bars.top,dp(14)+bars.right,bars.bottom);return insets;});
    }
    private void scanApps() {
        if (appLimit == 0 || scanningApps) return;
        scanningApps = true;
        moreApps.setEnabled(false);
        new Thread(() -> {
            try {
                AppDirectPolicy.Snapshot found = AppDirectPolicy.scan(this);
                runOnUiThread(() -> {
                    scanningApps = false;
                    if (isFinishing()) return;
                    moreApps.setEnabled(true);
                    appPolicy = found;
                    if (appLimit != 0 && !found.conflict.isEmpty()) detail.setText(found.conflict);
                    renderAppPolicy();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    scanningApps = false;
                    moreApps.setEnabled(true);
                    if (!isFinishing() && appLimit != 0) error(e);
                });
            }
        }, "f-app-scan").start();
    }

    private void renderAppPolicy() {
        if (appLimit == 0 || appPolicy == null) return;
        appPolicyRows.removeAllViews();
        String query = appSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        boolean hasMore = false;
        for (AppDirectPolicy.Entry entry : appPolicy.apps) {
            String caption = entry.label + "\n" + entry.pkg;
            if (!caption.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            if (appPolicyRows.getChildCount() == appLimit) {
                hasMore = true;
                break;
            }
            CheckBox box = new CheckBox(this);
            box.setTextSize(12);
            box.setText(caption);
            box.setChecked(entry.direct);
            box.setEnabled(!entry.automatic && !entry.required && !entry.browser);
            appPolicyRows.addView(box);
            box.setOnCheckedChangeListener((button, checked) -> {
                try {
                    AppDirectPolicy.setManual(this, entry.pkg, checked);
                    entry.direct = checked;
                    if (checked) appPolicy.excluded.add(entry.pkg);
                    else appPolicy.excluded.remove(entry.pkg);
                    if (RunState.active(RunState.vpn(this))) {
                        startService(new Intent(this, FVpnService.class).setAction(FVpnService.ACTION_STOP));
                        detail.setText("直连设置已保存。请重新连接使设置生效。");
                    } else {
                        detail.setText("直连设置已保存，下次连接生效。");
                    }
                } catch (Exception e) {
                    error(e);
                }
            });
        }
        moreApps.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }
    private void readStates(){
        vpn=RunState.vpn(this);status.setText(RunState.label(vpn));
        String phase=vpn.optString("phase");toggle.setEnabled(!phase.equals("stopping"));toggle.setText(RunState.active(vpn)?"断开连接":"连接所选节点");
        JSONObject next=RunState.read(this,"probe-state");boolean active=next.optBoolean("running");
        if(active&&System.currentTimeMillis()-next.optLong("updated")>3000&&!RunState.alive(this,next.optInt("pid"))){active=false;
            try{next.put("message","测速内核意外退出，请查看错误日志");}catch(Exception ignored){}}
        boolean changed=testing!=active||probeVersion!=next.optLong("updated");testing=active;probe=next;probeVersion=next.optLong("updated");
        measurements=probe.optJSONObject("results");if(measurements==null)measurements=new JSONObject();
        testAll.setText(testing?"停止测速":"测速全部");
        if(!probe.optString("message").isEmpty())probeInfo.setText(probe.optString("message"));
        if(!busy&&!vpn.optString("error").isEmpty())detail.setText(vpn.optString("error"));
        if(changed||!oldPhase.equals(phase)){adapter.notifyDataSetChanged();oldPhase=phase;}
    }
    private void refreshNodeList(){try{items=store.nodes();nodeCount.setText("节点  "+items.length());adapter.notifyDataSetChanged();renderSubscriptions();}catch(Exception e){error(e);}}
    private final class NodeAdapter extends BaseAdapter {
        public int getCount(){return items.length();}public Object getItem(int p){return items.optJSONObject(p);}public long getItemId(int p){return p;}
        public View getView(int position,View recycled,ViewGroup parent){
            JSONObject item=items.optJSONObject(position);JSONObject conf=item.optJSONObject("config");String id=item.optString("id");
            boolean selected=id.equals(store.selected());LinearLayout outside=vertical();outside.setPadding(0,dp(4),0,dp(4));
            LinearLayout card=vertical();card.setPadding(dp(12),dp(12),dp(12),dp(10));GradientDrawable background=bg(selected?0xfffff3f3:Color.WHITE,12);
            if(selected)background.setStroke(dp(1),RED);card.setBackground(background);outside.addView(card);
            LinearLayout first=row();TextView name=text((selected?"●  ":"○  ")+conf.optString("tag",conf.optString("server","节点")),15,selected?RED:DARK);
            name.setTypeface(null,Typeface.BOLD);first.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            JSONObject r=measurements.optJSONObject(id);String state=r==null?"":r.optString("status");String label="未测速";int color=MUTED;
            if(state.equals("ok")){label=r.optLong("delay")+" ms";color=0xff138052;}
            else if(state.equals("testing")){label=testing?"测速中…":"测速中断";}
            else if(state.equals("failed")){label="未连通";color=RED;}else if(state.equals("cancelled"))label="已取消";
            TextView latency=text(label,13,color);latency.setGravity(Gravity.END);first.addView(latency,new LinearLayout.LayoutParams(dp(84),-2));card.addView(first);
            TextView address=text(conf.optString("type")+"  ·  "+conf.optString("server")+":"+conf.optInt("server_port"),11,MUTED);card.addView(address);gap(card,7);
            LinearLayout actions=row();TextView hint=text(selected?"当前选择":"点击此卡片选择",11,MUTED);actions.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
            Button test=button("测速",false),del=button("删除",false);actions.addView(test,new LinearLayout.LayoutParams(dp(65),dp(35)));LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(dp(65),dp(35));d.leftMargin=dp(7);actions.addView(del,d);card.addView(actions);
            test.setEnabled(!testing);test.setOnClickListener(v->probeNode(id));del.setOnClickListener(v->deleteNode(id,conf.optString("tag","节点")));
            if(r!=null&&state.equals("failed")){TextView err=text(r.optString("detail"),10,RED);err.setTextIsSelectable(true);card.addView(err);}
            card.setOnClickListener(v->{if(RunState.active(RunState.vpn(MainActivity.this))){detail.setText("请先断开连接，再切换节点。");return;}
                try{store.select(id);notifyDataSetChanged();}catch(Exception e){error(e);}});
            return outside;
        }
    }
    private void toggleVpn(){
        if(RunState.active(RunState.vpn(this))){startService(new Intent(this,FVpnService.class).setAction(FVpnService.ACTION_STOP));detail.setText("正在断开连接…");return;}
        try{if(store.selectedConfig()==null){message("请先导入并选择节点");return;}
            Intent auth=VpnService.prepare(this);if(auth!=null)startActivityForResult(auth,REQUEST_VPN);else startVpn();
        }catch(Throwable t){operationError("请求 VPN 授权",t);}
    }
    private void startVpn(){try{detail.setText("正在启动 VPN，出错信息会保留在此页。");startForegroundService(new Intent(this,FVpnService.class));}
        catch(Throwable t){operationError("启动 VPN",t);}}
    private void probeNode(String id){
        if(testing||busy)return;
        if(items.length()==0){message("请先导入节点");return;}
        try{Intent intent=new Intent(this,ProbeService.class);if(id!=null)intent.putExtra("node",id);startForegroundService(intent);
            testing=true;testAll.setText("停止测速");probeInfo.setText("正在启动真实连接测速…");adapter.notifyDataSetChanged();}
        catch(Throwable t){testing=false;operationError("启动测速",t);}
    }
    private void deleteNode(String id,String name){
        new AlertDialog.Builder(this).setTitle("删除节点").setMessage("删除「"+name+"」？如果正在使用该节点，会同时断开连接。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                try{if(id.equals(RunState.vpn(this).optString("node")))startService(new Intent(this,FVpnService.class).setAction(FVpnService.ACTION_STOP));store.deleteNode(id);refreshNodeList();}
                catch(Exception e){error(e);}}).show();
    }
    private void clearNodes(){
        new AlertDialog.Builder(this).setTitle("删除全部节点").setMessage("删除全部 "+items.length()+" 个节点并断开连接？保留订阅地址，之后更新订阅会重新导入节点。")
            .setNegativeButton("取消",null).setPositiveButton("全部删除",(d,w)->{
                try{startService(new Intent(this,FVpnService.class).setAction(FVpnService.ACTION_STOP));stopService(new Intent(this,ProbeService.class));store.deleteAllNodes();refreshNodeList();}
                catch(Exception e){error(e);}}).show();
    }
    private void showImportDialog(){EditText edit=new EditText(this);edit.setMinLines(4);edit.setMaxLines(10);edit.setHint("粘贴 hy2://、hysteria2://、tuic:// 等链接\n支持批量链接及 sing-box JSON / Clash 订阅");
        new AlertDialog.Builder(this).setTitle("导入节点").setView(edit).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->
            background("正在导入",()->"已新增 "+store.importManual(edit.getText().toString())+" 个节点")).show();}
    private void renderSubscriptions()throws Exception{
        subscriptions.removeAllViews();JSONArray all=store.subscriptions();if(all.length()==0){subscriptions.addView(text("暂无订阅，可直接点击上方添加订阅。",12,MUTED));return;}
        for(int i=0;i<all.length();i++){
            JSONObject sub=all.getJSONObject(i);String id=sub.getString("id");LinearLayout card=vertical();card.setPadding(0,dp(8),0,dp(8));
            card.addView(text(sub.optString("name","订阅"),14,DARK));LinearLayout actions=row();Button update=button("更新订阅",false),del=button("删除订阅",false);pair(card,update,del);
            update.setOnClickListener(v->background("更新订阅",()->{store.replaceSubscription(id,NetFetch.text(sub.getString("url"),2_000_000));return "订阅更新完成";}));
            del.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("删除此订阅及其节点？").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                try{store.deleteSubscription(id);refreshNodeList();}catch(Exception e){error(e);}}).show());subscriptions.addView(card);
        }
    }
    private void addSubscriptionDialog(){LinearLayout fields=vertical();EditText name=new EditText(this),url=new EditText(this);name.setHint("订阅名称");url.setHint("HTTPS 订阅地址");url.setSingleLine(true);fields.addView(name);fields.addView(url);
        new AlertDialog.Builder(this).setTitle("添加订阅").setView(fields).setNegativeButton("取消",null).setPositiveButton("保存并更新",(d,w)->background("正在添加订阅",()->{
            String address=url.getText().toString().trim(),id=store.addSubscription(name.getText().toString(),address);store.replaceSubscription(id,NetFetch.text(address,2_000_000));return "订阅添加完成";})).show();}
    private interface Work{String run()throws Exception;}
    private void background(String label,Work work){if(busy)return;busy=true;refresh.setEnabled(false);detail.setText(label);
        new Thread(()->{String result;try{result=work.run();}catch(Throwable t){CrashDiary.record(this,label,t);result=label+"失败："+t;}final String outcome=result;
            runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);refreshNodeList();detail.setText(outcome);});},"f-io").start();}
    private void updateAll(){if(busy)return;busy=true;refresh.setEnabled(false);detail.setText("正在更新订阅、Geo 及检查正式版内核…");
        new Thread(()->{try{UpdateManager.Result result=UpdateManager.updateAll(this,store,info->runOnUiThread(()->detail.setText(info)));
            runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);refreshNodeList();appPolicy=null;scanApps();
                AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("更新结果").setMessage(result.asText()).setNegativeButton("关闭",null);
                if(result.appRelease!=null)dialog.setPositiveButton("安装正式版",(d,w)->installUpdate(result.appRelease));dialog.show();});
            }catch(Throwable t){runOnUiThread(()->{busy=false;refresh.setEnabled(true);operationError("更新",t);});}},"f-update").start();}
    private void installUpdate(JSONObject manifest){if(!getPackageManager().canRequestPackageInstalls()){
        pendingUpdate=manifest;startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),REQUEST_INSTALL);return;}
        background("下载并验证更新包",()->{UpdateManager.downloadAndInstall(this,manifest,info->runOnUiThread(()->detail.setText(info)));return "已打开系统安装器";});}
    private void requestExport(){new AlertDialog.Builder(this).setMessage("备份含节点密码和订阅地址，导出为未加密 JSON，请妥善保管。")
        .setNegativeButton("取消",null).setPositiveButton("导出",(d,w)->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"F-nodes-backup.json"),REQUEST_EXPORT)).show();}
    private void requestImport(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),REQUEST_IMPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==REQUEST_INSTALL){JSONObject update=pendingUpdate;pendingUpdate=null;if(update!=null&&getPackageManager().canRequestPackageInstalls())installUpdate(update);return;}
        if(result!=RESULT_OK)return;if(request==REQUEST_VPN){startVpn();return;}if(data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==REQUEST_EXPORT)background("导出备份",()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法创建文件");out.write(store.exportJson().getBytes(StandardCharsets.UTF_8));}return "备份已导出";});
        if(request==REQUEST_IMPORT)background("合并恢复",()->{try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("无法打开文件");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>8_000_000)throw new IOException("备份超过 8 MB");out.write(b,0,n);}
            return "已合并恢复 "+store.restoreMerge(out.toString("UTF-8"))+" 个节点";}});
    }
    private void operationError(String stage,Throwable t){CrashDiary.record(this,stage,t);detail.setText(stage+"失败："+t);}
    private void message(String value){new AlertDialog.Builder(this).setMessage(value).setPositiveButton("确定",null).show();}
    private void error(Exception e){operationError("操作",e);}
    private void fatal(String value){new AlertDialog.Builder(this).setTitle("启动失败").setMessage(value).setPositiveButton("关闭",(d,w)->finish()).show();}
}
