package li.lyc.lzuapp.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 协议版本：修订时 +1，老用户登录时需重新滑到底确认（存 "学号|版本" 比对） */
const val AGREEMENT_VERSION = 2

/** 用户协议正文（登录页弹窗与说明入口共用） */
const val USER_AGREEMENT = """
LZUapp 用户协议

更新日期：2026 年 10 月

欢迎使用 LZUapp（以下简称"本应用"）。在使用本应用前，请你务必认真阅读并充分理解本协议的全部内容。你点击"同意"或使用本应用，即视为你已阅读、理解并同意接受本协议的全部条款。

一、应用性质

1. 本应用是由个人开发者独立开发的第三方工具，并非兰州大学官方应用，与兰州大学及其任何部门没有任何隶属、授权或合作关系。
2. 本应用的课程表、校园卡、查寝、校园网等功能，均通过调用兰州大学相关校内系统面向其本人用户开放的接口实现，仅为你本人查询和管理自己的信息提供便利。
3. 本应用不代表学校立场，应用内展示的一切信息以学校官方系统为准。

二、账号与凭据

1. 你使用本应用需要提供兰州大学统一身份认证账号（学号/密码）、上网账号密码等凭据。
2. 上述凭据仅用于在你本机与学校官方系统之间完成登录认证，仅加密存储于你本人的设备本地，不会上传至任何第三方服务器。
3. 你应妥善保管自己的账号与设备。因你主动泄露账号密码、出借设备或设备丢失等原因造成的一切后果，由你自行承担。

三、数据与隐私

1. 本应用不收集、不存储、不分析、不分享你的任何个人信息与使用数据。
2. 本应用不含任何第三方统计、广告或追踪组件。
3. 你的课表、账单、签到等数据仅缓存在你本机，用于离线展示；你可以随时通过退出登录清除全部本地数据。
4. 查寝签到照片库中的照片由你本人主动添加，仅存储于你本机，一键签到时在本机进行扰动处理后直接提交学校系统，不经过任何第三方服务器。
5. 本应用与学校官方系统之间的数据传输遵循相关系统自身的安全策略，本应用不对传输链路之外的环节做任何干预。

四、使用责任与风险

1. 你使用本应用的一切行为及后果（包括但不限于账号异常、登录失败、数据错误、功能失效、设备问题等）由你自行承担，开发者不承担任何责任。
2. 本应用的校园卡付款码、充值等功能涉及你的资金安全，请自行核对金额与交易记录。因使用本应用产生的任何资金损失，由你自行承担。
3. 本应用的查寝签到、校园网登录等功能仅作为便利工具，不能替代学校规定的正式流程。因使用本应用导致的漏签、错签、网络异常等问题，由你自行承担相应后果。
4. 本应用的"一键签到"属于签到辅助功能：签到位置在你本人设定或任务给定的签到范围内随机选取，签到照片由你本人事先添加并确保真实有效。你应确保使用本功能时本人处于签到现场；如将本功能用于虚假签到等违反校规校纪的用途，一切后果由你自行承担，与开发者无关。
5. 学校系统接口、认证方式或政策发生变更时，本应用的部分或全部功能可能失效，开发者不保证功能的持续可用性。

五、禁止行为

使用本应用时，你不得：
1. 将本应用用于任何违法、违规或违反兰州大学校规校纪的活动；
2. 使用他人账号登录，或以任何方式获取、传播他人的个人信息与数据；
3. 利用本应用对学校系统进行攻击、渗透、高频请求或其他破坏性行为；
4. 将本应用或其修改版本用于商业用途；
5. 对本应用进行反向工程后用于任何非法用途。

如因你的上述行为导致任何法律责任、学校处分或第三方损失，均由你独立承担，与开发者无关。

六、免责声明

1. 本应用按"现状"提供，开发者不对其功能的准确性、及时性、稳定性作任何明示或默示的保证。
2. 在法律允许的最大范围内，开发者不对因使用或无法使用本应用而产生的任何直接、间接损失承担责任。
3. 你继续使用本应用即视为你理解并接受上述全部风险。

七、协议的变更与终止

1. 本协议可能随应用版本更新而修订，修订后将在登录页重新向你展示。
2. 如你不同意本协议的任何条款，请立即停止使用并卸载本应用。
3. 你可以随时通过卸载本应用终止使用；退出登录将清除本机保存的全部凭据与数据。

八、其他

1. 本协议的解释权归开发者所有。
2. 本协议自你点击"同意"之日起生效。
"""

/**
 * 用户协议弹窗：[requireScrollToEnd] 为 true 时需滑动到底部才能点同意；
 * 仅查看（协议入口）时直接可关闭。
 */
@Composable
fun AgreementDialog(
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    // 滑到底部（canScrollForward=false）才允许确认
    val reachedEnd by remember { derivedStateOf { !scrollState.canScrollForward } }
    val needScroll = onConfirm != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户协议", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState),
                ) {
                    Text(
                        USER_AGREEMENT.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (needScroll && !reachedEnd) {
                    Text(
                        "请滑动到底部阅读全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (onConfirm != null) {
                TextButton(onClick = onConfirm, enabled = reachedEnd) {
                    Text("同意并继续")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (onConfirm != null) {
                TextButton(onClick = onDismiss) { Text("不同意") }
            }
        },
    )
}
