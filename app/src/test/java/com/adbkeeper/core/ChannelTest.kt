package com.adbkeeper.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTest {

    @Test
    fun `wecom and dingtalk share text payload`() {
        val payload = JSONObject(Channel.WECOM.payload("标题", "内容"))
        assertEquals("text", payload.getString("msgtype"))
        assertEquals("内容", payload.getJSONObject("text").getString("content"))
        assertEquals(payload.toString(), Channel.DINGTALK.payload("标题", "内容"))
    }

    @Test
    fun `feishu payload uses msg_type`() {
        val payload = JSONObject(Channel.FEISHU.payload("标题", "内容"))
        assertEquals("text", payload.getString("msg_type"))
        assertEquals("内容", payload.getJSONObject("content").getString("text"))
    }

    @Test
    fun `bark payload carries title and body`() {
        val payload = JSONObject(Channel.BARK.payload("标题", "内容"))
        assertEquals("标题", payload.getString("title"))
        assertEquals("内容", payload.getString("body"))
    }

    @Test
    fun `business errors recognised per channel`() {
        assertTrue(Channel.WECOM.isBusinessError("""{"errcode":93000}"""))
        assertFalse(Channel.WECOM.isBusinessError("""{"errcode":0}"""))
        assertTrue(Channel.FEISHU.isBusinessError("""{"code":19001}"""))
        assertFalse(Channel.FEISHU.isBusinessError("""{"code":0}"""))
        assertTrue(Channel.BARK.isBusinessError("""{"code":400}"""))
        assertFalse(Channel.BARK.isBusinessError("""{"code":200}"""))
        assertFalse(Channel.WECOM.isBusinessError("not json"))
    }

    @Test
    fun `unknown channel id falls back to wecom`() {
        assertEquals(Channel.WECOM, Channel.fromId("unknown"))
        assertEquals(Channel.BARK, Channel.fromId("bark"))
    }
}
