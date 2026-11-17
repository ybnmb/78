package com.dafeiyu.paiguard.core

/**
 * 被保护的宿主应用。
 *
 * 元宝派运行在元宝 App 的「派」模块里，不同渠道包的包名会变，这里放已知的几个。
 * 如果你的版本不在列表里，用 `adb shell dumpsys activity | grep mResumedActivity`
 * 找到当前界面所属包名，往 [ALL] 里加一行，重新编译即可。
 */
object Targets {

    /** 元宝派（元宝 App 内的「派」） */
    const val YUANBAO_PAI = "com.tencent.hunyuan.app.chat"

    /** 元宝主客户端 */
    const val YUANBAO = "com.tencent.hunyuan.app"

    val ALL: Set<String> = linkedSetOf(YUANBAO_PAI, YUANBAO)

    fun matches(pkg: String?): Boolean = pkg != null && ALL.contains(pkg)
}
