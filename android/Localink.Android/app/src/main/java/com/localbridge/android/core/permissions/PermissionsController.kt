package com.localbridge.android.core.permissions

import android.Manifest
import android.os.Build

class PermissionsController {
    fun runtimeDangerousPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    fun managedPermissions(): List<AppPermission> {
        val items = mutableListOf(
            AppPermission(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    Manifest.permission.BLUETOOTH
                },
                label = "Bluetooth connect",
                description = "Needed for Localink Bluetooth fallback pairing and text sessions."
            ),
            AppPermission(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_SCAN
                } else {
                    Manifest.permission.BLUETOOTH_ADMIN
                },
                label = "Bluetooth scan",
                description = "Needed to list nearby or paired Bluetooth peers for the fallback mode."
            ),
            AppPermission(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_ADVERTISE
                } else {
                    Manifest.permission.BLUETOOTH
                },
                label = "Bluetooth advertise",
                description = "Needed for Localink to host the Android Bluetooth fallback listener."
            ),
            AppPermission(
                permission = Manifest.permission.ACCESS_WIFI_STATE,
                label = "Wi-Fi state",
                description = "Needed to understand hotspot and LAN context."
            ),
            AppPermission(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                label = "Precise location",
                description = "Helps Android 9+ expose nearby Wi-Fi and Bluetooth devices more reliably for local discovery."
            ),
            AppPermission(
                permission = Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                label = "Multicast",
                description = "Needed later for reliable LAN discovery traffic."
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items += AppPermission(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                label = "Notifications",
                description = "Needed so Localink can keep long transfers alive in the background with a foreground-service notification."
            )
            items += AppPermission(
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                label = "Nearby Wi-Fi",
                description = "Prepared for richer local network discovery on Android 13+."
            )
            items += AppPermission(
                permission = Manifest.permission.READ_MEDIA_IMAGES,
                label = "Images",
                description = "Prepared for image picking and image transfer flows."
            )
            items += AppPermission(
                permission = Manifest.permission.READ_MEDIA_VIDEO,
                label = "Video",
                description = "Prepared for light video picking and transfer flows."
            )
        } else {
            items += AppPermission(
                permission = Manifest.permission.READ_EXTERNAL_STORAGE,
                label = "Storage",
                description = "Prepared for selecting files on older Android versions."
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                items += AppPermission(
                    permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    label = "Download folder write",
                    description = "Needed on Android 9 so received files can also be mirrored into Download/Localink."
                )
            }
        }

        return items
    }
}
