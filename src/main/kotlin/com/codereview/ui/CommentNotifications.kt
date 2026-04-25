package com.codereview.ui

import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager

object CommentNotifications {

    fun warn(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeReview.Notifications")
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }

    fun info(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeReview.Notifications")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
