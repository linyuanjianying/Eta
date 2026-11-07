package io.github.mangi.eta.ui.screens.prompts

import android.content.Context
import android.widget.Toast

/** 轻提示：保存失败、组合套用这类一次性反馈，不值得单独做状态。 */
internal fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
