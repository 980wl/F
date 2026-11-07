package com.fproxy.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

public class FNotification {
    public static void show(Service service, int id, String text) {
        NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                "f-vpn-active", "F 连接与测速", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(
                service, id, new Intent(service, MainActivity.class), flags);
        PendingIntent stopIntent = PendingIntent.getService(
                service, id, new Intent(service, service.getClass()).setAction("STOP"), flags);

        Notification notification = new Notification.Builder(service, "f-vpn-active")
                .setContentTitle("F · sing-box")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_status, "停止", stopIntent).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            service.startForeground(id, notification);
        }
    }
}
