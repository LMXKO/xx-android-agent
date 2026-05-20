package com.lmx.xiaoxuanagent.runtime

sealed interface VerificationCommand {
    val label: String

    data class VerifySearchInput(val query: String) : VerificationCommand {
        override val label: String = "验证搜索输入"
    }

    data object VerifyResultClick : VerificationCommand {
        override val label: String = "验证结果页点击"
    }
}
