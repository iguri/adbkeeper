package com.adbkeeper.core

import org.json.JSONObject

/** 通知渠道及其纯函数报文构造（便于单元测试）。 */
enum class Channel(val id: String, val label: String, val hint: String) {
    WECOM("wecom", "企业微信机器人", "群机器人 Webhook 地址"),
    DINGTALK("dingtalk", "钉钉机器人", "自定义机器人 Webhook（需关闭额外校验或用关键词）"),
    FEISHU("feishu", "飞书机器人", "自定义机器人 Webhook"),
    BARK("bark", "Bark", "形如 https://api.day.app/你的Key"),
    ;

    /** 生成该渠道的请求体。Bark 使用 title/body，其余使用文本消息。 */
    fun payload(title: String, content: String): String = when (this) {
        WECOM, DINGTALK -> JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().put("content", content))
        }.toString()
        FEISHU -> JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().put("text", content))
        }.toString()
        BARK -> JSONObject().apply {
            put("title", title)
            put("body", content)
        }.toString()
    }

    /** 各平台把业务错误塞进 HTTP 200 的情况。 */
    fun isBusinessError(body: String): Boolean {
        if (body.isBlank()) return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return when (this) {
            WECOM -> json.optInt("errcode", 0) != 0
            DINGTALK -> json.optInt("errcode", 0) != 0
            FEISHU -> json.optInt("code", 0) != 0
            BARK -> json.optInt("code", 200) != 200
        }
    }

    companion object {
        fun fromId(id: String): Channel = entries.firstOrNull { it.id == id } ?: WECOM
    }
}
