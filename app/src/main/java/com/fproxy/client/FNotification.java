package com.fproxy.client;
import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.content.pm.ServiceInfo;
public final class FNotification {
    public static void show(Service s,int id,String message){
        String channel="f-vpn-active";
        NotificationManager nm=(NotificationManager)s.getSystemService(Service.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(channel,"F 连接与测速",NotificationManager.IMPORTANCE_LOW));
        PendingIntent ui=PendingIntent.getActivity(s,id,new Intent(s,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(s,id,new Intent(s,s.getClass()).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(s,channel).setContentTitle("F · sing-box")
            .setContentText(message).setSmallIcon(R.drawable.ic_status).setContentIntent(ui).setOngoing(true)
            .addAction(new Notification.Action.Builder(R.drawable.ic_status,"停止",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)s.startForeground(id,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else s.startForeground(id,n);
    }
}
