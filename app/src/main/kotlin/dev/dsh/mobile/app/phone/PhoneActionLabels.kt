package dev.dsh.mobile.app.phone

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.toolbridge.phone.PhoneAction

/**
 * 手机动作词表的显示层映射。
 *
 * PhoneAction 在 toolbridge（design 不依赖 toolbridge），所以映射放 app 层。
 * when 穷尽：给 PhoneAction 加一个动作，这里漏了分支就编译不过，不会静默显示 id。
 */
@Composable
fun PhoneAction.title(): String = stringResource(
    when (this) {
        PhoneAction.Tap -> DesignR.string.act_tap_title
        PhoneAction.Swipe -> DesignR.string.act_swipe_title
        PhoneAction.Type -> DesignR.string.act_type_title
        PhoneAction.Key -> DesignR.string.act_key_title
        PhoneAction.Back -> DesignR.string.act_back_title
        PhoneAction.Home -> DesignR.string.act_home_title
        PhoneAction.Recents -> DesignR.string.act_recents_title
        PhoneAction.Scroll -> DesignR.string.act_scroll_title
        PhoneAction.Pinch -> DesignR.string.act_pinch_title
        PhoneAction.Drag -> DesignR.string.act_drag_title
        PhoneAction.ReadScreen -> DesignR.string.act_readscreen_title
        PhoneAction.ReadTree -> DesignR.string.act_tree_title
        PhoneAction.WaitFor -> DesignR.string.act_wait_for_title
        PhoneAction.CurrentApp -> DesignR.string.act_currentapp_title
        PhoneAction.Apps -> DesignR.string.act_apps_title
        PhoneAction.Sequence -> DesignR.string.act_sequence_title
        PhoneAction.OpenApp -> DesignR.string.act_openapp_title
        PhoneAction.Screenshot -> DesignR.string.act_screenshot_title
        PhoneAction.Contacts -> DesignR.string.act_contacts_title
        PhoneAction.SmsList -> DesignR.string.act_sms_list_title
        PhoneAction.SmsSend -> DesignR.string.act_sms_send_title
        PhoneAction.Dial -> DesignR.string.act_dial_title
        PhoneAction.Location -> DesignR.string.act_location_title
        PhoneAction.AudioRecord -> DesignR.string.act_audio_record_title
    },
)

@Composable
fun PhoneAction.hint(): String = stringResource(
    when (this) {
        PhoneAction.Tap -> DesignR.string.act_tap_hint
        PhoneAction.Swipe -> DesignR.string.act_swipe_hint
        PhoneAction.Type -> DesignR.string.act_type_hint
        PhoneAction.Key -> DesignR.string.act_key_hint
        PhoneAction.Back -> DesignR.string.act_back_hint
        PhoneAction.Home -> DesignR.string.act_home_hint
        PhoneAction.Recents -> DesignR.string.act_recents_hint
        PhoneAction.Scroll -> DesignR.string.act_scroll_hint
        PhoneAction.Pinch -> DesignR.string.act_pinch_hint
        PhoneAction.Drag -> DesignR.string.act_drag_hint
        PhoneAction.ReadScreen -> DesignR.string.act_readscreen_hint
        PhoneAction.ReadTree -> DesignR.string.act_tree_hint
        PhoneAction.WaitFor -> DesignR.string.act_wait_for_hint
        PhoneAction.CurrentApp -> DesignR.string.act_currentapp_hint
        PhoneAction.Apps -> DesignR.string.act_apps_hint
        PhoneAction.Sequence -> DesignR.string.act_sequence_hint
        PhoneAction.OpenApp -> DesignR.string.act_openapp_hint
        PhoneAction.Screenshot -> DesignR.string.act_screenshot_hint
        PhoneAction.Contacts -> DesignR.string.act_contacts_hint
        PhoneAction.SmsList -> DesignR.string.act_sms_list_hint
        PhoneAction.SmsSend -> DesignR.string.act_sms_send_hint
        PhoneAction.Dial -> DesignR.string.act_dial_hint
        PhoneAction.Location -> DesignR.string.act_location_hint
        PhoneAction.AudioRecord -> DesignR.string.act_audio_record_hint
    },
)
