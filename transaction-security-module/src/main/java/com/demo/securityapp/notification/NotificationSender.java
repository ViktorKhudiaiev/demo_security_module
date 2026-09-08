package com.demo.securityapp.notification;

@FunctionalInterface
public interface NotificationSender {
    void send(IncidentNotification notification) throws Exception;
}
