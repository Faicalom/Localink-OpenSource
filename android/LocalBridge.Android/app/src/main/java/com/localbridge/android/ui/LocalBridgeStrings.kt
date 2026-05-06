package com.localbridge.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.LayoutDirection
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.AppLanguage
import com.localbridge.android.models.ChatMessageStatus
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.LocalBridgeSettings
import com.localbridge.android.models.TransferDirection
import com.localbridge.android.models.TransferItem
import com.localbridge.android.models.TransferState
import java.util.Locale

@Immutable
class LocalBridgeStrings private constructor(
    private val language: AppLanguage,
    private val values: Map<String, String>
) {
    val layoutDirection: LayoutDirection
        get() = if (language == AppLanguage.Arabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    operator fun get(key: String): String = values[key] ?: key

    fun languageLabel(target: AppLanguage): String {
        return when (target) {
            AppLanguage.English -> this["language_english"]
            AppLanguage.Arabic -> this["language_arabic"]
        }
    }

    fun modeLabel(mode: AppConnectionMode): String {
        return when (mode) {
            AppConnectionMode.LocalWifiLan -> this["mode_lan"]
            AppConnectionMode.BluetoothFallback -> this["mode_bluetooth"]
        }
    }

    fun connectionLifecycleLabel(
        state: ConnectionLifecycleState,
        isScanning: Boolean
    ): String {
        if (isScanning && state in setOf(ConnectionLifecycleState.Idle, ConnectionLifecycleState.Disconnected)) {
            return this["state_discovering"]
        }

        return when (state) {
            ConnectionLifecycleState.Idle -> this["state_idle"]
            ConnectionLifecycleState.Discovering -> this["state_discovering"]
            ConnectionLifecycleState.Connecting -> this["state_connecting"]
            ConnectionLifecycleState.WaitingForPairing -> this["state_waiting_pairing"]
            ConnectionLifecycleState.Paired -> this["state_paired"]
            ConnectionLifecycleState.Connected -> this["state_connected"]
            ConnectionLifecycleState.TransferInProgress -> this["state_transfer_in_progress"]
            ConnectionLifecycleState.Disconnected -> this["state_disconnected"]
            ConnectionLifecycleState.Failed -> this["state_failed"]
        }
    }

    fun chatStatus(status: ChatMessageStatus): String {
        return when (status) {
            ChatMessageStatus.Sending -> this["chat_status_sending"]
            ChatMessageStatus.Sent -> this["chat_status_sent"]
            ChatMessageStatus.Delivered -> this["chat_status_delivered"]
            ChatMessageStatus.Failed -> this["chat_status_failed"]
        }
    }

    fun transferStatus(status: TransferState): String {
        return when (status) {
            TransferState.Queued -> this["transfer_status_queued"]
            TransferState.Preparing -> this["transfer_status_preparing"]
            TransferState.Sending -> this["transfer_status_sending"]
            TransferState.Receiving -> this["transfer_status_receiving"]
            TransferState.Paused -> this["transfer_status_paused"]
            TransferState.Completed -> this["transfer_status_completed"]
            TransferState.Failed -> this["transfer_status_failed"]
            TransferState.Canceled -> this["transfer_status_canceled"]
        }
    }

    fun transferDirection(direction: TransferDirection): String {
        return if (direction == TransferDirection.Outgoing) this["direction_outgoing"] else this["direction_incoming"]
    }

    fun transferPreviewFallback(kind: String, mimeType: String, fileName: String): String {
        return when {
            kind.equals("image", ignoreCase = true) -> this["preview_image_unavailable"]
            kind.equals("video", ignoreCase = true) -> this["preview_video_file"]
            kind.equals("document", ignoreCase = true) || mimeType == "application/pdf" -> this["preview_pdf_document"]
            kind.equals("text", ignoreCase = true) || mimeType.startsWith("text/", ignoreCase = true) -> this["preview_text_file"]
            else -> {
                val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase(Locale.US)
                if (extension.isNullOrBlank()) {
                    this["preview_generic_file"]
                } else {
                    "$extension ${this["preview_file_suffix"]}"
                }
            }
        }
    }

    fun speedLabel(bytesPerSecond: Long): String {
        return if (bytesPerSecond <= 0L) {
            this["speed_unknown"]
        } else {
            "${this["speed_prefix"]} ${TransferItem.formatBytes(bytesPerSecond)}/s"
        }
    }

    fun etaLabel(estimatedSecondsRemaining: Long?): String {
        return estimatedSecondsRemaining
            ?.takeIf { it > 0L }
            ?.let { seconds ->
                val minutes = seconds / 60
                val remainder = seconds % 60
                "${this["eta_prefix"]} %02d:%02d".format(localeForFormatting(), minutes, remainder)
            } ?: this["eta_unknown"]
    }

    fun fileLimitLabel(limitBytes: Long): String {
        val gigaBytes = limitBytes / (1024L * 1024L * 1024L)
        return "$gigaBytes GB"
    }

    fun trustedLabel(isTrusted: Boolean): String = if (isTrusted) this["trusted"] else this["untrusted"]

    fun pairingLabel(pairingRequired: Boolean): String = if (pairingRequired) this["pairing_required"] else this["quick_reconnect"]

    fun supportedModesLabel(modes: List<String>): String {
        val labels = modes.map { mode ->
            when (mode.lowercase(Locale.ROOT)) {
                "local-lan", "wifi_lan", "local_lan" -> this["mode_lan"]
                "bluetooth-fallback", "bluetooth_fallback" -> this["mode_bluetooth"]
                else -> mode
            }
        }
        return labels.joinToString(", ").ifBlank { this["none"] }
    }

    fun receiveFolderHeadline(settings: LocalBridgeSettings): String {
        return if (settings.hasExternalReceiveFolder) {
            "${this["external_folder_prefix"]} ${settings.receiveTreeDisplayName ?: this["picked_saf_directory"]}"
        } else {
            "${this["private_storage_prefix"]} ${settings.receiveFolderLabel}"
        }
    }

    fun activeReceiveLocationDescription(
        settings: LocalBridgeSettings,
        fallbackPath: String,
        publicDownloadsPath: String
    ): String {
        return if (settings.hasExternalReceiveFolder) {
            "${this["external_folder_prefix"]} ${settings.receiveTreeDisplayName ?: settings.receiveTreeUri.orEmpty()} · ${this["download_mirror_prefix"]} $publicDownloadsPath · ${this["fallback_prefix"]} $fallbackPath"
        } else {
            "${this["download_mirror_prefix"]} $publicDownloadsPath · ${this["private_storage_prefix"]} $fallbackPath"
        }
    }

    fun boolLabel(value: Boolean): String = if (value) this["enabled"] else this["disabled"]

    fun foundWindowsPeers(count: Int): String = "${this["found_windows_peers_prefix"]} $count ${this["found_windows_peers_suffix"]}"

    fun couldNotOpen(fileName: String): String = "${this["could_not_open"]} $fileName."

    fun couldNotShare(fileName: String): String = "${this["could_not_share"]} $fileName."

    private fun localeForFormatting(): Locale {
        return if (language == AppLanguage.Arabic) Locale("ar") else Locale.US
    }

    companion object {
        fun forLanguage(language: AppLanguage): LocalBridgeStrings {
            return LocalBridgeStrings(
                language = language,
                values = if (language == AppLanguage.Arabic) arabicValues else englishValues
            )
        }

        private val englishValues = mapOf(
            "nav_home" to "Home",
            "nav_devices" to "Devices",
            "nav_chat" to "Chat",
            "nav_transfers" to "Transfers",
            "nav_settings" to "Settings",
            "app_subtitle" to "Offline sharing between Windows and Android",
            "mode_lan" to "Local Wi-Fi / Hotspot",
            "mode_bluetooth" to "Bluetooth",
            "state_idle" to "Idle",
            "state_discovering" to "Discovering",
            "state_connecting" to "Connecting",
            "state_waiting_pairing" to "Waiting for pairing",
            "state_paired" to "Paired",
            "state_connected" to "Connected",
            "state_transfer_in_progress" to "Transfer in progress",
            "state_disconnected" to "Disconnected",
            "state_failed" to "Failed",
            "language_english" to "English",
            "language_arabic" to "Arabic",
            "home_connection_title" to "Connection",
            "home_scanning_headline" to "Scanning the hotspot/LAN...",
            "home_status_supporting" to "Protocol",
            "discovered_peers" to "discovered peer(s)",
            "trusted_devices_count" to "trusted device(s)",
            "open_devices" to "Devices",
            "open_chat" to "Chat",
            "open_transfers" to "Transfers",
            "preferred_mode_title" to "Preferred mode",
            "receive_folder_title" to "Receive folder",
            "active_peer_title" to "Active peer",
            "no_active_peer" to "No active peer",
            "pairing_title" to "First-time pairing",
            "pairing_value" to "Use the Windows pairing code",
            "pairing_supporting" to "Open Devices, enter the Windows code, then trust the peer after the first successful connection.",
            "continue_lan_discovery" to "Continue with LAN discovery",
            "devices_title" to "Discovered Devices",
            "devices_hint" to "Use LAN or hotspot for speed. Bluetooth works best for chat and small files.",
            "discovery_title" to "Discovery",
            "discovery_scanning" to "Scanning hotspot/LAN",
            "discovery_listening" to "Listening for peers",
            "discovery_stopped" to "Stopped",
            "connection_title" to "Connection",
            "scanning_for_peers" to "Scanning for compatible Windows peers...",
            "negotiating_handshake" to "Negotiating the Localink handshake...",
            "pairing_card_title" to "Pairing",
            "pairing_card_headline" to "First-time confirmation required",
            "pairing_steps" to "1. Open the Windows Localink app.\n2. Copy the six-digit pairing code shown there.\n3. Paste it here, then tap Connect again.",
            "trust_pairing_title" to "Trust and pairing",
            "trust_pairing_headline" to "Manual first connection",
            "trust_pairing_supporting" to "After the first successful pairing, keep the peer trusted for quicker reconnects.",
            "android_pairing_code_supporting" to "Share this code with Windows when it starts a Bluetooth connection.",
            "pairing_token" to "Pairing token",
            "pairing_placeholder" to "Enter the code shown on Windows",
            "make_bluetooth_discoverable" to "Make phone discoverable",
            "make_bluetooth_discoverable_supporting" to "Use this before connecting from Windows over Bluetooth. Android will ask for permission and usually keeps the phone visible for about 5 minutes.",
            "scanning_button" to "Scanning...",
            "refresh_lan_peers" to "Refresh LAN peers",
            "found_windows_peers_prefix" to "Found",
            "found_windows_peers_suffix" to "Windows peer(s)",
            "no_windows_peers_title" to "Found devices",
            "no_windows_peers_headline" to "No Windows peers yet",
            "no_windows_peers_supporting" to "Keep both devices on the same hotspot/LAN, then refresh discovery.",
            "chat_title" to "Chat",
            "messaging_title" to "Messaging",
            "messaging_supporting" to "Messages stay local between this phone and the connected Windows device.",
            "chat_empty_title" to "Conversation",
            "chat_empty_headline" to "No messages yet",
            "chat_empty_supporting" to "Connect to Windows, then send your first local message.",
            "message_label" to "Message",
            "message_placeholder" to "Type a local protocol test message",
            "send" to "Send",
            "retry" to "Retry",
            "copy" to "Copy",
            "message_copied" to "Message copied.",
            "transfers_title" to "Transfers",
            "transfers_supporting" to "Send files over LAN or hotspot. Bluetooth is best kept for small transfers. Limit:",
            "receive_folder_card_title" to "Receive folder",
            "select_files" to "Select images or files",
            "connect_to_send" to "Connect to Windows to send files",
            "no_transfers" to "No transfers yet. Pick files after pairing with the Windows app.",
            "settings_title" to "Settings",
            "theme_storage_title" to "Theme and storage",
            "theme_storage_supporting" to "Choose the app look and control where received files are stored.",
            "device_alias" to "Device alias",
            "fallback_subfolder" to "Fallback private subfolder",
            "fallback_subfolder_supporting" to "Used whenever no external SAF folder is selected or if external saving becomes unavailable.",
            "active_receive_destination" to "Active receive destination",
            "active_receive_destination_supporting" to "Received files use your chosen folder when available, with a safe in-app fallback.",
            "private_fallback_path" to "Private fallback path",
            "private_fallback_path_supporting" to "Used whenever external storage is not available.",
            "external_receive_folder" to "External receive folder",
            "not_selected" to "Not selected",
            "external_receive_selected" to "Selected with Android's folder picker for future receives.",
            "external_receive_unselected" to "Choose a folder if you want received files outside app-private storage.",
            "pick_folder" to "Pick folder",
            "use_fallback" to "Use fallback",
            "modern_android_note" to "Modern Android note",
            "modern_android_value" to "SAF is the user-friendly external option",
            "modern_android_supporting" to "If Android revokes folder access, Localink falls back to private storage automatically.",
            "transfer_size_note" to "Transfer size note",
            "transfer_size_supporting" to "This build supports files up to 20 GB over LAN. Actual speed still depends on storage and route stability.",
            "preferred_mode_supporting" to "LAN or hotspot is the recommended route for full transfers.",
            "bluetooth_android_note_title" to "Bluetooth note",
            "bluetooth_android_note_value" to "Bluetooth fallback supports pairing and text chat",
            "bluetooth_android_note_supporting" to "Use LAN for the best speed. Bluetooth stays slower by design.",
            "dark_theme" to "Dark theme",
            "enabled" to "Enabled",
            "disabled" to "Disabled",
            "history_title" to "History",
            "clear_histories_title" to "Clear local histories",
            "clear_histories_value" to "Removes saved chat messages and completed/failed/canceled transfer records only.",
            "clear_histories_supporting" to "Saved files stay on disk. Active transfers are not removed.",
            "clear_chat" to "Clear chat",
            "clear_transfers" to "Clear transfers",
            "trusted_devices" to "Trusted devices",
            "no_trusted_devices_title" to "No trusted devices yet",
            "no_trusted_devices_value" to "Trust a Windows peer from the Devices screen after first pairing.",
            "remove" to "Remove",
            "permissions_title" to "Permissions",
            "granted" to "Granted",
            "not_granted" to "Not granted",
            "request_permissions_title" to "Request prepared permissions",
            "request_permissions_value" to "Tap to open runtime request flow",
            "request" to "Request",
            "recent_logs" to "Recent logs",
            "app_language" to "App language",
            "app_language_supporting" to "Switch between Arabic and English for the Android UI. Technical diagnostics may stay in English.",
            "theme_light" to "Light",
            "theme_dark" to "Dark",
            "clear_all" to "Clear all",
            "trusted" to "Trusted",
            "untrusted" to "Untrusted",
            "pairing_required" to "Pairing required",
            "quick_reconnect" to "Quick reconnect",
            "disconnect" to "Disconnect",
            "connect" to "Connect",
            "trust" to "Trust",
            "remove_trust" to "Remove trust",
            "modes_prefix" to "Modes:",
            "last_seen_prefix" to "Last seen:",
            "none" to "none",
            "open" to "Open",
            "share" to "Share",
            "pause" to "Pause",
            "resume" to "Resume",
            "cancel" to "Cancel",
            "chat_status_sending" to "Sending",
            "chat_status_sent" to "Queued",
            "chat_status_delivered" to "Delivered",
            "chat_status_failed" to "Failed",
            "transfer_status_queued" to "Queued",
            "transfer_status_preparing" to "Preparing",
            "transfer_status_sending" to "Sending",
            "transfer_status_receiving" to "Receiving",
            "transfer_status_paused" to "Paused",
            "transfer_status_completed" to "Completed",
            "transfer_status_failed" to "Failed",
            "transfer_status_canceled" to "Canceled",
            "direction_outgoing" to "Outgoing",
            "direction_incoming" to "Incoming",
            "preview_image_unavailable" to "Image preview unavailable",
            "preview_video_file" to "Video file",
            "preview_pdf_document" to "PDF document",
            "preview_text_file" to "Text file",
            "preview_generic_file" to "Generic file",
            "preview_file_suffix" to "file",
            "speed_prefix" to "Speed",
            "speed_unknown" to "Speed --",
            "eta_prefix" to "ETA",
            "eta_unknown" to "ETA --",
            "external_folder_prefix" to "External folder /",
            "download_mirror_prefix" to "Download mirror /",
            "private_storage_prefix" to "App private storage /",
            "fallback_prefix" to "Fallback",
            "picked_saf_directory" to "Picked SAF directory",
            "could_not_open" to "Could not open",
            "could_not_share" to "Could not share"
        )

        private val arabicValues = mapOf(
            "nav_home" to "الرئيسية",
            "nav_devices" to "الأجهزة",
            "nav_chat" to "الدردشة",
            "nav_transfers" to "النقل",
            "nav_settings" to "الإعدادات",
            "app_subtitle" to "مشاركة محلية بين ويندوز وأندرويد بدون إنترنت",
            "mode_lan" to "واي فاي محلي / نقطة اتصال",
            "mode_bluetooth" to "بلوتوث",
            "state_idle" to "خامل",
            "state_discovering" to "جارٍ الاكتشاف",
            "state_connecting" to "جارٍ الاتصال",
            "state_waiting_pairing" to "بانتظار الاقتران",
            "state_paired" to "مقترن",
            "state_connected" to "متصل",
            "state_transfer_in_progress" to "نقل جارٍ",
            "state_disconnected" to "غير متصل",
            "state_failed" to "فشل",
            "language_english" to "English",
            "language_arabic" to "العربية",
            "home_connection_title" to "الاتصال",
            "home_scanning_headline" to "جارٍ فحص نقطة الاتصال / الشبكة المحلية...",
            "home_status_supporting" to "البروتوكول",
            "discovered_peers" to "جهاز/أجهزة مكتشفة",
            "trusted_devices_count" to "جهاز/أجهزة موثوقة",
            "open_devices" to "الأجهزة",
            "open_chat" to "الدردشة",
            "open_transfers" to "النقل",
            "preferred_mode_title" to "الوضع المفضل",
            "receive_folder_title" to "مجلد الاستقبال",
            "active_peer_title" to "الجهاز النشط",
            "no_active_peer" to "لا يوجد جهاز نشط",
            "pairing_title" to "الاقتران لأول مرة",
            "pairing_value" to "استخدم رمز الاقتران الظاهر في ويندوز",
            "pairing_supporting" to "افتح الأجهزة، أدخل الرمز الظاهر في ويندوز، ثم اجعل الجهاز موثوقًا بعد أول اتصال ناجح.",
            "continue_lan_discovery" to "تابع اكتشاف الشبكة المحلية",
            "devices_title" to "الأجهزة المكتشفة",
            "devices_hint" to "استخدم الشبكة المحلية أو نقطة الاتصال للسرعة. البلوتوث مناسب أكثر للدردشة والملفات الصغيرة.",
            "discovery_title" to "الاكتشاف",
            "discovery_scanning" to "جارٍ فحص الشبكة المحلية",
            "discovery_listening" to "يستمع للأجهزة",
            "discovery_stopped" to "متوقف",
            "connection_title" to "الاتصال",
            "scanning_for_peers" to "جارٍ البحث عن أجهزة ويندوز المتوافقة...",
            "negotiating_handshake" to "جارٍ التفاوض على مصافحة Localink...",
            "pairing_card_title" to "الاقتران",
            "pairing_card_headline" to "يلزم تأكيد أول اتصال",
            "pairing_steps" to "1. افتح تطبيق Localink على ويندوز.\n2. انسخ رمز الاقتران المكوّن من ستة أرقام.\n3. الصقه هنا ثم اضغط اتصال مرة أخرى.",
            "trust_pairing_title" to "الثقة والاقتران",
            "trust_pairing_headline" to "أول اتصال يدوي",
            "trust_pairing_supporting" to "بعد أول اقتران ناجح يمكنك إبقاء جهاز ويندوز موثوقًا لتسهيل إعادة الاتصال.",
            "android_pairing_code_supporting" to "أعطِ هذا الرمز لويندوز عندما يبدأ اتصال البلوتوث.",
            "pairing_token" to "رمز الاقتران",
            "pairing_placeholder" to "أدخل الرمز الظاهر على ويندوز",
            "make_bluetooth_discoverable" to "اجعل الهاتف ظاهرًا بالبلوتوث",
            "make_bluetooth_discoverable_supporting" to "استخدم هذا قبل الاتصال من ويندوز عبر البلوتوث. سيطلب أندرويد السماح ويبقى الهاتف ظاهرًا عادةً لحوالي 5 دقائق.",
            "scanning_button" to "جارٍ الفحص...",
            "refresh_lan_peers" to "تحديث أجهزة الشبكة المحلية",
            "found_windows_peers_prefix" to "تم العثور على",
            "found_windows_peers_suffix" to "جهاز/أجهزة ويندوز",
            "no_windows_peers_title" to "الأجهزة الموجودة",
            "no_windows_peers_headline" to "لا توجد أجهزة ويندوز بعد",
            "no_windows_peers_supporting" to "أبقِ الهاتف والكمبيوتر على نفس نقطة الاتصال أو الشبكة ثم أعد التحديث.",
            "chat_title" to "الدردشة",
            "messaging_title" to "الرسائل",
            "messaging_supporting" to "الرسائل تبقى محلية بين هذا الهاتف وجهاز ويندوز المتصل.",
            "chat_empty_title" to "المحادثة",
            "chat_empty_headline" to "لا توجد رسائل بعد",
            "chat_empty_supporting" to "اتصل بويندوز ثم أرسل أول رسالة محلية.",
            "message_label" to "الرسالة",
            "message_placeholder" to "اكتب رسالة اختبار محلية",
            "send" to "إرسال",
            "retry" to "إعادة المحاولة",
            "copy" to "نسخ",
            "message_copied" to "تم نسخ الرسالة.",
            "transfers_title" to "النقل",
            "transfers_supporting" to "أرسل الملفات عبر الشبكة المحلية أو نقطة الاتصال. البلوتوث مناسب أكثر للملفات الصغيرة. الحد:",
            "receive_folder_card_title" to "مجلد الاستقبال",
            "select_files" to "اختيار صور أو ملفات",
            "connect_to_send" to "اتصل بويندوز لإرسال الملفات",
            "no_transfers" to "لا توجد عمليات نقل بعد. اختر ملفات بعد الاقتران مع تطبيق ويندوز.",
            "settings_title" to "الإعدادات",
            "theme_storage_title" to "المظهر والتخزين",
            "theme_storage_supporting" to "اختر شكل التطبيق وحدد مكان حفظ الملفات المستلمة.",
            "device_alias" to "اسم الجهاز",
            "fallback_subfolder" to "المجلد الاحتياطي الخاص",
            "fallback_subfolder_supporting" to "يُستخدَم عندما لا يتم اختيار مجلد SAF خارجي أو إذا أصبح الحفظ الخارجي غير متاح.",
            "active_receive_destination" to "وجهة الاستقبال الحالية",
            "active_receive_destination_supporting" to "تستخدم الملفات المجلد الذي اخترته إن كان متاحًا، مع احتياط داخلي آمن.",
            "private_fallback_path" to "مسار الاحتياط الخاص",
            "private_fallback_path_supporting" to "يُستخدم هذا المسار عندما لا يتاح الحفظ الخارجي.",
            "external_receive_folder" to "مجلد الاستقبال الخارجي",
            "not_selected" to "غير محدد",
            "external_receive_selected" to "تم اختياره عبر منتقي المجلدات وسيُستخدم للاستقبال القادم.",
            "external_receive_unselected" to "اختر مجلدًا إذا أردت حفظ الملفات خارج تخزين التطبيق.",
            "pick_folder" to "اختيار مجلد",
            "use_fallback" to "استخدام الاحتياط",
            "modern_android_note" to "ملاحظة أندرويد الحديثة",
            "modern_android_value" to "SAF هو الخيار الخارجي الأكثر عملية للمستخدم",
            "modern_android_supporting" to "إذا سحب أندرويد صلاحية المجلد الخارجي يعود Localink تلقائيًا إلى التخزين الخاص.",
            "transfer_size_note" to "ملاحظة حجم النقل",
            "transfer_size_supporting" to "هذه النسخة تدعم ملفات حتى 20 جيجابايت عبر الشبكة المحلية، والسرعة تعتمد على التخزين وثبات الاتصال.",
            "preferred_mode_supporting" to "الشبكة المحلية أو نقطة الاتصال هي الطريق الأفضل للنقل الكامل.",
            "bluetooth_android_note_title" to "ملاحظة البلوتوث",
            "bluetooth_android_note_value" to "البلوتوث الاحتياطي يدعم الآن الاقتران والدردشة النصية",
            "bluetooth_android_note_supporting" to "استخدم الشبكة المحلية لأفضل سرعة. البلوتوث أبطأ بطبيعته.",
            "dark_theme" to "المظهر الداكن",
            "enabled" to "مفعّل",
            "disabled" to "معطّل",
            "history_title" to "السجل",
            "clear_histories_title" to "مسح السجلات المحلية",
            "clear_histories_value" to "يمسح الرسائل المحفوظة وسجلات النقل المكتملة أو الفاشلة أو الملغاة فقط.",
            "clear_histories_supporting" to "الملفات المحفوظة تبقى في مكانها. عمليات النقل النشطة لا تُمسح.",
            "clear_chat" to "مسح الدردشة",
            "clear_transfers" to "مسح النقل",
            "trusted_devices" to "الأجهزة الموثوقة",
            "no_trusted_devices_title" to "لا توجد أجهزة موثوقة بعد",
            "no_trusted_devices_value" to "اجعل جهاز ويندوز موثوقًا من شاشة الأجهزة بعد أول اقتران.",
            "remove" to "إزالة",
            "permissions_title" to "الأذونات",
            "granted" to "مسموح",
            "not_granted" to "غير مسموح",
            "request_permissions_title" to "طلب الأذونات الجاهزة",
            "request_permissions_value" to "اضغط لفتح طلب الأذونات وقت التشغيل",
            "request" to "طلب",
            "recent_logs" to "أحدث السجلات",
            "app_language" to "لغة التطبيق",
            "app_language_supporting" to "بدّل بين العربية والإنجليزية لواجهة أندرويد. رسائل التشخيص التقنية قد تبقى بالإنجليزية.",
            "theme_light" to "نهاري",
            "theme_dark" to "ليلي",
            "clear_all" to "مسح الكل",
            "trusted" to "موثوق",
            "untrusted" to "غير موثوق",
            "pairing_required" to "يلزم اقتران",
            "quick_reconnect" to "إعادة اتصال سريعة",
            "disconnect" to "قطع الاتصال",
            "connect" to "اتصال",
            "trust" to "وثوق",
            "remove_trust" to "إزالة الثقة",
            "modes_prefix" to "الأنماط:",
            "last_seen_prefix" to "آخر ظهور:",
            "none" to "لا يوجد",
            "open" to "فتح",
            "share" to "مشاركة",
            "pause" to "إيقاف مؤقت",
            "resume" to "استئناف",
            "cancel" to "إلغاء",
            "chat_status_sending" to "جارٍ الإرسال",
            "chat_status_sent" to "في الانتظار",
            "chat_status_delivered" to "تم التسليم",
            "chat_status_failed" to "فشل",
            "transfer_status_queued" to "في الانتظار",
            "transfer_status_preparing" to "جارٍ التحضير",
            "transfer_status_sending" to "جارٍ الإرسال",
            "transfer_status_receiving" to "جارٍ الاستقبال",
            "transfer_status_paused" to "متوقف مؤقتًا",
            "transfer_status_completed" to "اكتمل",
            "transfer_status_failed" to "فشل",
            "transfer_status_canceled" to "أُلغي",
            "direction_outgoing" to "صادر",
            "direction_incoming" to "وارد",
            "preview_image_unavailable" to "معاينة الصورة غير متاحة",
            "preview_video_file" to "ملف فيديو",
            "preview_pdf_document" to "مستند PDF",
            "preview_text_file" to "ملف نصي",
            "preview_generic_file" to "ملف عام",
            "preview_file_suffix" to "ملف",
            "speed_prefix" to "السرعة",
            "speed_unknown" to "السرعة --",
            "eta_prefix" to "الوقت المتبقي",
            "eta_unknown" to "الوقت المتبقي --",
            "external_folder_prefix" to "مجلد خارجي /",
            "download_mirror_prefix" to "نسخة Download /",
            "private_storage_prefix" to "تخزين التطبيق الخاص /",
            "fallback_prefix" to "الاحتياط",
            "picked_saf_directory" to "مجلد SAF مختار",
            "could_not_open" to "تعذّر فتح",
            "could_not_share" to "تعذّرت مشاركة"
        )
    }
}

val LocalAppStrings = staticCompositionLocalOf { LocalBridgeStrings.forLanguage(AppLanguage.English) }

@Composable
fun rememberLocalBridgeStrings(language: AppLanguage): LocalBridgeStrings {
    return remember(language) {
        LocalBridgeStrings.forLanguage(language)
    }
}
