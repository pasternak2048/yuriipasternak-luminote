package com.yp.luminote.app.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log

internal object NotificationDiagnostics {

    private const val TAG =
        "LuminoteNotificationDiagnostic"

    fun log(
        sbn: StatusBarNotification
    ) {
        val notification =
            sbn.notification

        val extras =
            notification.extras

        val actions =
            notification.actions
                ?.mapNotNull { action ->
                    action.title
                        ?.toString()
                }
                .orEmpty()

        val extraKeys =
            extras.keySet()
                .sorted()

        Log.d(
            TAG,
            buildString {
                appendLine("----- Notification diagnostic -----")
                appendLine("package=${sbn.packageName}")
                appendLine("key=${sbn.key}")
                appendLine("id=${sbn.id}")
                appendLine("tag=${sbn.tag}")
                appendLine("category=${notification.category}")
                appendLine(
                    "flags=0x${notification.flags.toString(16)}"
                )
                appendLine("channelId=${notification.channelId}")
                appendLine("group=${notification.group}")
                appendLine("sortKey=${notification.sortKey}")
                appendLine(
                    "template=${
                        extras
                            .getString(
                                Notification.EXTRA_TEMPLATE
                            )
                    }"
                )
                appendLine(
                    "title=${
                        extras
                            .getCharSequence(
                                Notification.EXTRA_TITLE
                            )
                    }"
                )
                appendLine(
                    "text=${
                        extras
                            .getCharSequence(
                                Notification.EXTRA_TEXT
                            )
                    }"
                )
                appendLine(
                    "subText=${
                        extras
                            .getCharSequence(
                                Notification.EXTRA_SUB_TEXT
                            )
                    }"
                )
                appendLine(
                    "summaryText=${
                        extras
                            .getCharSequence(
                                Notification.EXTRA_SUMMARY_TEXT
                            )
                    }"
                )
                appendLine(
                    "infoText=${
                        extras
                            .getCharSequence(
                                Notification.EXTRA_INFO_TEXT
                            )
                    }"
                )
                appendLine(
                    "progress=${
                        extras.getInt(
                            Notification.EXTRA_PROGRESS,
                            0
                        )
                    }"
                )
                appendLine(
                    "progressMax=${
                        extras.getInt(
                            Notification.EXTRA_PROGRESS_MAX,
                            0
                        )
                    }"
                )
                appendLine(
                    "progressIndeterminate=${
                        extras.getBoolean(
                            Notification.EXTRA_PROGRESS_INDETERMINATE,
                            false
                        )
                    }"
                )
                appendLine(
                    "hasMediaSession=${
                        extras.containsKey(
                            Notification.EXTRA_MEDIA_SESSION
                        )
                    }"
                )
                appendLine("actions=$actions")
                appendLine("extraKeys=$extraKeys")
                append("-------------------------------")
            }
        )
    }
}